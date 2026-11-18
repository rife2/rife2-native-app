/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.android;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Answers what a web view asks of the site with what the site says, on the
 * thread of the web view that asks. A web view of Android leaves two things
 * to the app that answers it: what a page posts, which it doesn't hand over,
 * and a redirect, which it can't be answered with. The script of
 * the pages hands over what a page posts first, see
 * {@link #posted(String, byte[])}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeRequests {
    /** How a request names what it posts: a header of what a script asks, a parameter of a form. */
    static final String POSTED_HEADER = "X-Rife2-Body";
    static final String POSTED_PARAMETER = "rife2-body";
    // how many posts are kept for a request that names them: one that never comes mustn't stay forever
    private static final int POSTS_KEPT = 16;
    // how far the app follows what isn't a page when the site sends it on
    private static final int REDIRECTS = 5;

    private record Posted(String type, byte[] body) {
    }

    private final Context context_;
    private final Map<String, Posted> posted_ = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Posted> eldest) {
            return size() > POSTS_KEPT;
        }
    });

    /**
     * The requests of the pages that an app's context shows.
     *
     * @param context the context of the app
     */
    public NativeRequests(Context context) {
        context_ = context.getApplicationContext();
    }

    /**
     * Takes what a page is about to post and answers what the request has
     * to name it with.
     */
    public String posted(String type, byte[] body) {
        var named = UUID.randomUUID().toString();
        posted_.put(named, new Posted(type, body));
        return named;
    }

    private static String header(Map<String, String> headers, String name) {
        for (var header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /**
     * Called with what a page posted, before it goes to the site, for a
     * check that looks at what a web view hands over.
     */
    protected void handed(String method, String address, String type, byte[] body) {
    }

    // where the site sends a request on to, when that's an address of its own
    private String sentOn(String from, NativeSite.Answer answer) {
        var said = answer.failure == null && answer.status >= 300 && answer.status < 400 ? answer.header("Location") : null;
        if (null == said) {
            return null;
        }
        try {
            var location = new java.net.URL(new java.net.URL(from), said).toString();
            return NativeSite.own(context_, Uri.parse(location)) ? location : null;
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    /**
     * The site's answer to a request of the web view.
     */
    public WebResourceResponse answered(WebResourceRequest request) {
        var url = request.getUrl();
        var headers = new LinkedHashMap<>(request.getRequestHeaders());
        var named = header(headers, POSTED_HEADER);
        headers.keySet().removeIf(name -> name.equalsIgnoreCase(POSTED_HEADER));
        var address = url.toString();
        if (null == named && url.getQueryParameter(POSTED_PARAMETER) != null) {
            named = url.getQueryParameter(POSTED_PARAMETER);
            // without what names it, and otherwise as it was written
            var query = new StringBuilder();
            for (var parameter : url.getEncodedQuery().split("&")) {
                if (!parameter.startsWith(POSTED_PARAMETER + "=")) {
                    query.append(query.length() == 0 ? "" : "&").append(parameter);
                }
            }
            address = url.buildUpon().encodedQuery(query.length() == 0 ? null : query.toString()).build().toString();
        }
        var posted = null == named ? null : posted_.remove(named);
        if (named != null && null == posted) {
            Log.w("rife2", "What " + address + " posts was handed over too long ago, it goes without it");
        }
        if (posted != null && !posted.type().isEmpty()) {
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("Content-Type"));
            headers.put("Content-Type", posted.type());
        }
        var body = null == posted ? null : posted.body();
        if (body != null) {
            handed(request.getMethod(), address, header(headers, "Content-Type"), body);
        }
        var lines = new StringBuilder();
        for (var header : headers.entrySet()) {
            lines.append(oneLine(header.getKey())).append(": ").append(oneLine(header.getValue())).append('\n');
        }
        var page = request.isForMainFrame();
        var answer = NativeSite.ask(context_, address, request.getMethod(), lines.toString(), body);
        // a page that's sent on is told so, see below; anything else, a file
        // or what a script asks for, is followed here
        // a redirect is followed with a GET, which has no body for these headers to describe
        var followedLines = new StringBuilder();
        for (var header : headers.entrySet()) {
            if (!header.getKey().equalsIgnoreCase("Content-Type") && !header.getKey().equalsIgnoreCase("Content-Length")) {
                followedLines.append(oneLine(header.getKey())).append(": ").append(oneLine(header.getValue())).append('\n');
            }
        }
        var from = address;
        for (var followed = 0; !page && followed < REDIRECTS && sentOn(from, answer) != null; ++followed) {
            from = sentOn(from, answer);
            answer = NativeSite.ask(context_, from, "GET", followedLines.toString(), null);
        }
        return response(answer, page);
    }

    // a line break, in what a page says it posts for instance, would tell the site another header
    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ");
    }

    private static WebResourceResponse response(NativeSite.Answer answer, boolean page) {
        if (answer.failure != null) {
            Log.w("rife2", answer.failure);
            // a page that isn't there would leave the screen empty, so it says why instead
            var said = "<!DOCTYPE html><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<body style=\"font: 17px sans-serif; padding: 24px; line-height: 1.5; overflow-wrap: anywhere\"><h1 style=\"font-size: 22px\">This page couldn't be shown</h1><p>"
                + answer.failure.replace("&", "&amp;").replace("<", "&lt;") + "</p>";
            // what a script asks for fails as such
            return new WebResourceResponse("text/html", "UTF-8", page ? 200 : 500, page ? "OK" : "Internal Server Error",
                java.util.Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(said.getBytes(StandardCharsets.UTF_8)));
        }
        var headers = new LinkedHashMap<String, String>();
        for (var line : answer.headers.split("\n")) {
            var colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        var status = answer.status;
        var body = answer.body;
        var location = header(headers, "Location");
        // A web view can't be answered with a redirect. A page is sent on by
        // a page of one line, so that its address is the one of what it
        // shows: followed here, the address would stay and the relative links
        // of the page would be wrong.
        if (page && status >= 300 && status < 400 && location != null) {
            // a browser refuses to be sent to a script or to data, which would run as the site
            var scheme = Uri.parse(location).getScheme();
            if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return new WebResourceResponse("text/plain", "UTF-8", 502, "Bad Gateway", Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
            }
            body = ("<!DOCTYPE html><script>location.replace(" + JSONObject.quote(location) + ")</script>").getBytes(StandardCharsets.UTF_8);
            headers.clear();
            headers.put("Content-Type", "text/html; charset=UTF-8");
            status = 200;
        }
        var type = header(headers, "Content-Type");
        var mime = null == type ? "application/octet-stream" : type.split(";")[0].trim();
        String charset = null;
        if (type != null) {
            for (var parameter : type.split(";")) {
                var pair = parameter.trim();
                if (pair.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) {
                    charset = pair.substring(8).trim().replace("\"", "");
                }
            }
        }
        headers.putIfAbsent("Cache-Control", "no-store");
        // a web view refuses a response with another status than these
        var allowed = status >= 100 && status <= 599 && (status < 300 || status >= 400);
        return new WebResourceResponse(mime, charset, allowed ? status : 200, "OK", headers, new ByteArrayInputStream(body));
    }
}
