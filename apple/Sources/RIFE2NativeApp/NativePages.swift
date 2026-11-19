/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

import Foundation
import SwiftUI
import WebKit
#if os(iOS) || os(visionOS)
import UIKit
/// A color of the platform.
public typealias NativeColor = UIColor
#else
import AppKit
/// A color of the platform.
public typealias NativeColor = NSColor
#endif

/// The pages of the site in a web view, with what they need of the app that a
/// web view doesn't do by itself: links that lead out of the site open in the
/// browser, files that a page saves are offered to save or share, a page can
/// print, and a page that the system stopped drawing is loaded again.
///
/// The pages know that they're in an app through the `context:nativeAppScript`
/// value of their templates. A page and the app tell each other what's their
/// own through it: the app ``listen(_:_:)``s for what a page tells it with
/// `rife2NativeApp.tell(name, data)`, and ``tell(_:_:)``s a page what it
/// hears with `rife2NativeApp.on(name, heard)`. An app adds more of its own
/// by subclassing this. A subclass that overrides a delegate method calls
/// `super`'s, which is where links leave the site, pages print, a stopped page
/// loads again and what waits for a page to load runs. An app extension, like
/// a widget, uses ``NativeSite`` without this.
@available(iOSApplicationExtension, unavailable)
@available(visionOSApplicationExtension, unavailable)
@available(macOSApplicationExtension, unavailable)
open class NativePages: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    /// What a page and its app tell each other about, which a page reads as
    /// `rife2NativeApp.protocol`: 1 has ``listen(_:_:)`` and ``tell(_:_:)``.
    public static let `protocol` = 1

    /// The path of the page that's shown.
    @Published public private(set) var path = ""
    /// Whether there's a page to go back to.
    @Published public private(set) var canGoBack = false
    /// Whether there's a page to go forward to.
    @Published public private(set) var canGoForward = false
    /// The web view, made the first time it's asked for.
    public private(set) lazy var view: WKWebView = made()
    /// The site that answers the web view.
    public let site: NativeSite
    private var first: URL?
    private let background: NativeColor?
    private let inspectable: Bool
    private let zooms: Bool
    private var built = false
    // what listen adds to, once what the pages are told at their start is there
    private var content: WKUserContentController?
    private var handler: NativeSchemeHandler?
    // pages of the history that the app answered with itself, at the address that it answered, for when the
    // web view shows one again from its cache; a page that sends the web view on is replaced in the history
    // by where it sends it, under the same item
    private let owned = NSMapTable<WKBackForwardListItem, NSString>.weakToStrongObjects()
    // the page of one line that sends the web view on finished, and where it sends it didn't start yet
    private var forwarding = false
    // how many pages in a row sent the web view on, which a browser stops after a while
    private var forwards = 0
    private let saving = Saving()
    private var watched: [NSKeyValueObservation] = []
    private var listened: [String: (Any) -> Void] = [:]
    // what runs once the page that's loading is there
    private var waiting: [() -> Void] = []
    private var inFront: NSObjectProtocol?
    private var reloaded: Date?
    // a reload that's owed, for when the app is in front again or after a while
    private var owed: NSObjectProtocol?
    private var retry: DispatchWorkItem?
    private var waited: TimeInterval = 10

    /// - Parameters:
    ///   - start: the path of the first page
    ///   - site: the site that answers the web view
    ///   - background: the color behind the pages, which shows before the
    ///     first one is drawn and when one is pulled past its edge; the web
    ///     view's own when it's not given
    ///   - inspectable: whether Safari's Web Inspector can look into the pages
    ///   - zooms: whether a page can be enlarged with two fingers, which a
    ///     screen of an app usually can't; on a Mac, a page always can, as in
    ///     a browser
    public init(start: String = "/", site: NativeSite = .shared, background: NativeColor? = nil, inspectable: Bool = false, zooms: Bool = false) {
        self.site = site
        self.background = background
        self.inspectable = inspectable
        self.zooms = zooms
        super.init()
        // without the slash, the path would be part of the host
        first = URL(string: site.root + (start.hasPrefix("/") ? start : "/" + start))
        #if os(iOS) || os(visionOS)
        let active = UIApplication.didBecomeActiveNotification
        #else
        let active = NSApplication.didBecomeActiveNotification
        #endif
        inFront = NotificationCenter.default.addObserver(forName: active, object: nil, queue: .main) { [weak self] _ in
            self?.activated()
        }
    }

    /// Changes the configuration of the web view before it's made, to
    /// ``listen(_:_:)`` for what the pages tell the app for instance.
    open func configure(_ configuration: WKWebViewConfiguration) {
    }

    /// Changes the web view once it's made, before its first page loads.
    /// This is called while ``view`` is being made, so an override uses the
    /// view that it's given: reading ``view`` here would make it again,
    /// without end.
    open func prepare(_ view: WKWebView) {
    }

    /// Called each time the app is in front, when it starts too, and when
    /// it's back from the background or from another app.
    open func activated() {
    }

    private func made() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let handler = NativeSchemeHandler(site: site)
        self.handler = handler
        configuration.setURLSchemeHandler(handler, forURLScheme: site.scheme)
        // the controller holds its handlers strongly, and the view holds the controller
        configuration.userContentController.add(Handler(self), name: "rife2")
        configure(configuration)
        // what the pages know of the app before their own scripts run
        configuration.userContentController.addUserScript(WKUserScript(source: Self.told(Array(listened.keys), zooms: zooms),
                                                                       injectionTime: .atDocumentStart, forMainFrameOnly: false))
        content = configuration.userContentController

        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.isInspectable = inspectable
        if let background {
            view.underPageBackgroundColor = background
            #if os(iOS) || os(visionOS)
            view.isOpaque = false
            view.backgroundColor = background
            view.scrollView.backgroundColor = background
            #endif
        }
        #if os(macOS)
        // two fingers enlarge a page, as in a browser
        view.allowsMagnification = true
        #endif
        prepare(view)
        watched = [
            view.observe(\.url, options: [.initial, .new]) { [weak self] view, _ in
                let path = view.url?.path ?? ""
                DispatchQueue.main.async {
                    self?.path = path
                }
            },
            view.observe(\.canGoBack, options: [.initial, .new]) { [weak self] view, _ in
                let back = view.canGoBack
                DispatchQueue.main.async {
                    self?.canGoBack = back
                }
            },
            view.observe(\.canGoForward, options: [.initial, .new]) { [weak self] view, _ in
                let forward = view.canGoForward
                DispatchQueue.main.async {
                    self?.canGoForward = forward
                }
            },
        ]
        built = true
        // on this view, the lazy one isn't there until this returns
        if let first {
            view.load(URLRequest(url: first))
        }
        return view
    }

    // the script that tells a page what the app hears, written as a script writes its texts
    static func told(_ heard: [String], zooms: Bool) -> String {
        let native: [String: Any] = ["protocol": Self.protocol, "heard": heard.sorted(), "zooms": zooms]
        let written = (try? JSONSerialization.data(withJSONObject: native)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        return "window.rife2NativeAppHost = \(written);"
    }

    deinit {
        for observer in [inFront, owed].compactMap({ $0 }) {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    /// Whether an address is one of the site.
    public func own(_ url: URL) -> Bool {
        site.own(url)
    }

    /// Shows the page at a path of the site. Before the web view is made,
    /// that's the first page that it shows.
    public func go(_ path: String) {
        // without the slash, the path would be part of the host
        if let url = URL(string: site.root + (path.hasPrefix("/") ? path : "/" + path)) {
            open(url)
        }
    }

    /// Shows the page at an address of the site, one that a widget or a
    /// link to the app was tapped for for instance. Before the web view is
    /// made, that's the first page that it shows.
    ///
    /// - Returns: whether the address is one of the site, the only ones that are shown
    @discardableResult
    public func open(_ url: URL) -> Bool {
        guard own(url) else {
            return false
        }
        // also the page that's loaded when what draws the pages stopped before there was one
        first = url
        guard built else {
            return true
        }
        settle()
        view.load(URLRequest(url: url))
        return true
    }

    /// Goes back a page, when there's one to go back to.
    @discardableResult
    public func back() -> Bool {
        guard view.canGoBack else {
            return false
        }
        settle()
        view.goBack()
        return true
    }

    /// Goes forward a page, when there's one to go forward to.
    @discardableResult
    public func forward() -> Bool {
        guard view.canGoForward else {
            return false
        }
        settle()
        view.goForward()
        return true
    }

    /// Loads the page that's shown again.
    public func reload() {
        settle()
        // what draws the pages stopped before there was one, or the first one didn't load
        if view.url == nil, let first {
            view.load(URLRequest(url: first))
        } else {
            view.reload()
        }
    }

    /// Runs something once the page that's loading is there, the one that a
    /// redirect leads to, or now when none is. When the page doesn't load, or
    /// says why it couldn't be shown, it doesn't run. A load that's stopped
    /// before the web view started it, which tells nobody, leaves it waiting
    /// for the next one.
    public func whenLoaded(_ run: @escaping () -> Void) {
        // a page that's on its way: through a redirect, or loaded again once what draws it can
        if built && !view.isLoading && !forwarding && owed == nil && retry == nil {
            run()
        } else {
            waiting.append(run)
        }
    }

    // a page that loads by itself doesn't owe a reload anymore
    private func settle() {
        if let owed {
            NotificationCenter.default.removeObserver(owed)
            self.owed = nil
        }
        retry?.cancel()
        retry = nil
    }

    /// Hears what the pages tell the app with `rife2NativeApp.tell(name, data)`,
    /// which arrives as a script's value does: a `String`, an `NSNumber`, an
    /// `NSDate`, an array, a dictionary or `NSNull`. Only a page of the site is heard. A
    /// page knows what the app hears with `rife2NativeApp.hears(name)`, from
    /// the next page on when this is called after ``configure(_:)``.
    public func listen(_ name: String, _ heard: @escaping (Any) -> Void) {
        let new = listened[name] == nil
        listened[name] = heard
        if let content, new {
            let quoted = (try? JSONSerialization.data(withJSONObject: name, options: .fragmentsAllowed)).map { String(decoding: $0, as: UTF8.self) } ?? "\"\""
            content.addUserScript(WKUserScript(source: "window.rife2NativeAppHost && window.rife2NativeAppHost.heard.push(\(quoted));",
                                                                                injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
    }

    /// Tells the page that's shown something, which it hears with
    /// `rife2NativeApp.on(name, heard)`; nothing happens when it doesn't
    /// listen. What's told is what a script can be given: a `String`, a
    /// number, a `Bool`, a `Date`, an array, a dictionary or `NSNull`.
    public func tell(_ name: String, _ data: Any = NSNull()) {
        run("const app = window.rife2NativeApp; return app && app.told ? app.told(name, data) === true : false",
            ["name": name, "data": data]) { _ in }
    }

    /// Calls a function of the page that's shown, `showChart` or
    /// `charts.show` for instance, with arguments that a script can be
    /// given: a `String`, a number, a `Bool`, a `Date`, an array, a
    /// dictionary or `NSNull`. The function is found from `window` on, as a
    /// `function` or a `var` of a page's script is, not a `const`, a `let`
    /// or a `class`, which a script puts on `window` itself when it's called.
    ///
    /// - Parameters:
    ///   - function: the name of the function, from `window` on
    ///   - arguments: what the function is given
    ///   - answered: told whether the page has the function, and what it
    ///     returned when it's something that a script can return: after the
    ///     promise that it returned was kept, for one that does, and nothing
    ///     when it failed
    public func call(_ function: String, _ arguments: Any..., then answered: ((_ called: Bool, _ result: Any?) -> Void)? = nil) {
        let script = """
            let owner = window, found = window;
            for (const name of path.split('.')) {
                owner = found;
                found = found == null ? undefined : found[name];
            }
            if (typeof found !== 'function') return [false];
            let result;
            try {
                result = await found.apply(owner, args);
            } catch (e) {
                return [true, null];
            }
            try {
                return [true, JSON.parse(JSON.stringify(result === undefined ? null : result))];
            } catch (e) {
                return [true, null];
            }
            """
        run(script, ["path": function, "args": arguments]) { result in
            guard let answered else {
                return
            }
            let said = result as? [Any]
            let value = said?.count == 2 ? said?[1] : nil
            answered(said?.first as? Bool ?? false, value is NSNull ? nil : value)
        }
    }

    /// Presses what a selector finds on the page that's shown, a button for instance.
    ///
    /// - Parameters:
    ///   - selector: the CSS selector of what's pressed, the first it finds
    ///   - pressed: told whether the page has it
    public func press(_ selector: String, then pressed: ((Bool) -> Void)? = nil) {
        run("const found = document.querySelector(selector); if (!found) return false; found.click(); return true", ["selector": selector]) { result in
            pressed?(result as? Bool ?? false)
        }
    }

    // a script with what it's given as arguments, which the web view writes for it
    private func run(_ script: String, _ arguments: [String: Any], _ answered: @escaping (Any?) -> Void) {
        guard built else {
            answered(nil)
            return
        }
        view.callAsyncJavaScript(script, arguments: arguments, in: nil, in: .page) { result in
            switch result {
            case .success(let value):
                answered(value)
            case .failure(let error):
                log.error("A script of the app failed on the page: \(error.localizedDescription, privacy: .public)")
                answered(nil)
            }
        }
    }

    /// What a page of the site asks of the app through the "rife2" message
    /// handler; a page from elsewhere is ignored.
    open func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        let origin = message.frameInfo.securityOrigin
        guard message.name == "rife2", origin.protocol.lowercased() == site.scheme.lowercased(), origin.host.lowercased() == site.host.lowercased(), origin.port == 0,
              let said = message.body as? [String: Any], let action = said["action"] as? String else {
            return
        }
        switch action {
        case "print":
            printPage()
        case "tell":
            if let name = said["name"] as? String, let heard = listened[name] {
                heard(said["data"] ?? NSNull())
            }
        default:
            log.error("A page asked for \(action, privacy: .public), which this version of rife2-native-app doesn't know")
        }
    }

    open func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        // only what a page that sends the web view on started counts on
        if !forwarding {
            forwards = 0
        }
        forwarding = false
        handler?.answered = nil
    }

    open func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loaded(webView)
    }

    open func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        failed(webView, error)
    }

    open func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        failed(webView, error)
    }

    // A page that sends the web view on isn't the one that loads, the one
    // that it leads to is, and one that says why a page couldn't be shown
    // is one that didn't load.
    private func loaded(_ webView: WKWebView) {
        guard !webView.isLoading else {
            return
        }
        let own: NativeSchemeHandler.Own?
        if let answered = handler?.answered, answered.url == webView.url {
            handler?.answered = nil
            own = answered.own
            if let item = webView.backForwardList.currentItem {
                if let own {
                    owned.setObject("\(own == .forward ? "forward" : "failed") \(answered.url.absoluteString)" as NSString, forKey: item)
                } else {
                    owned.removeObject(forKey: item)
                }
            }
        } else {
            // from the cache, without asking for it again, when it's still the page that was answered
            let kept = webView.backForwardList.currentItem.flatMap { owned.object(forKey: $0) as String? }?.split(separator: " ", maxSplits: 1)
            own = kept?.count == 2 && String(kept![1]) == webView.url?.absoluteString ? (kept![0] == "forward" ? .forward : .failed) : nil
        }
        // what it sends the web view to starts after this, or leaves the site, see decidePolicyFor
        if own == .forward {
            forwarding = true
            forwards += 1
            return
        }
        forwards = 0
        let run = waiting
        waiting = []
        if own != .failed {
            for each in run {
                each()
            }
        }
    }

    private func failed(_ webView: WKWebView, _ error: Error) {
        ended(webView)
    }

    // What waits for a page that doesn't come is let go of. A load that
    // another one took the place of is cancelled first, so that's only
    // known once the delegate was told.
    private func ended(_ webView: WKWebView) {
        forwarding = false
        forwards = 0
        handler?.answered = nil
        DispatchQueue.main.async { [weak self, weak webView] in
            if let webView, !webView.isLoading {
                self?.waiting = []
            }
        }
    }

    // The system stops what draws the pages when it needs the memory, which
    // leaves the web view empty. It's loaded again after this returns, since
    // a process that can't start calls this again from inside reload(). When
    // it stopped right after the last time, that waits until the app is in
    // front again, or a while when it's in front already, longer each time.
    open func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let now = Date()
        guard let reloaded, now.timeIntervalSince(reloaded) < 10 else {
            reloaded = now
            waited = 10
            DispatchQueue.main.async { [weak self] in
                self?.loadAgain(webView)
            }
            return
        }
        log.error("What draws the pages stopped again right after it was started again")
        guard owed == nil && retry == nil else {
            return
        }
        #if os(iOS) || os(visionOS)
        let active = UIApplication.didBecomeActiveNotification
        let inFront = UIApplication.shared.applicationState == .active
        #else
        let active = NSApplication.didBecomeActiveNotification
        let inFront = NSApplication.shared.isActive
        #endif
        owed = NotificationCenter.default.addObserver(forName: active, object: nil, queue: .main) { [weak self, weak webView] _ in
            guard let self, let webView else {
                return
            }
            self.reloaded = Date()
            self.loadAgain(webView)
        }
        if inFront {
            let later = DispatchWorkItem { [weak self, weak webView] in
                guard let self, let webView else {
                    return
                }
                self.reloaded = Date()
                self.waited = min(self.waited * 2, 300)
                self.loadAgain(webView)
            }
            retry = later
            DispatchQueue.main.asyncAfter(deadline: .now() + waited, execute: later)
        }
    }

    // a process that stopped before the first page was there leaves nothing to reload
    private func loadAgain(_ webView: WKWebView) {
        settle()
        if webView.url == nil, let url = first {
            webView.load(URLRequest(url: url))
        } else {
            webView.reload()
        }
    }

    open func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        if action.shouldPerformDownload {
            return .download
        }
        // pages that keep sending the web view on, to themselves for instance
        if action.targetFrame?.isMainFrame ?? false, forwards >= 20 {
            log.error("The site sent the web view on 20 times in a row, the last time to \(action.request.url?.absoluteString ?? "", privacy: .public)")
            ended(webView)
            return .cancel
        }
        // A link out of the site, to a web page or a phone number for
        // instance, goes to the system: the app has no way back from it. What
        // a frame inside a page loads stays there.
        guard action.targetFrame?.isMainFrame ?? true, let url = action.request.url, let scheme = url.scheme?.lowercased(),
              !own(url), !["about", "blob", "data", "javascript"].contains(scheme) else {
            return .allow
        }
        if leaving.contains(scheme) {
            await leave(to: url)
        }
        // a page that sent the web view out of the site ended there
        ended(webView)
        return .cancel
    }

    /// The schemes of the addresses that a link takes out of the app to the
    /// system; the others go nowhere, since some would start what the user
    /// didn't choose to.
    open var leaving: Set<String> {
        ["http", "https", "mailto", "tel", "sms", "facetime"]
    }

    // what the site answers as a file to save is saved, not shown
    open func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse) async -> WKNavigationResponsePolicy {
        let disposition = (response.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition") ?? ""
        if disposition.lowercased().hasPrefix("attachment") || !response.canShowMIMEType {
            return .download
        }
        return .allow
    }

    // a link of the site that opens a new window shows its page here, there's one window
    open func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                      windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url, own(url) {
            settle()
            webView.load(action.request)
        }
        return nil
    }

    // a page that became a file to save doesn't load
    open func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = saving
        ended(webView)
    }

    open func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = saving
        ended(webView)
    }

    /// Opens an address outside of the site.
    @MainActor
    open func leave(to url: URL) async {
        #if os(iOS) || os(visionOS)
        await UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }

    /// Shows the print dialog for the page.
    open func printPage() {
        #if os(iOS) || os(visionOS)
        let job = UIPrintInfo(dictionary: nil)
        job.jobName = view.title ?? ""
        let dialog = UIPrintInteractionController.shared
        dialog.printInfo = job
        dialog.printFormatter = view.viewPrintFormatter()
        dialog.present(animated: true)
        #else
        guard let window = view.window else {
            return
        }
        let paper = NSPrintInfo.shared
        paper.horizontalPagination = .fit
        paper.verticalPagination = .automatic
        let printing = view.printOperation(with: paper)
        // a web view prints from a view of the size of its paper
        printing.view?.frame = NSRect(origin: .zero, size: paper.paperSize)
        printing.runModal(for: window, delegate: nil, didRun: nil, contextInfo: nil)
        #endif
    }
}

