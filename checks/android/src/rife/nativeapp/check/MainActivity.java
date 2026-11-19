/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.check;

import android.util.Log;
import android.webkit.WebView;
import rife.nativeapp.android.NativeActivity;
import rife.nativeapp.android.NativePages;

// The site of a native library in the web view of rife2-native-app, as an app
// would show it, under the name that the manifest's rife2.library says. Once
// the first page is there, it has the page tell the app what the app asks it
// to, which shows in the log as "rife2-check" for check.sh's log steps.
public class MainActivity extends NativeActivity {
    @Override
    protected NativePages pages() {
        var start = getIntent().getStringExtra("start");
        var pages = new NativePages(this, start == null ? "/" : start) {
            @Override
            protected void configure(WebView view) {
                listen("rife2-check", data -> Log.i("rife2-check", "heard " + data));
            }

            // what waits for each page runs on that page, not on one that sends the web view on
            @Override
            protected void starting(WebView view, String url) {
                whenLoaded(() -> Log.i("rife2-check", "waited for " + android.net.Uri.parse(view.getUrl()).getPath()));
            }
        };
        pages.whenLoaded(() -> pages.call("rife2NativeApp.hears", new Object[] {"rife2-check"}, (called, hears) -> {
            Log.i("rife2-check", "hears " + hears);
            pages.call("rife2NativeApp.tell", new Object[] {"rife2-check", "ping"}, (told, result) -> Log.i("rife2-check", "told " + told + " " + result));
            pages.call("nothing.here", new Object[0], (missing, result) -> Log.i("rife2-check", "missing " + missing));
        }));
        return pages;
    }
}
