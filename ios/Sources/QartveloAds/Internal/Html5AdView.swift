import UIKit
import WebKit

/// Path rules for bundle files. Pure functions, shared by the parser, the cache and the web view.
enum Html5Files {
    static let index = "index.html"

    /// A plain relative path under the bundle base: no `..`, no absolute path, no scheme, no query.
    static func isSafePath(_ path: String) -> Bool {
        guard !path.isEmpty, path.count <= 200, !path.hasPrefix("/") else { return false }
        let forbidden = CharacterSet(charactersIn: "\\:?#%").union(.whitespacesAndNewlines).union(.controlCharacters)
        guard path.rangeOfCharacter(from: forbidden) == nil else { return false }
        return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    /// The base of a bundle from its `creative_url`, or nil when it is not an `index.html` URL.
    static func base(of creativeURL: URL) -> URL? {
        let string = creativeURL.absoluteString
        guard string.hasSuffix("/" + index), creativeURL.query == nil, creativeURL.fragment == nil else { return nil }
        return URL(string: String(string.dropLast(index.count)))
    }

    /// The path of `url` inside the bundle at `base`, or nil when it is outside it.
    static func relativePath(base: String, url: String) -> String? {
        guard url.hasPrefix(base) else { return nil }
        var rest = Substring(url.dropFirst(base.count))
        if let cut = rest.firstIndex(where: { $0 == "?" || $0 == "#" }) { rest = rest[..<cut] }
        guard let decoded = String(rest).removingPercentEncoding, isSafePath(decoded) else { return nil }
        return decoded
    }

    /// The cached copy of `url` in `directory`, or nil when it is outside the bundle or not cached.
    static func resolve(directory: URL, base: String, url: String) -> URL? {
        guard let path = relativePath(base: base, url: url) else { return nil }
        let file = directory.appendingPathComponent(path).standardizedFileURL
        let root = directory.standardizedFileURL.path + "/"
        var isDirectory: ObjCBool = false
        guard file.path.hasPrefix(root), FileManager.default.fileExists(atPath: file.path, isDirectory: &isDirectory), !isDirectory.boolValue else { return nil }
        return file
    }

    private static let mimeTypes = [
        "html": "text/html", "css": "text/css", "js": "text/javascript", "png": "image/png",
        "jpg": "image/jpeg", "gif": "image/gif", "svg": "image/svg+xml",
    ]

    /// Content type of a bundle file, or nil for a type bundles never contain.
    static func mimeType(_ path: String) -> String? {
        guard let dot = path.lastIndex(of: ".") else { return nil }
        return mimeTypes[path[path.index(after: dot)...].lowercased()]
    }
}

/// Request and navigation decisions of the locked web view. Pure, so they are unit tested.
enum Html5Policy {
    static let clickURL = "qartvelo://click"
    static let readyTimeoutMs: Int64 = 6_000
    static let readyPollMs: Int64 = 100
    /// The private scheme the bundle is served under; the page can reach nothing else.
    static let scheme = "qartvelo-bundle"
    static let pageBase = "qartvelo-bundle://ad/"

    /// Like the bundle route's policy (CONTRACT.md "HTML5 creatives"), without `sandbox`: the page
    /// already has its own private origin and a non-persistent data store, and a sandboxed opaque
    /// origin would stop WebKit from loading the bundle's own files from the private scheme.
    static let csp = "default-src 'self' data:; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'"

    enum Decision: Equatable {
        /// Answer from the downloaded copy.
        case serve(URL, mimeType: String)
        /// A file of this bundle that was not pre-downloaded (another layout): load it from the bundle URL.
        case network(URL, mimeType: String)
        /// Anything else.
        case block
    }

    static func intercept(directory: URL, bundleBase: URL, url: String, method: String) -> Decision {
        guard method.uppercased() == "GET", let path = Html5Files.relativePath(base: pageBase, url: url),
              let mimeType = Html5Files.mimeType(path) else { return .block }
        if let file = Html5Files.resolve(directory: directory, base: pageBase, url: url) { return .serve(file, mimeType: mimeType) }
        guard let remote = URL(string: path, relativeTo: bundleBase)?.absoluteURL else { return .block }
        return .network(remote, mimeType: mimeType)
    }

    /// Every navigation is stopped; `qartvelo://click` and any navigation after the first load are clicks.
    static func isClick(_ url: String, firstLoadDone: Bool) -> Bool {
        url.hasPrefix(clickURL) || firstLoadDone
    }

