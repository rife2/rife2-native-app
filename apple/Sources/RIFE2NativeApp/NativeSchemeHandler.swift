/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

import Foundation
import os
import WebKit
#if os(iOS) || os(visionOS)
import UIKit
#endif

/// Serves the address of the site to a web view, its pages and its files
/// alike, from the site in the app.
public final class NativeSchemeHandler: NSObject, WKURLSchemeHandler {
    // the requests that the web view still waits for, only touched on the main thread
    private var waiting: [ObjectIdentifier: Reply] = [:]
    private let site: NativeSite
    private static let answering = DispatchQueue(label: "rife2.nativeapp.answering", qos: .userInitiated, attributes: .concurrent)
    private static let replies = OSAllocatedUnfairLock(initialState: 0)

    /// How many requests of web views are held on to right now, from when a
    /// request is taken until it was answered and let go, for a check that
    /// asks for many at once and then reads that none stayed.
    public static var held: Int {
        replies.withLock { $0 }
    }

    /// A handler that serves a site.
    public init(site: NativeSite = .shared) {
        self.site = site
    }

    public func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        let request = task.request
        guard let url = request.url else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        let headers = (request.allHTTPHeaderFields ?? [:]).map { "\(Self.oneLine($0)): \(Self.oneLine($1))\n" }.joined()
        // a web view hands over what a page posts as a stream
        let body = request.httpBody ?? request.httpBodyStream.map { stream in
            var read = Data()
            var buffer = [UInt8](repeating: 0, count: 16384)
            stream.open()
            while case let count = stream.read(&buffer, maxLength: buffer.count), count > 0 {
                read.append(buffer, count: count)
            }
            stream.close()
            return read
        }
        answer(NativeRequest(url: url.absoluteString, method: request.httpMethod ?? "GET", headers: headers, body: body), with: Reply(to: task, from: self))
    }

    public func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {
        guard let reply = waiting.removeValue(forKey: ObjectIdentifier(task)) else {
            return
        }
        reply.stopped.withLock { $0 = true }
        let site = site
        Self.answering.async {
            site.cancel(reply.number)
        }
    }

    private func answer(_ request: NativeRequest, with reply: Reply) {
        waiting[ObjectIdentifier(reply.task)] = reply
        let site = site
        Self.answering.async {
            // the site can't be told to cancel what it wasn't asked yet
            if reply.stopped.withLock({ $0 }) {
                return
            }
            site.handle(request, numbered: reply.number, telling: reply)
            // a stop that came while the site took the request, which an event stream would outlive
            if reply.stopped.withLock({ $0 }) {
                site.cancel(reply.number)
            }
            guard let location = reply.followed.withLock({ $0 }), let followed = URL(string: location) else {
                return
            }
            DispatchQueue.main.async {
                if self.waiting[ObjectIdentifier(reply.task)] === reply {
                    self.answer(NativeRequest(url: location, headers: Self.withoutBody(request.headers)),
                                with: Reply(to: reply.task, from: self, redirects: reply.redirects + 1, at: followed))
                }
            }
        }
    }

    // a line break would tell the site another header
    static func oneLine(_ text: String) -> String {
        text.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " ")
    }

    // a redirect is followed with a GET, which has no body that these would describe
    static func withoutBody(_ headers: String) -> String {
        headers.split(separator: "\n").filter { line in
            let name = line.split(separator: ":", maxSplits: 1).first?.trimmingCharacters(in: .whitespaces).lowercased()
            return name != "content-type" && name != "content-length"
        }.map { "\($0)\n" }.joined()
    }

    // What the web view is told of one request, on the main thread and only
    // while it still waits for it: a task that was stopped raises an
    // exception when it's told anything.
    private final class Reply: NativeResponse {
        let task: any WKURLSchemeTask
        let number: Int64
        let redirects: Int
        // set on the main thread when the web view stops waiting, read where requests are answered
        let stopped = OSAllocatedUnfairLock(initialState: false)
        // where the site sends a request that isn't for a page, once it
        // ended; an event stream is told of on other threads
        let followed = OSAllocatedUnfairLock<String?>(initialState: nil)
        private weak var handler: NativeSchemeHandler?
        private let url: URL
        // what a relative address that the site sends a request to is relative to
        private let base: URL
        private let root: String
        // what the web view shows as a page, rather than what a page loads or asks for
        private let page: Bool
        private let began = Date()
        private var status = 0
        private var size = 0
        private var forward: Data?

        init(to task: any WKURLSchemeTask, from handler: NativeSchemeHandler, redirects: Int = 0, at followed: URL? = nil) {
            self.task = task
            self.handler = handler
            self.redirects = redirects
            number = handler.site.number()
            root = handler.site.root
            url = task.request.url!
            base = followed ?? url
            // what a script of the page asks for at the page's own address isn't the page
            page = task.request.mainDocumentURL == url && (task.request.value(forHTTPHeaderField: "Accept") ?? "").hasPrefix("text/html")
            NativeSchemeHandler.replies.withLock { $0 += 1 }
        }

        deinit {
            NativeSchemeHandler.replies.withLock { $0 -= 1 }
        }

        private func tell(_ step: @escaping (any WKURLSchemeTask) -> Void) {
            DispatchQueue.main.async { [self] in
                if handler?.waiting[ObjectIdentifier(task)] === self {
                    step(task)
                }
            }
        }

        private func response(_ status: Int, _ headers: [String: String]) -> HTTPURLResponse {
            HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers.merging(["Cache-Control": "no-store"]) { said, _ in said })!
        }

        private func respond(_ status: Int, _ headers: [String: String]) {
            self.status = status
            let response = response(status, headers)
            tell { $0.didReceive(response) }
        }

        func committed(status: Int, headers: [(name: String, value: String)]) {
            // A web view can't be answered with a redirect. A page is sent on
            // by a page of one line, so that its address is the one of what it
            // shows: followed here, the address would stay and the relative
            // links of the page would be wrong. Anything else, a file or what a
            // script asks for, is followed here.
            if (300...399).contains(status),
               let said = headers.first(where: { $0.name.caseInsensitiveCompare("Location") == .orderedSame })?.value,
               let location = URL(string: said, relativeTo: base)?.absoluteString {
                let own = location.lowercased().hasPrefix(root.lowercased() + "/") || location.lowercased() == root.lowercased()
                // a browser refuses to be sent to a script or to data, which would run as the site
                guard own || ["http", "https"].contains(URL(string: location)?.scheme?.lowercased() ?? "") else {
                    respond(502, [:])
                    return
                }
                // a page sent elsewhere goes there through the web view, which leaves the app for it
                if page, let quoted = try? JSONSerialization.data(withJSONObject: location, options: .fragmentsAllowed) {
                    forward = Data("<!DOCTYPE html><script>location.replace(".utf8) + quoted + Data(")</script>".utf8)
                    respond(200, ["Content-Type": "text/html; charset=UTF-8"])
                    return
                }
                if own && redirects < 5 {
                    followed.withLock { $0 = location }
                    return
                }
            }
            respond(status, Dictionary(headers.map { ($0.name, $0.value) }) { _, last in last })
        }

        func content(_ data: Data) {
            size += data.count
            if forward == nil && followed.withLock({ $0 }) == nil {
                tell { $0.didReceive(data) }
            }
        }

        func ended(_ how: NativeEnding) {
            let took = Int(Date().timeIntervalSince(began) * 1000)
            switch how {
            case .completed:
                log.debug("\(self.status) \(self.url.absoluteString) \(self.size) bytes in \(took) ms")
                if followed.withLock({ $0 }) != nil {
                    return
                }
            case .failed(let why):
                log.error("\(self.url.absoluteString) failed after \(took) ms: \(why, privacy: .public)")
            case .cancelled:
                break
            }
            followed.withLock { $0 = nil }
            let unanswered = status == 0
            tell { [self] task in
                handler?.waiting[ObjectIdentifier(task)] = nil
                switch how {
                case .completed:
                    if let forward {
                        task.didReceive(forward)
                    }
                    task.didFinish()
                // a page that isn't there would leave the screen empty, so it says why instead
                case .failed(let why) where page && unanswered:
                    task.didReceive(response(200, ["Content-Type": "text/html; charset=UTF-8"]))
                    task.didReceive(Data(Self.explained(why).utf8))
                    task.didFinish()
                case .failed(let why):
                    task.didFailWithError(NSError(domain: "rife2.nativeapp", code: 0, userInfo: [NSLocalizedDescriptionKey: why]))
                // the web view still waits, so it wasn't the one that stopped it
                case .cancelled:
                    task.didFailWithError(URLError(.cancelled))
                }
            }
        }

        private static func explained(_ why: String) -> String {
            #if os(macOS)
            let device = "Mac, macOS \(ProcessInfo.processInfo.operatingSystemVersionString)"
            #else
            let device = "\(UIDevice.current.model), \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
            #endif
            let said = why.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            return """
                <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="font: 17px -apple-system; padding: 24px; line-height: 1.5; overflow-wrap: anywhere">
                <h1 style="font-size: 22px">This page couldn't be shown</h1>
                <p>\(said)</p>
                <p style="color: gray">\(device)</p>
                </body></html>
                """
        }
    }
}