// Hands the messages of the pages on without keeping the pages.
@available(iOSApplicationExtension, unavailable)
@available(visionOSApplicationExtension, unavailable)
@available(macOSApplicationExtension, unavailable)
private final class Handler: NSObject, WKScriptMessageHandler {
    private weak var pages: NativePages?

    init(_ pages: NativePages) {
        self.pages = pages
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        pages?.userContentController(controller, didReceive: message)
    }
}

#if os(iOS) || os(visionOS)
// What a page saves goes to the sheet of iOS that saves and sends files.
final class Saving: NSObject, WKDownloadDelegate {
    private var saved: [ObjectIdentifier: URL] = [:]

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String) async -> URL? {
        // under the name that the page gave it, in a folder of its own: a
        // file of that name may still be on its way somewhere
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // only a name, so that the folder above it is the one made here, which goes once it's saved
        let name = (suggestedFilename as NSString).lastPathComponent
        let file = folder.appendingPathComponent(["", ".", "..", "/"].contains(name) ? "download" : name)
        saved[ObjectIdentifier(download)] = file
        return file
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let file = saved.removeValue(forKey: ObjectIdentifier(download)) else {
            return
        }
        guard let view = download.webView, let front = view.window?.rootViewController?.front else {
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
            return
        }
        let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, _, _, _ in
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        }
        // an iPad shows it as a bubble, which needs a place
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        front.present(sheet, animated: true)
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        if let file = saved.removeValue(forKey: ObjectIdentifier(download)) {
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        }
        log.error("Saving failed: \(error.localizedDescription, privacy: .public)")
    }
}