    static func headers(mimeType: String) -> [String: String] {
        [
            "Content-Type": mimeType,
            "Content-Security-Policy": csp,
            "X-Content-Type-Options": "nosniff",
            "Cache-Control": "no-store",
        ]
    }
}

/// A shown HTML5 creative. `load` reports exactly one of ready or failed; taps that leave the ad
/// report `onClick` (the caller counts at most one click per impression).
protocol Html5Surface: AnyObject {
    var view: UIView { get }
    func load(onReady: @escaping () -> Void, onFailed: @escaping (String) -> Void, onClick: @escaping () -> Void)
    func pause()
    func resume()
    func destroy()
}

/// HTML5 creatives in a locked `WKWebView`: the bundle is served under a private scheme from the
/// downloaded copy (or the bundle URL for another layout's files); no message handlers, a
/// non-persistent data store, content rules that block every http(s) load, media only after a
/// gesture. Readiness is polled through `window.__qartvelo.ready`.
final class Html5AdView: NSObject, Html5Surface, WKNavigationDelegate, WKUIDelegate {
    private static let readyJS = "!!(window.__qartvelo && window.__qartvelo.ready === true)"
    private static let pauseJS = "window.__qartvelo && window.__qartvelo.pause && window.__qartvelo.pause();"
    private static let resumeJS = "window.__qartvelo && window.__qartvelo.resume && window.__qartvelo.resume();"

    private let webView: WKWebView
    private let handler: BundleSchemeHandler
    private var onReady: (() -> Void)?
    private var onFailed: ((String) -> Void)?
    private var onClick: (() -> Void)?
    private var firstLoadDone = false
    private var settled = false
    private var destroyed = false
    private var pollTask: DispatchWorkItem?
    private var timeoutTask: DispatchWorkItem?

    var view: UIView { webView }

    /// The surface for an HTML5 `ad` whose bundle was downloaded. Tests swap in a fake through `TestHooks`.
    static func create(_ ad: ServedAd) -> Html5Surface? {
        if let factory = TestHooks.html5SurfaceFactory { return factory(ad) }
        guard let bundle = ad.bundle, let directory = ad.file else { return nil }
        return Html5AdView(bundle: bundle, directory: directory)
    }

    private init(bundle: Html5Bundle, directory: URL) {
        handler = BundleSchemeHandler(bundle: bundle, directory: directory)
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.setURLSchemeHandler(handler, forURLScheme: Html5Policy.scheme)
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.dataDetectorTypes = []
        if let rules = Html5ContentRules.compiled { configuration.userContentController.add(rules) }
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsLinkPreview = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
    }

    func load(onReady: @escaping () -> Void, onFailed: @escaping (String) -> Void, onClick: @escaping () -> Void) {
        self.onReady = onReady
        self.onFailed = onFailed
        self.onClick = onClick
        timeoutTask = Main.postDelayed(Html5Policy.readyTimeoutMs) { [weak self] in
            self?.fail("not ready within \(Html5Policy.readyTimeoutMs) ms")
        }
        guard let url = URL(string: Html5Policy.pageBase + Html5Files.index) else {
            fail("invalid page URL")
            return
        }
        webView.load(URLRequest(url: url))
        schedulePoll()
    }

    func pause() {
        guard !destroyed else { return }
        webView.evaluateJavaScript(Self.pauseJS, completionHandler: nil)
    }

    func resume() {
        guard !destroyed else { return }
        webView.evaluateJavaScript(Self.resumeJS, completionHandler: nil)
    }

    func destroy() {
        guard !destroyed else { return }
        destroyed = true
        settled = true
        pollTask?.cancel()
        timeoutTask?.cancel()
        onReady = nil
        onFailed = nil
        onClick = nil
        handler.stopAll()
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.removeFromSuperview()
    }

    private func schedulePoll() {
        pollTask = Main.postDelayed(Html5Policy.readyPollMs) { [weak self] in
            guard let self = self, !self.settled else { return }
            self.webView.evaluateJavaScript(Self.readyJS) { [weak self] result, _ in
                if (result as? Bool) == true { self?.ready() }
            }
            self.schedulePoll()
        }
    }

    private func ready() {
        guard !settled else { return }
        settled = true
        pollTask?.cancel()
        timeoutTask?.cancel()
        onReady?()
    }

