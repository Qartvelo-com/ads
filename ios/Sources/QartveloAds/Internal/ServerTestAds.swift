import Foundation

/// Metadata for the backend's public, never-billable test creatives. These assets are downloaded
/// from `/test-ads/` so the SDK does not ship a second copy of server-owned banner/video content.
enum ServerTestAds {
    struct Creative {
        let filename: String
        let width: Int
        let height: Int
        let duration: Int?
    }

    /// Adaptive slots select Retina artwork by proportions; legacy requests use widest-fit rotation.
    static func creative(for format: QartveloAdFormat, availableWidth: Int? = nil, availableHeight: Int? = nil) -> Creative {
        switch format {
        case .banner:
            if let width = availableWidth, let height = availableHeight, width > 0, height > 0 {
                let sizes = [(960, 150), (1320, 204), (2184, 270)]
                let ratio = Double(height) / Double(width)
                let size = sizes.min { abs(Double($0.1) / Double($0.0) - ratio) < abs(Double($1.1) / Double($1.0) - ratio) }!
                return Creative(filename: "banner_adaptive_v1_\(size.0)x\(size.1).png", width: size.0, height: size.1, duration: nil)
            }
            let sizes = [(320, 50), (320, 100), (300, 250), (468, 60), (728, 90)]
            let fitting = sizes.filter { $0.0 <= (availableWidth ?? 320) }
            let width = fitting.map { $0.0 }.max() ?? 300
            let size = sizes.filter { $0.0 == width }.randomElement()!
            return Creative(filename: "banner_\(size.0)x\(size.1).png", width: size.0, height: size.1, duration: nil)
        case .interstitial:
            return Creative(filename: "interstitial_1080x1920.png", width: 1080, height: 1920, duration: nil)
        case .rewarded:
            return Creative(filename: "rewarded.mp4", width: 720, height: 1280, duration: 15)
        }
    }

    static func make(placementId: String, format: QartveloAdFormat, creative: Creative, creativeURL: URL) -> ServedAd {
        let type: CreativeType = format == .rewarded ? .video : .image

        let now = Clock.now()
        return ServedAd(
            requestId: "req_test_\(UUID().uuidString)",
            adId: "ad_test_\(format.wireName)",
            campaignId: "cmp_test",
            creativeId: "cr_test_\(format.wireName)",
            format: format,
            creativeType: type,
            creativeURL: creativeURL,
            clickURL: nil,
            width: creative.width,
            height: creative.height,
            durationSeconds: creative.duration,
            impressionToken: "public_test",
            expiresAt: now + 86_400_000,
            test: true,
            localTest: true
        )
    }
}