private extension UIViewController {
    // what's on the screen, to show a sheet over
    var front: UIViewController {
        presentedViewController?.front ?? self
    }
}

/// The pages as a SwiftUI view.
@available(iOSApplicationExtension, unavailable)
@available(visionOSApplicationExtension, unavailable)
public struct NativePagesView: UIViewRepresentable {
    /// The pages that this shows.
    public let pages: NativePages

    /// A view of pages.
    public init(pages: NativePages) {
        self.pages = pages
    }

    public func makeUIView(context: Context) -> WKWebView {
        pages.view
    }

    public func updateUIView(_ view: WKWebView, context: Context) {
    }
}
#else
// What a page saves goes where the person says, under the name that the page
// gave it unless they give it another one.
final class Saving: NSObject, WKDownloadDelegate {
    @MainActor
    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String) async -> URL? {
        let asking = NSSavePanel()
        asking.nameFieldStringValue = suggestedFilename
        asking.canCreateDirectories = true
        guard let window = download.webView?.window, await asking.beginSheetModal(for: window) == .OK, let file = asking.url else {
            return nil
        }
        // a web view doesn't write over a file, and the person just said that it may
        try? FileManager.default.removeItem(at: file)
        return file
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        log.error("Saving failed: \(error.localizedDescription, privacy: .public)")
    }
}

/// The pages as a SwiftUI view.
@available(macOSApplicationExtension, unavailable)
public struct NativePagesView: NSViewRepresentable {
    /// The pages that this shows.
    public let pages: NativePages

    /// A view of pages.
    public init(pages: NativePages) {
        self.pages = pages
    }

    public func makeNSView(context: Context) -> WKWebView {
        pages.view
    }

    public func updateNSView(_ view: WKWebView, context: Context) {
    }
}
#endif