    private func fail(_ reason: String) {
        guard !settled else { return }
        settled = true
        pollTask?.cancel()
        timeoutTask?.cancel()
        Log.i("HTML5 creative failed: \(reason)")
        onFailed?(reason)
    }

    private func click() {
        guard !destroyed else { return }
        onClick?()
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let url = navigationAction.request.url?.absoluteString ?? ""
        let mainFrame = navigationAction.targetFrame?.isMainFrame ?? true
        // Only the first load of the page itself; every later navigation is stopped.
        if !firstLoadDone && mainFrame && url == Html5Policy.pageBase + Html5Files.index {
            decisionHandler(.allow)
            return
        }
        if mainFrame && Html5Policy.isClick(url, firstLoadDone: firstLoadDone) { Main.post { [weak self] in self?.click() } }
        decisionHandler(.cancel)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        firstLoadDone = true
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if !firstLoadDone { fail("page failed: \((error as NSError).code)") }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // Never let a crashed or killed web process leave a blank ad: drop it.
        fail("web content process ended")
        destroy()
    }

    // MARK: - WKUIDelegate

    /// `window.open` (or a target=_blank link) is a click; no window is ever created.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if firstLoadDone { Main.post { [weak self] in self?.click() } }
        return nil
    }
}

/// Serves the bundle under `qartvelo-bundle://ad/`: downloaded files from disk, another layout's
/// files from the bundle URL, anything else refused.
private final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {
    private let bundle: Html5Bundle
    private let directory: URL
    private let session: URLSession
    private var active: [ObjectIdentifier: URLSessionDataTask?] = [:]

    init(bundle: Html5Bundle, directory: URL) {
        self.bundle = bundle
        self.directory = directory
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        session = URLSession(configuration: configuration)
        super.init()
    }

    func stopAll() {
        active.values.forEach { $0?.cancel() }
        active.removeAll()
        session.invalidateAndCancel()
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        let key = ObjectIdentifier(urlSchemeTask)
        let request = urlSchemeTask.request
        let url = request.url?.absoluteString ?? ""
        switch Html5Policy.intercept(directory: directory, bundleBase: bundle.baseURL, url: url, method: request.httpMethod ?? "GET") {
        case .serve(let file, let mimeType):
            guard let data = try? Data(contentsOf: file) else {
                fail(urlSchemeTask)
                return
            }
            respond(urlSchemeTask, data: data, mimeType: mimeType)
        case .network(let remote, let mimeType):
            active[key] = .some(nil)
            let task = session.dataTask(with: remote) { [weak self] data, response, error in
                Main.post {
                    guard let self = self, self.active.removeValue(forKey: key) != nil else { return } // Stopped meanwhile.
                    guard error == nil, let data = data, let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                        self.fail(urlSchemeTask)
                        return
                    }
                    self.respond(urlSchemeTask, data: data, mimeType: mimeType)
                }
            }
            active[key] = task
            task.resume()
        case .block:
            Log.d("HTML5 request blocked: \(request.httpMethod ?? "GET") \(request.url?.path ?? "")")
            fail(urlSchemeTask)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        active.removeValue(forKey: ObjectIdentifier(urlSchemeTask))??.cancel()
    }

    private func respond(_ task: WKURLSchemeTask, data: Data, mimeType: String) {
        guard let url = task.request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: Html5Policy.headers(mimeType: mimeType)) else {
            fail(task)
            return
        }
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    private func fail(_ task: WKURLSchemeTask) {
        task.didFailWithError(NSError(domain: NSURLErrorDomain, code: NSURLErrorResourceUnavailable))
    }
}

/// Content rules that block every http(s) load in HTML5 web views (the bundle is served under the
/// private scheme). Compiled once, at startup, off the critical path; until then CSP still applies.
enum Html5ContentRules {
    private(set) static var compiled: WKContentRuleList?
    private static var started = false

    static func prepare() {
        guard !started else { return }
        started = true
        let rules = #"[{"trigger":{"url-filter":"^https?:"},"action":{"type":"block"}}]"#
        WKContentRuleListStore.default()?.compileContentRuleList(forIdentifier: "qartvelo-html5-v1", encodedContentRuleList: rules) { list, error in
            Main.post {
                if let list = list { compiled = list } else { Log.e("HTML5 content rules unavailable: \(error?.localizedDescription ?? "unknown")") }
            }
        }
    }
}
