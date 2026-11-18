/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The site of the app, compiled from Java to native code by GraalVM, which
 * answers requests without a server. It has a Java of its own inside its
 * library, apart from the one of Android that this class is in, and the
 * bridge that's linked into the library is between the two.
 * <p>Set {@link #library}, {@link #scheme}, {@link #host} and
 * {@link #properties} before the first request, in the
 * {@code onCreate} of the app's {@code Application}: a widget can make the
 * first request, before any activity.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public final class NativeSite {
    /**
     * The name of the site's native library, {@code site} for
     * {@code libsite.so}.
     */
    public static String library = "site";
    /**
     * The scheme of the address that the web view has the site at. A web
     * view of Android only keeps what a page stores for an address of the
     * web, so it's {@code https}.
     */
    public static String scheme = "https";
    /**
     * The host of the address that the web view has the site at, which
     * nothing is asked of; the package of the app under {@code .invalid}
     * when it's not set.
     */
    public static String host = null;
    /**
     * What the site is told before its first request, beside what's told
     * for every app: the platform, the directories of the app, the address
     * and the version of the app.
     */
    public static final Map<String, String> properties = new LinkedHashMap<>();

    /**
     * What the site answers a request with, all of it. The bridge tells it
     * on the thread that asks, in this order: the status and the headers
     * once, the content as the site writes it, and once how it ended.
     */
    public static final class Answer {
        private static final int COMPLETED = 0;
        private static final int FAILED = 1;

        /** The status of the answer. */
        public int status;
        /** Lines of a name, a colon and a value. */
        public String headers = "";
        /** The content of the answer, all of it. */
        public byte[] body = new byte[0];
        /** Why there's no answer, when the site or its Java failed. */
        public String failure;
        private final ByteArrayOutputStream content_ = new ByteArrayOutputStream();

        private void committed(int status, byte[] headers) {
            this.status = status;
            this.headers = new String(headers, StandardCharsets.UTF_8);
        }

        private void content(byte[] bytes) {
            content_.write(bytes, 0, bytes.length);
        }

        private void ended(int how, byte[] why) {
            body = content_.toByteArray();
            if (how != COMPLETED) {
                failure = how == FAILED && why != null ? new String(why, StandardCharsets.UTF_8) : "The site stopped answering";
            }
        }

        /** The value of a header, whatever the case of its name. */
        public String header(String name) {
            for (var line : headers.split("\n")) {
                var colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }

    private static final String LIBRARY_FAILURE = "The library didn't load: ";
    private static final AtomicLong numbers_ = new AtomicLong();
    private static boolean started_;
    private static String failure_;

    private NativeSite() {
    }

    private static native String start(byte[][] properties);

    private static native void handle(long number, byte[] url, byte[] method, byte[] headers, byte[] body, Answer answer);

    // a text as the bridge takes it: in UTF-8, with a zero at its end
    private static byte[] written(String text) {
        return (text + '\0').getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The address of the site, like {@code https://com.example.app.invalid}.
     */
    public static String root(Context context) {
        return scheme + "://" + host(context);
    }

    // a web view writes the host in lower case
    private static String host(Context context) {
        return (host != null ? host : context.getPackageName() + ".invalid").toLowerCase(Locale.ROOT);
    }

    /**
     * Whether an address is one of the site, the only ones that the app answers.
     */
    public static boolean own(Context context, Uri address) {
        return scheme.equalsIgnoreCase(address.getScheme()) && host(context).equalsIgnoreCase(address.getHost()) && address.getPort() == -1;
    }

    // Loads the library and starts its Java. Answers why that failed, or
    // nothing: an app that says what went wrong is of more use than one
    // that stops.
    private static String started(Context context) {
        try {
            System.loadLibrary(library);
            var told = new LinkedHashMap<String, String>();
            told.put("rife.nativeapp.platform", "android");
            told.put("rife.nativeapp.scheme", scheme);
            told.put("rife.nativeapp.host", host(context));
            told.put("rife.nativeapp.data", context.getFilesDir().getPath());
            // the place that Java has for files that are posted isn't one that an app of Android may write in
            told.put("rife.nativeapp.temp", context.getCacheDir().getPath());
            try {
                var app = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                told.put("rife.nativeapp.version", String.valueOf(app.versionName));
                told.put("rife.nativeapp.build", String.valueOf(app.getLongVersionCode()));
            } catch (PackageManager.NameNotFoundException e) {
                // the pages leave the version out
            }
            told.putAll(properties);
            // a web view of Android takes an answer all at once, and the
            // bridge's memory for it only lasts while it's asked, so an app
            // can't say otherwise
            told.put("rife.nativeapp.wholeAnswers", "true");
            var said = new byte[told.size() * 2][];
            var i = 0;
            for (var property : told.entrySet()) {
                said[i++] = written(property.getKey());
                said[i++] = written(property.getValue());
            }
            return start(said);
        } catch (LinkageError e) {
            return LIBRARY_FAILURE + e.getMessage();
        }
    }

    /**
     * Has the site answer a request, on the thread that asks, with its
     * headers as lines of a name, a colon and a value, and with what was
     * posted, if anything. The first request starts the site.
     */
    public static Answer ask(Context context, String url, String method, String headers, byte[] body) {
        synchronized (NativeSite.class) {
            if (!started_) {
                // whatever stops it from starting, a later request doesn't ask a site that isn't there
                failure_ = "The site didn't start";
                started_ = true;
                failure_ = started(context.getApplicationContext());
                // a library that didn't load can be asked for again, under
                // the name that the app may have set meanwhile
                if (failure_ != null && failure_.startsWith(LIBRARY_FAILURE)) {
                    started_ = false;
                }
            }
        }
        var answer = new Answer();
        if (failure_ != null) {
            answer.failure = failure_;
        } else {
            handle(numbers_.incrementAndGet(), written(url), written(method), written(headers), body, answer);
        }
        return answer;
    }

    /**
     * What the site answers at a path, all of it, for what isn't a page of
     * the web view: a widget's drawing, for instance.
     */
    public static byte[] get(Context context, String path) throws IOException {
        var answer = ask(context, root(context) + path, "GET", "", null);
        if (answer.failure != null) {
            throw new IOException(answer.failure);
        }
        if (answer.status != 200) {
            throw new IOException("The site answered " + path + " with status " + answer.status);
        }
        return answer.body;
    }
}
