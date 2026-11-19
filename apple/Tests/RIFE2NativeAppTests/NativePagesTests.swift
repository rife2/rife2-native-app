/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

import Foundation
import WebKit
import XCTest
@testable import RIFE2NativeApp

final class NativePagesTests: XCTestCase {
    func testCleanedAsABrowserReadsAnAddress() {
        XCTAssertEqual(NativeSchemeHandler.cleaned("java\tscript:alert(1)"), "javascript:alert(1)")
        XCTAssertEqual(NativeSchemeHandler.cleaned("jav\r\nascript:x"), "javascript:x")
        XCTAssertEqual(NativeSchemeHandler.cleaned(" \u{1}https://example.org/ \u{0}"), "https://example.org/")
        XCTAssertEqual(NativeSchemeHandler.cleaned("/a b"), "/a b")
        // a redirect to a script that a tab hides is one to a script, which is refused
        XCTAssertEqual(URL(string: NativeSchemeHandler.cleaned("java\tscript:alert(1)"), relativeTo: URL(string: "app://site/")!)?.scheme, "javascript")
    }

    func testARedirectToTheSamePageWithAFragmentIsFollowed() {
        let page = URL(string: "app://site/list?a=1")!
        XCTAssertTrue(NativeSchemeHandler.sameDocument("app://site/list?a=1#item-5", page))
        XCTAssertTrue(NativeSchemeHandler.sameDocument("app://site/list?a=1#", page))
        XCTAssertFalse(NativeSchemeHandler.sameDocument("app://site/list?a=1", page))
        XCTAssertFalse(NativeSchemeHandler.sameDocument("app://site/list?a=2#item-5", page))
        XCTAssertFalse(NativeSchemeHandler.sameDocument("app://site/other#item-5", page))
        XCTAssertTrue(NativeSchemeHandler.sameDocument("app://site/list?a=1#x", URL(string: "app://site/list?a=1#y")!))
    }

    func testOwn() {
        XCTAssertTrue(NativeSite.own(URL(string: "app://site/x?y")!))
        XCTAssertTrue(NativeSite.own(URL(string: "APP://Site/")!))
        XCTAssertFalse(NativeSite.own(URL(string: "app://site:8080/")!))
        XCTAssertFalse(NativeSite.own(URL(string: "app://elsewhere/")!))
        XCTAssertFalse(NativeSite.own(URL(string: "https://site/")!))
    }

    func testToldTheProtocolAndWhatTheAppHears() throws {
        let script = NativePages.told(["people", "chart"], zooms: true)
        XCTAssertTrue(script.hasPrefix("window.rife2NativeAppHost = "))
        let written = try XCTUnwrap(script.dropFirst("window.rife2NativeAppHost = ".count).dropLast().data(using: .utf8))
        let told = try XCTUnwrap(JSONSerialization.jsonObject(with: written) as? [String: Any])
        XCTAssertEqual(told["protocol"] as? Int, 1)
        XCTAssertEqual(told["heard"] as? [String], ["chart", "people"])
        XCTAssertEqual(told["zooms"] as? Bool, true)
    }
}

