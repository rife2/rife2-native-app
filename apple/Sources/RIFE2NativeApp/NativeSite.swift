/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

import CRIFE2NativeApp
import Foundation
import os

let log = Logger(subsystem: "com.uwyn.rife2.nativeapp", category: "site")

/// A request for the site. Its headers are lines of a name, a colon and a value.
public struct NativeRequest {
    /// The address, of the site.
    public var url: String
    /// The method, like `GET` or `POST`.
    public var method: String
    /// The headers, a line each.
    public var headers: String
    /// What's posted, when there's something.
    public var body: Data?

    /// A request for an address of the site.
    public init(url: String, method: String = "GET", headers: String = "", body: Data? = nil) {
        self.url = url
        self.method = method
        self.headers = headers
        self.body = body
    }
}

/// What the site says of a request while it answers it, in this order: the
/// status and the headers once, the content as it's written, and once how it
/// ended. Nothing comes after that.
public protocol NativeResponse: AnyObject {
    /// The status and the headers of the answer.
    func committed(status: Int, headers: [(name: String, value: String)])
    /// Content of the answer, as the site writes it.
    func content(_ data: Data)
    /// How the answer ended.
    func ended(_ how: NativeEnding)
}

/// How an answer of the site ended.
public enum NativeEnding {
    /// With all of the answer.
    case completed
    /// Before all of the answer was given, and why.
    case failed(String)
    /// Cancelled by the app.
    case cancelled
}

/// Why the site can't answer, like a library that didn't start.
public struct NativeSiteError: Error, CustomStringConvertible {
    /// What went wrong.
    public let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// The site of the app, compiled from Java to native code by GraalVM, which
/// answers requests without a server. The app links the native library that
/// the site was built into.
///
/// Set ``scheme``, ``host`` and ``properties`` before ``shared`` is first used.
public final class NativeSite: @unchecked Sendable {
    /// The scheme of the address that the web view has the site at.
    nonisolated(unsafe) public static var scheme = "app"
    /// The host of the address that the web view has the site at.
    nonisolated(unsafe) public static var host = "site"
    /// What the site is told before its first request, beside what's told
    /// for every app: the platform, the directory for what should last, the
    /// address and the version of the app.
    nonisolated(unsafe) public static var properties: [String: String] = [:]
    /// The arguments that the site's Java starts with, like `-Xmx64m`. An app
    /// extension, like a widget, gets a small heap unless this says otherwise:
    /// iOS stops an extension that uses more than a few tens of megabytes.
    nonisolated(unsafe) public static var arguments: [String]? = nil

    /// The site, started the first time it's used.
    public static let shared = NativeSite()

    /// The address of the site, like `app://site`.
    public let root: String
    private let java: Result<OpaquePointer, NativeSiteError>
    private let numbers = OSAllocatedUnfairLock(initialState: Int64(0))

    // an app extension runs apart from its app, inside the app's bundle
    static let isExtension = Bundle.main.bundleURL.pathExtension == "appex"

    private init() {
        root = "\(Self.scheme)://\(Self.host)"
        do {
            java = .success(try Self.started())
        } catch let error as NativeSiteError {
            java = .failure(error)
        } catch {
            java = .failure(NativeSiteError(String(describing: error)))
        }
    }

    private static func started() throws -> OpaquePointer {
        let java = try isolate(arguments: arguments ?? (isExtension ? ["-Xmx16m", "-Xmn2m"] : []))
        var abi: Int32 = 0
        try entered(java) { thread in
            abi = rife2_abi(thread)
            guard abi == 1 else {
                return
            }
            var told = [
                "rife.nativeapp.scheme": scheme,
                "rife.nativeapp.host": host,
            ]
            // the pages of an extension, like a widget's drawing, aren't in a web view
            if !isExtension {
                #if os(macOS)
                told["rife.nativeapp.platform"] = "mac"
                #else
                told["rife.nativeapp.platform"] = "ios"
                #endif
            }
            // in a file of the app that lasts and isn't the user's to see; an
            // extension keeps nothing of its own
            if !isExtension, var support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
                // outside a sandbox that's the folder of every app, the app gets one of its own in it
                if !support.path.contains("/Containers/"), let identifier = Bundle.main.bundleIdentifier {
                    support.appendPathComponent(identifier, isDirectory: true)
                }
                try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
                told["rife.nativeapp.data"] = support.path
            }
            for (name, key) in [("rife.nativeapp.version", "CFBundleShortVersionString"), ("rife.nativeapp.buildNumber", "CFBundleVersion")] {
                if let value = Bundle.main.object(forInfoDictionaryKey: key) as? String {
                    told[name] = value
                }
            }
            for (name, value) in told.merging(properties, uniquingKeysWith: { _, app in app }) {
                rife2_property(thread, name, value)
            }
        }
        guard abi == 1 else {
            throw NativeSiteError("The native library has the functions of another version of RIFE2: \(abi)")
        }
        return java
    }

