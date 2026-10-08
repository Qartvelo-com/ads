import CryptoKit
import Foundation
import ImageIO

/// Persists the last remote configuration (never the session token) so offline or slow starts still
/// know placement timeouts, kill switches and fallback ad units.
final class ConfigStore {
    static let suiteKeyConfig = "com.qartvelo.ads.remote_config_v1"
    static let suiteKeyApp = "com.qartvelo.ads.app_key"

    private let appKey: String
    private let defaults: UserDefaults

    init(appKey: String, defaults: UserDefaults = .standard) {
        self.appKey = appKey
        self.defaults = defaults
    }

    func load() -> RemoteConfig? {
        guard defaults.string(forKey: Self.suiteKeyApp) == appKey,
              let data = defaults.data(forKey: Self.suiteKeyConfig) else {
            return nil
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON else {
            Log.e("Ignoring unreadable cached config")
            return nil
        }
        return RemoteConfig.parse(json)
    }

    func save(_ data: Data) {
        defaults.set(appKey, forKey: Self.suiteKeyApp)
        defaults.set(data, forKey: Self.suiteKeyConfig)
    }
}

/// Creative files cached in `Caches/qartvelo_creatives`, named by URL hash so a creative served again
/// reuses its file. A file lives only as long as the longest-lived ad referencing it; stale files are
/// purged on start and before each download. Blocking; background queues only.
final class CreativeCache {
    static let maxImageBytes: Int64 = 2 * 1024 * 1024
    static let maxVideoBytes: Int64 = 40 * 1024 * 1024
    static let imageTimeoutMs: Int64 = 10_000
    static let videoTimeoutMs: Int64 = 45_000
    private static let partFileMaxAge: TimeInterval = 10 * 60

    private let api: ApiClient
    private let fileManager = FileManager.default
    private let lock = NSLock()
    private var expiries: [String: Int64] = [:]
    private var locks: [String: NSLock] = [:]

    lazy var directory: URL = {
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fileManager.temporaryDirectory
        return caches.appendingPathComponent("qartvelo_creatives", isDirectory: true)
    }()

    init(api: ApiClient) {
        self.api = api
    }

    /// Downloads (or reuses) the creative for `ad` and validates it.
    func fetch(_ ad: ServedAd, tracker: CallTracker? = nil) throws -> URL {
        purgeExpired()
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = Self.hash(ad.creativeURL.absoluteString) + Self.fileExtension(ad.creativeURL)
        let file = directory.appendingPathComponent(name)
        let fileLock = lockFor(name)
        fileLock.lock()
        defer { fileLock.unlock() }

        // Register the expiry before downloading so a concurrent purge never removes this file.
        lock.lock()
        let cached = expiries[name] != nil && fileManager.fileExists(atPath: file.path)
        expiries[name] = max(expiries[name] ?? 0, ad.expiresAt)
        lock.unlock()
        do {
            if !cached {
                let video = ad.creativeType == .video
                try api.download(
                    ad.creativeURL,
                    to: file,
                    maxBytes: video ? Self.maxVideoBytes : Self.maxImageBytes,
                    timeoutMs: video ? Self.videoTimeoutMs : Self.imageTimeoutMs,
                    tracker: tracker
                )
            }
            try Self.validate(ad.creativeType, file: file)
        } catch {
            try? fileManager.removeItem(at: file)
            lock.lock()
            expiries.removeValue(forKey: name)
            lock.unlock()
            throw error
        }
        return file
    }

    /// Deletes files whose ads have all expired, plus leftovers from earlier processes.
    func purgeExpired() {
        guard let files = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        let now = Clock.now()
        lock.lock()
        defer { lock.unlock() }
        for file in files {
            let name = file.lastPathComponent
            let stale: Bool
            if let expiry = expiries[name] {
                stale = now >= expiry
            } else if name.hasSuffix(".part") {
                // In-progress downloads write to `.part` files; only abandoned leftovers are removed.
                let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                stale = Date().timeIntervalSince(modified) > Self.partFileMaxAge
            } else {
                // Unknown files come from an earlier process whose ads are gone.
                stale = true
            }
            if stale {
                try? fileManager.removeItem(at: file)
                expiries.removeValue(forKey: name)
            }
        }
    }

    private func lockFor(_ name: String) -> NSLock {
        lock.lock()
        defer { lock.unlock() }
        if let existing = locks[name] { return existing }
        let created = NSLock()
        locks[name] = created
        return created
    }

    static func validate(_ type: CreativeType, file: URL) throws {
        let header = try readHeader(file, count: 12)
        switch type {
        case .image:
            guard hasImageSignature(header) else { throw NetworkError(message: "creative is not a PNG/JPEG/GIF/WebP image") }
            guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                  let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue, width > 0,
                  let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue, height > 0 else {
                throw NetworkError(message: "creative is not a decodable image")
            }
        case .video:
            // MP4/ISO-BMFF files carry an `ftyp` box at offset 4; anything else (an HTML error page,
            // a truncated upload) is rejected before we claim the ad is loaded.
            guard header.count >= 8, String(bytes: header[4..<8], encoding: .ascii) == "ftyp" else {
                throw NetworkError(message: "creative is not an MP4 video")
            }
        }
    }

    /// Magic bytes of the formats the backend accepts (png, jpg, gif, webp).
    static func hasImageSignature(_ bytes: [UInt8]) -> Bool {
        guard bytes.count >= 12 else { return false }
        func ascii(_ range: Range<Int>) -> String? { String(bytes: bytes[range], encoding: .ascii) }
        let png = bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
        let jpeg = bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF
        let gif = ascii(0..<4) == "GIF8"
        let webp = ascii(0..<4) == "RIFF" && ascii(8..<12) == "WEBP"
        return png || jpeg || gif || webp
    }

    private static func readHeader(_ file: URL, count: Int) throws -> [UInt8] {
        let handle = try FileHandle(forReadingFrom: file)
        defer { handle.closeFile() }
        return [UInt8](handle.readData(ofLength: count))
    }

    static func fileExtension(_ url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        return (2...4).contains(ext.count) && ext.allSatisfy({ $0.isLetter || $0.isNumber }) ? ".\(ext)" : ""
    }

    static func hash(_ value: String) -> String {
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }
}