// The pages of a site's native library in a web view, the library that
// RIFE2_NATIVE_APP_LIBRARY names, with a page that includes RIFE2's script
// at RIFE2_NATIVE_APP_PAGE, "/" when it's not set.
@MainActor
final class NativePagesOfASiteTests: XCTestCase {
    private static var library: Bool = {
        guard let path = ProcessInfo.processInfo.environment["RIFE2_NATIVE_APP_LIBRARY"], dlopen(path, RTLD_NOW | RTLD_GLOBAL) != nil else {
            return false
        }
        let data = FileManager.default.temporaryDirectory.appendingPathComponent("rife2-native-app-tests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        NativeSite.properties = ["rife.nativeapp.data": data.path]
        return true
    }()

    private static var page: String {
        ProcessInfo.processInfo.environment["RIFE2_NATIVE_APP_PAGE"] ?? "/"
    }

    private final class Listening: NativePages {
        var heard: (Any) -> Void = { _ in }

        override func configure(_ configuration: WKWebViewConfiguration) {
            listen("pong") { [weak self] data in
                self?.heard(data)
            }
        }
    }

    private func pages<P: NativePages>(_ made: () -> P) throws -> P {
        guard Self.library else {
            throw XCTSkip("RIFE2_NATIVE_APP_LIBRARY doesn't name a site's native library")
        }
        let pages = made()
        let loaded = expectation(description: "the first page loaded")
        pages.whenLoaded {
            loaded.fulfill()
        }
        _ = pages.view
        wait(for: [loaded], timeout: 30)
        return pages
    }

    private func evaluated(_ pages: NativePages, _ script: String) -> Any? {
        var result: Any?
        let done = expectation(description: "evaluated")
        pages.view.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { said in
            result = try? said.get()
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
        return result
    }

    func testAPageAndItsAppTellEachOther() throws {
        let pages = try pages { Listening(start: Self.page) }
        let native = evaluated(pages, "return [rife2NativeApp.protocol, rife2NativeApp.hears('pong'), rife2NativeApp.hears('other')]") as? [Any]
        XCTAssertEqual(native?[0] as? Int, NativePages.protocol)
        XCTAssertEqual(native?[1] as? Bool, true)
        XCTAssertEqual(native?[2] as? Bool, false)

        // the page answers what the app tells it, which the app hears
        _ = evaluated(pages, "rife2NativeApp.on('ping', data => rife2NativeApp.tell('pong', {got: data})); return true")
        let heard = expectation(description: "the app heard the page")
        pages.heard = { data in
            XCTAssertEqual((data as? [String: Any])?["got"] as? Int, 42)
            heard.fulfill()
        }
        pages.tell("ping", 42)
        wait(for: [heard], timeout: 10)
    }

    func testListeningLaterIsHeardFromTheNextPage() throws {
        let pages = try pages { NativePages(start: Self.page) }
        pages.listen("later") { _ in }
        let reloaded = expectation(description: "the page loaded again")
        pages.reload()
        pages.whenLoaded {
            reloaded.fulfill()
        }
        wait(for: [reloaded], timeout: 30)
        let hears = expectation(description: "the page hears it")
        pages.call("rife2NativeApp.hears", "later") { called, result in
            XCTAssertTrue(called)
            XCTAssertEqual(result as? Bool, true)
            hears.fulfill()
        }
        wait(for: [hears], timeout: 10)
    }

    func testCallAFunctionOfThePage() throws {
        let pages = try pages { NativePages(start: Self.page) }
        let maximum = expectation(description: "called")
        pages.call("Math.max", 3, 7, 5) { called, result in
            XCTAssertTrue(called)
            XCTAssertEqual(result as? Int, 7)
            maximum.fulfill()
        }
        let missing = expectation(description: "a function that the page doesn't have")
        pages.call("nothing.here", "x") { called, result in
            XCTAssertFalse(called)
            XCTAssertNil(result)
            missing.fulfill()
        }
        let nothing = expectation(description: "a function that returns nothing")
        pages.call("console.log", "from the test") { called, result in
            XCTAssertTrue(called)
            XCTAssertNil(result)
            nothing.fulfill()
        }
        // what's given is given as it is, not as a script to run
        let quoted = expectation(description: "a text with quotes")
        pages.call("String", "'); throw 1; ('") { called, result in
            XCTAssertEqual(result as? String, "'); throw 1; ('")
            quoted.fulfill()
        }
        wait(for: [maximum, missing, nothing, quoted], timeout: 10)
    }

    func testPress() throws {
        let pages = try pages { NativePages(start: Self.page) }
        let none = expectation(description: "nothing to press")
        pages.press("#nothing-of-the-page") { pressed in
            XCTAssertFalse(pressed)
            none.fulfill()
        }
        let pressed = expectation(description: "pressed")
        pages.view.evaluateJavaScript("const b = document.createElement('button'); b.id = 'made'; b.onclick = () => document.title = 'pressed'; document.body.append(b); true") { _, _ in
            pages.press("#made") { found in
                XCTAssertTrue(found)
                pressed.fulfill()
            }
        }
        wait(for: [none, pressed], timeout: 10)
        XCTAssertEqual(evaluated(pages, "return document.title") as? String, "pressed")
    }

    private final class Prepared: NativePages {
        var prepared = 0

        override func prepare(_ view: WKWebView) {
            prepared += 1
        }
    }

    func testOpenOnlyTheSiteAndBeforeTheViewItIsTheFirstPage() throws {
        let pages: Prepared = try pages {
            let pages = Prepared(start: "/nothing-here")
            XCTAssertTrue(pages.open(URL(string: NativeSite.shared.root + Self.page)!))
            XCTAssertFalse(pages.open(URL(string: "https://example.org/")!))
            // without making the view, which would load the page that it starts with
            XCTAssertEqual(pages.prepared, 0)
            return pages
        }
        XCTAssertEqual(pages.prepared, 1)
        XCTAssertEqual(pages.view.url?.absoluteString, NativeSite.shared.root + Self.page)
        XCTAssertFalse(pages.open(URL(string: "app://elsewhere/")!))
    }

    func testWhenLoadedRunsAfterTheLoadThatsUnderWay() throws {
        let pages = try pages { NativePages(start: Self.page) }
        var ran: [String] = []
        pages.whenLoaded {
            ran.append("now")
        }
        XCTAssertEqual(ran, ["now"])
        pages.go(Self.page + (Self.page.contains("?") ? "&" : "?") + "again")
        let loaded = expectation(description: "loaded")
        pages.whenLoaded {
            ran.append("loaded")
            XCTAssertTrue(pages.view.url?.absoluteString.hasSuffix("again") ?? false)
            loaded.fulfill()
        }
        XCTAssertEqual(ran, ["now"])
        wait(for: [loaded], timeout: 30)
        XCTAssertEqual(ran, ["now", "loaded"])
    }

    private final class ListeningInPrepare: NativePages {
        override func prepare(_ view: WKWebView) {
            listen("prepared") { _ in }
        }
    }

    func testListeningInPrepareIsHeard() throws {
        let pages = try pages { ListeningInPrepare(start: Self.page) }
        XCTAssertEqual(evaluated(pages, "return rife2NativeApp.hears('prepared')") as? Bool, true)
    }

    func testCallAFunctionThatFails() throws {
        let pages = try pages { NativePages(start: Self.page) }
        let failed = expectation(description: "a function that throws")
        pages.call("JSON.parse", "{") { called, result in
            XCTAssertTrue(called)
            XCTAssertNil(result)
            failed.fulfill()
        }
        wait(for: [failed], timeout: 10)
    }

    // The example's office syncs with a post that sends the page on to the
    // page of today, which says what it received. What waits runs there, not
    // on the page of one line that the app sends the web view on with.
    func testWhenLoadedRunsOnThePageThatARedirectLeadsTo() throws {
        guard ProcessInfo.processInfo.environment["RIFE2_NATIVE_APP_LIBRARY"]?.contains("workorders") ?? false else {
            throw XCTSkip("this goes through a page of the Work Orders example")
        }
        let pages = try pages { NativePages(start: "/") }
        let synced = expectation(description: "the page after the sync")
        // a form is submitted once what pressed it returned
        let loading = keyValueObservingExpectation(for: pages.view, keyPath: "loading", expectedValue: true)
        pages.press("form[action*=sync] button") { pressed in
            XCTAssertTrue(pressed)
        }
        wait(for: [loading], timeout: 10)
        do {
            pages.whenLoaded {
                pages.view.evaluateJavaScript("document.body.textContent.includes('received')") { received, _ in
                    XCTAssertEqual(received as? Bool, true, "\(pages.view.url?.absoluteString ?? "")")
                    synced.fulfill()
                }
            }
        }
        wait(for: [synced], timeout: 30)
    }

    // stops the load of a page that says so once it started
    private final class Stopping: NativePages {
        override func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            super.webView(webView, didStartProvisionalNavigation: navigation)
            if webView.url?.query?.contains("stopped") ?? false {
                webView.stopLoading()
            }
        }
    }

    // asks for what waits for a page once the page of one line that sends the web view on finished
    private final class AskingWhenForwarded: NativePages {
        var asked: (() -> Void)?

        override func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            super.webView(webView, didFinish: navigation)
            if webView.url?.path == "/sync", let asked {
                self.asked = nil
                asked()
            }
        }
    }