    // Starts Java with the address space that an iPhone can spare first,
    // since its own choice is more than iOS may give an app.
    private static func isolate(arguments: [String]) throws -> OpaquePointer {
        // read as the arguments of a program are, after its name, and never
        // freed: once for a process
        let argv = UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: arguments.count + 1)
        for (index, argument) in (["site"] + arguments).enumerated() {
            argv[index] = strdup(argument)
        }
        var tried: [String] = []
        for megabytes in [2048, 1024, 0] {
            var parameters = graal_create_isolate_params_t()
            parameters.version = 1
            parameters.reserved_address_space_size = UInt(megabytes) * 1024 * 1024
            if !arguments.isEmpty {
                parameters.version = 4
                parameters.argc = Int32(arguments.count + 1)
                parameters.argv = argv
            }
            var isolate: OpaquePointer?
            var thread: OpaquePointer?
            let code = megabytes == 0 && arguments.isEmpty ? graal_create_isolate(nil, &isolate, &thread) : graal_create_isolate(&parameters, &isolate, &thread)
            if code == 0, let isolate {
                log.info("The site started with \(megabytes == 0 ? "its own" : "\(megabytes) MB of", privacy: .public) address space")
                if let thread {
                    graal_detach_thread(thread)
                }
                return isolate
            }
            tried.append((megabytes == 0 ? "its own size" : "\(megabytes) MB") + " gave error \(code)")
        }
        throw NativeSiteError("The site's Java didn't start: " + tried.joined(separator: ", "))
    }

    // Java only knows the threads that were attached to it, and several can be at a time.
    private static func entered(_ java: OpaquePointer, _ call: (OpaquePointer) -> Void) throws {
        // a thread that's already in Java, one that tells a response, stays attached
        if let inside = graal_get_current_thread(java) {
            call(inside)
            return
        }
        var thread: OpaquePointer?
        guard graal_attach_thread(java, &thread) == 0, let thread else {
            throw NativeSiteError("A request couldn't reach the site's Java")
        }
        call(thread)
        graal_detach_thread(thread)
    }

    private static func told(_ context: UnsafeMutableRawPointer?) -> Unmanaged<AnyObject> {
        Unmanaged<AnyObject>.fromOpaque(context!)
    }

    /// A number for a request, to cancel it by.
    public func number() -> Int64 {
        numbers.withLock {
            $0 += 1
            return $0
        }
    }

    /// Has the site answer a request on the thread that calls this.
    public func handle(_ request: NativeRequest, numbered number: Int64, telling response: NativeResponse) {
        do {
            let java = try java.get()
            try Self.entered(java) { thread in
                // held on to until the site says that the request ended, which it does once
                let context = Unmanaged.passRetained(response as AnyObject).toOpaque()
                let call = { (bytes: UnsafePointer<CChar>?, count: Int32) in
                    rife2_handle(thread, number, request.url, request.method, request.headers, bytes, count, context, { context, status, headers in
                        let lines = String(cString: headers!).split(separator: "\n")
                        let pairs = lines.compactMap { line -> (name: String, value: String)? in
                            guard let colon = line.firstIndex(of: ":") else {
                                return nil
                            }
                            return (String(line[..<colon]), line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces))
                        }
                        (NativeSite.told(context).takeUnretainedValue() as! NativeResponse).committed(status: Int(status), headers: pairs)
                    }, { context, bytes, length in
                        // the bytes are only the site's during the call
                        (NativeSite.told(context).takeUnretainedValue() as! NativeResponse).content(Data(bytes: bytes!, count: Int(length)))
                    }, { context, how, failure in
                        let ending: NativeEnding = how == 2 ? .cancelled : how == 1 ? .failed(failure.map { String(cString: $0) } ?? "") : .completed
                        (NativeSite.told(context).takeRetainedValue() as! NativeResponse).ended(ending)
                    })
                }
                if let body = request.body {
                    body.withUnsafeBytes { call($0.bindMemory(to: CChar.self).baseAddress, Int32(body.count)) }
                } else {
                    call(nil, 0)
                }
            }
        } catch {
            response.ended(.failed(String(describing: error)))
        }
    }

    /// What the site answers at a path, all of it at once, for what isn't a
    /// page of the web view: a widget's drawing, for instance.
    public func get(_ path: String) throws -> Data {
        final class Whole: NativeResponse {
            struct State {
                var status = 0
                var body = Data()
                var ending: NativeEnding?
            }

            // an event stream is told of on other threads after handle returned
            let state = OSAllocatedUnfairLock(initialState: State())

            func committed(status: Int, headers: [(name: String, value: String)]) {
                state.withLock { $0.status = status }
            }

            func content(_ data: Data) {
                state.withLock { $0.body.append(data) }
            }

            func ended(_ how: NativeEnding) {
                state.withLock { $0.ending = how }
            }
        }
        let whole = Whole()
        let number = number()
        handle(NativeRequest(url: root + path), numbered: number, telling: whole)
        if whole.state.withLock({ $0.ending == nil }) {
            cancel(number)
            throw NativeSiteError("The site answered \(path) with a response that doesn't end, like an event stream")
        }
        let state = whole.state.withLock { $0 }
        if case .failed(let why) = state.ending {
            throw NativeSiteError(why)
        }
        guard state.status == 200 else {
            throw NativeSiteError("The site answered \(path) with status \(state.status)")
        }
        return state.body
    }

    /// Tells the site that nobody waits for the response of a request anymore.
    public func cancel(_ number: Int64) {
        guard let java = try? java.get() else {
            return
        }
        try? Self.entered(java) { rife2_cancel($0, number) }
    }
}
