/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

import Foundation
import SwiftUI
import WebKit
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// The pages of the site in a web view, with what they need of the app that a
/// web view doesn't do by itself: links that lead out of the site open in the
/// browser, files that a page saves are offered to save or share, a page can
/// print, and a page that the system stopped drawing is loaded again.
///
/// The pages know that they're in an app through the `context:nativeApp`
/// value of their templates. An app adds what's its own by subclassing this,
/// for instance its own message handlers in ``configure(_:)``. An app
/// extension, like a widget, uses ``NativeSite`` without this.
@available(iOSApplicationExtension, unavailable)
@available(macOSApplicationExtension, unavailable)
open class NativePages: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    /// The path of the page that's shown.
    @Published public private(set) var path = ""
    /// The web view, made the first time it's asked for.
    public private(set) lazy var view: WKWebView = made()
    /// The site that answers the web view.
    public let site: NativeSite
    private let start: String
    private let saving = Saving()
    private var watched: NSKeyValueObservation?
    private var reloaded: Date?
    // a reload that's owed, for when the app is in front again or after a while
    private var activated: NSObjectProtocol?
    private var retry: DispatchWorkItem?
    private var waited: TimeInterval = 10

    /// - Parameters:
    ///   - start: the path of the first page
    ///   - site: the site that answers the web view
    public init(start: String = "/", site: NativeSite = .shared) {
        self.start = start
        self.site = site
        super.init()
    }

    /// Changes the configuration of the web view before it's made, to add the
    /// message handlers of the app for instance.
    open func configure(_ configuration: WKWebViewConfiguration) {
    }

    /// Changes the web view once it's made, before its first page loads.
    open func prepare(_ view: WKWebView) {
    }

    private func made() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(NativeSchemeHandler(site: site), forURLScheme: NativeSite.scheme)
        // the controller holds its handlers strongly, and the view holds the controller
        configuration.userContentController.add(Handler(self), name: "rife2")
        configure(configuration)

        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        #if os(macOS)
        // two fingers enlarge a page, as in a browser
        view.allowsMagnification = true
        #endif
        prepare(view)
        watched = view.observe(\.url, options: [.initial, .new]) { [weak self] view, _ in
            let path = view.url?.path ?? ""
            DispatchQueue.main.async {
                self?.path = path
            }
        }
        // on this view, the lazy one isn't there until this returns
        if let url = URL(string: site.root + start) {
            view.load(URLRequest(url: url))
        }
        return view
    }

    deinit {
        if let activated {
            NotificationCenter.default.removeObserver(activated)
        }
    }

    /// Whether an address is one of the site.
    public func own(_ url: URL) -> Bool {
        url.scheme?.lowercased() == NativeSite.scheme.lowercased() && url.host?.lowercased() == NativeSite.host.lowercased()
    }

    /// Shows the page at a path of the site.
    public func go(_ path: String) {
        guard let url = URL(string: site.root + path) else {
            return
        }
        settle()
        view.load(URLRequest(url: url))
    }

    // a page that loads by itself doesn't owe a reload anymore
    private func settle() {
        if let activated {
            NotificationCenter.default.removeObserver(activated)
            self.activated = nil
        }
        retry?.cancel()
        retry = nil
    }

    /// What a page of the site asks of the app through the "rife2" message
    /// handler; a page from elsewhere is ignored.
    open func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        let origin = message.frameInfo.securityOrigin
        guard message.name == "rife2", origin.protocol.lowercased() == NativeSite.scheme.lowercased(), origin.host.lowercased() == NativeSite.host.lowercased(),
              let said = message.body as? [String: Any], let action = said["action"] as? String else {
            return
        }
        if action == "print" {
            printPage()
        }
    }

    open func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
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
        guard activated == nil && retry == nil else {
            return
        }
        #if os(iOS)
        let active = UIApplication.didBecomeActiveNotification
        let inFront = UIApplication.shared.applicationState == .active
        #else
        let active = NSApplication.didBecomeActiveNotification
        let inFront = NSApplication.shared.isActive
        #endif
        activated = NotificationCenter.default.addObserver(forName: active, object: nil, queue: .main) { [weak self, weak webView] _ in
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
        if webView.url == nil, let url = URL(string: site.root + start) {
            webView.load(URLRequest(url: url))
        } else {
            webView.reload()
        }
    }

    open func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        if action.shouldPerformDownload {
            return .download
        }
        // A link out of the site, to a web page or a phone number for
        // instance, goes to the system: the app has no way back from it. What
        // a frame inside a page loads stays there.
        guard action.targetFrame?.isMainFrame ?? true, let url = action.request.url, let scheme = url.scheme?.lowercased(),
              !own(url), !["about", "blob", "data", "javascript"].contains(scheme) else {
            return .allow
        }
        await leave(to: url)
        return .cancel
    }

    // a link of the site that opens a new window shows its page here, there's one window
    open func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                      windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url, own(url) {
            webView.load(action.request)
        }
        return nil
    }

    open func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = saving
    }

    open func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = saving
    }

    /// Opens an address outside of the site.
    @MainActor
    open func leave(to url: URL) async {
        #if os(iOS)
        await UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }

    /// Shows the print dialog for the page.
    open func printPage() {
        #if os(iOS)
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

#if os(iOS)
// What a page saves goes to the sheet of iOS that saves and sends files.
final class Saving: NSObject, WKDownloadDelegate {
    private var saved: [ObjectIdentifier: URL] = [:]

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String) async -> URL? {
        // under the name that the page gave it, in a folder of its own: a
        // file of that name may still be on its way somewhere
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(suggestedFilename)
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