    func testWhenLoadedAfterThePageThatSendsTheWebViewOnFinished() throws {
        guard ProcessInfo.processInfo.environment["RIFE2_NATIVE_APP_LIBRARY"]?.contains("workorders") ?? false else {
            throw XCTSkip("this goes through a page of the Work Orders example")
        }
        let pages = try pages { AskingWhenForwarded(start: "/") }
        let synced = expectation(description: "the page after the sync")
        pages.asked = {
            pages.whenLoaded {
                pages.view.evaluateJavaScript("document.body.textContent.includes('received')") { received, _ in
                    XCTAssertEqual(received as? Bool, true, "\(pages.view.url?.absoluteString ?? "")")
                    synced.fulfill()
                }
            }
        }
        pages.press("form[action*=sync] button")
        wait(for: [synced], timeout: 30)
    }

    // a page that takes the query out of its own address before it finished loading
    private final class Replacing: NativePages {
        override func configure(_ configuration: WKWebViewConfiguration) {
            configuration.userContentController.addUserScript(WKUserScript(source: "if (location.search) history.replaceState(null, '', location.pathname);",
                                                                           injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
    }

    func testWhenLoadedRunsOnARedirectsPageThatChangedItsAddress() throws {
        guard ProcessInfo.processInfo.environment["RIFE2_NATIVE_APP_LIBRARY"]?.contains("workorders") ?? false else {
            throw XCTSkip("this goes through a page of the Work Orders example")
        }
        let pages = try pages { Replacing(start: "/") }
        let loading = keyValueObservingExpectation(for: pages.view, keyPath: "loading", expectedValue: true)
        pages.press("form[action*=sync] button")
        wait(for: [loading], timeout: 10)
        let synced = expectation(description: "the page after the sync")
        pages.whenLoaded {
            pages.view.evaluateJavaScript("document.body.textContent.includes('received')") { received, _ in
                XCTAssertEqual(received as? Bool, true, "\(pages.view.url?.absoluteString ?? "")")
                synced.fulfill()
            }
        }
        wait(for: [synced], timeout: 30)
    }

    func testWhenLoadedDoesntRunForALoadThatWasStopped() throws {
        let pages = try pages { Stopping(start: Self.page) }
        var stopped = false
        pages.go(Self.page + (Self.page.contains("?") ? "&" : "?") + "stopped")
        pages.whenLoaded {
            stopped = true
        }
        // a while later, a load of its own
        let later = expectation(description: "a later page loaded")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            pages.go(Self.page)
            pages.whenLoaded {
                later.fulfill()
            }
        }
        wait(for: [later], timeout: 30)
        XCTAssertFalse(stopped)
    }
}
