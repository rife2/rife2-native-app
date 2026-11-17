/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileUtils;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The pages of the site in a web view, and what they need of Android that a
 * web view doesn't do by itself: links that lead out of the site, sharing,
 * files that a page saves or asks for, printing, what a page posts, and a
 * page that's shown again when Android stopped what draws it.
 * <p>An activity calls {@link #start}, puts {@link #shown} on the screen and hands on
 * {@code onActivityResult}, {@code onSaveInstanceState}, the back button and
 * {@code onDestroy}. It adds what's its own by subclassing this, for
 * instance a JavaScript interface of its own in {@link #configure(WebView)}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativePages {
    /** The request codes of the activity results that {@link #chosen} takes. */
    public static final int SAVED = 0x5201;
    public static final int CHOSEN = 0x5202;
    private static final Set<String> INSIDE = Set.of("about", "blob", "data", "javascript");
    // what another app can't be handed, or what would let a page start anything
    private static final Set<String> KEPT_IN = Set.of("file", "content", "intent");
    private static final String NOT_STARTED = "the pages didn't start yet, see start()";
    // files are written away from the thread that the screen is drawn on
    private static final Executor WRITING = Executors.newSingleThreadExecutor();

    /** What holds the web view, which is another one after Android stopped what draws the pages. */
    public final FrameLayout shown;
    protected final Activity activity;
    private NativeRequests requests_;
    private WebView view_;
    // the address of the page that's shown, to go back to when Android stops what draws it
    private String at_;
    private ValueCallback<Uri[]> choosing_;

    /**
     * @param activity the activity that shows the pages
     * @param start    the path of the first page
     */
    public NativePages(Activity activity, String start) {
        this.activity = activity;
        shown = new FrameLayout(activity);
        at_ = NativeSite.root(activity) + start;
    }

    /**
     * Makes the web view and shows the first page, or the pages that were
     * shown before Android took the activity away.
     *
     * @param state what {@link #keep} kept of the pages; or {@code null}
     * @return these pages
     */
    public NativePages start(Bundle state) {
        if (view_ != null) throw new IllegalStateException("the pages already started");
        requests_ = requests();
        view_ = made();
        if (null == state || null == view_.restoreState(state)) {
            view_.loadUrl(at_);
        }
        return this;
    }

    /**
     * Changes a web view before its first page, to add a JavaScript
     * interface of the app for instance. It's called again for the web view
     * that takes the place of one whose pages Android stopped drawing.
     */
    protected void configure(WebView view) {
    }

    /**
     * Makes what answers the requests of the web view, once, in
     * {@link #start}.
     */
    protected NativeRequests requests() {
        return new NativeRequests(activity);
    }

    /**
     * Called when a page starts loading.
     */
    protected void starting(WebView view, String url) {
    }

    /**
     * Called when a page finished loading.
     */
    protected void loaded(WebView view, String url) {
    }

    /** The web view that's shown now. */
    public WebView view() {
        if (null == view_) throw new IllegalStateException(NOT_STARTED);
        return view_;
    }

    private WebView made() {
        var view = new WebView(activity);
        var settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        // what the pages keep in their storage
        settings.setDomStorageEnabled(true);
        // a page keeps its size, as a screen of an app does
        settings.setSupportZoom(false);
        // Nothing but the site loads in the web view, since the pages reach
        // the app through their interface, and so would a page of elsewhere.
        // An app whose pages show what's on the web allows it in configure().
        settings.setBlockNetworkLoads(true);
        view.addJavascriptInterface(new Asked(), "Rife2NativeApp");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return NativeSite.own(activity, request.getUrl()) ? requests_.answered(request) : null;
            }

            // A link out of the site, to a web page or a phone number for
            // instance, goes to Android: the app has no way back from it.
            // Only the site's own pages are in the web view, so it was one
            // of those, also when the site sent a page on to another place.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                var address = request.getUrl();
                var scheme = String.valueOf(address.getScheme()).toLowerCase(java.util.Locale.ROOT);
                if (NativeSite.own(activity, address) || INSIDE.contains(scheme)) {
                    return false;
                }
                if (!KEPT_IN.contains(scheme)) {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, address));
                    } catch (RuntimeException e) {
                        Log.w("rife2", "Nothing on this device opens " + address, e);
                    }
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
                starting(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded(view, url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (url != null && NativeSite.own(activity, Uri.parse(url))) {
                    at_ = url;
                }
            }

            // Android stops what draws the pages when it needs the memory, or
            // that fails by itself. A web view is of no use after that, and an
            // app that keeps it is stopped too, so another one takes its place
            // at the same page, without the pages before it.
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w("rife2", "What draws the pages " + (detail.didCrash() ? "failed" : "was stopped") + ", the page is loaded again");
                shown.removeView(view);
                view.destroy();
                if (view == view_) {
                    view_ = made();
                    view_.loadUrl(at_);
                }
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            // a page that asks for a file
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> chosen, FileChooserParams asked) {
                if (choosing_ != null) {
                    choosing_.onReceiveValue(null);
                }
                try {
                    activity.startActivityForResult(asked.createIntent(), CHOSEN);
                    choosing_ = chosen;
                } catch (ActivityNotFoundException e) {
                    choosing_ = null;
                    chosen.onReceiveValue(null);
                }
                return true;
            }
        });
        // a link to a file of the site that the web view follows by itself
        view.setDownloadListener((url, agent, disposition, type, length) -> {
            if (NativeSite.own(activity, Uri.parse(url))) {
                WRITING.execute(() -> {
                    var answer = NativeSite.ask(activity, url, "GET", "", null);
                    if (null == answer.failure && answer.status == 200) {
                        offer(URLUtil.guessFileName(url, disposition, type), type, answer.body);
                    }
                });
            }
        });
        configure(view);
        shown.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return view;
    }

    /** Shows the page at a path of the site. */
    public void go(String path) {
        view().loadUrl(NativeSite.root(activity) + path);
    }

    /** Shows the page at an address of the site; one of elsewhere isn't shown. */
    public void open(String address) {
        if (NativeSite.own(activity, Uri.parse(address))) {
            view().loadUrl(address);
        }
    }

    /** Goes back a page, when there's one to go back to. */
    public boolean back() {
        if (view().canGoBack()) {
            view_.goBack();
            return true;
        }
        return false;
    }

    /** Keeps where the pages are for when Android takes the activity away and brings it back. */
    public void keep(Bundle state) {
        view().saveState(state);
    }

    /** Lets go of the web view, whose page would otherwise go on behind an activity that's gone. */
    public void gone() {
        shown.removeAllViews();
        if (view_ != null) {
            view_.destroy();
        }
    }

    // what a page asks of the app through the script of rife2-native-app, on a thread of the web view
    private final class Asked {
        @JavascriptInterface
        public void print() {
            activity.runOnUiThread(() -> {
                var title = null == view_.getTitle() ? "" : view_.getTitle();
                ((PrintManager) activity.getSystemService(Context.PRINT_SERVICE)).print(title, view_.createPrintDocumentAdapter(title), new PrintAttributes.Builder().build());
            });
        }

        @JavascriptInterface
        public void share(String title, String url) {
            activity.runOnUiThread(() -> activity.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, url), null)));
        }

        @JavascriptInterface
        public void save(String name, String type, String encoded) {
            offer(name, type, Base64.decode(encoded, Base64.DEFAULT));
        }

        @JavascriptInterface
        public String posted(String type, String encoded) {
            return requests_.posted(type, Base64.decode(encoded, Base64.DEFAULT));
        }
    }

    // What a page saves is kept as a file where the person chooses, or goes
    // to another app. It's a file of the app first, the one that another app
    // is handed and that's still there when Android took the activity away
    // meanwhile.
    private void offer(String name, String type, byte[] content) {
        var kind = null == type || type.isEmpty() ? "application/octet-stream" : type.split(";")[0].trim();
        WRITING.execute(() -> {
            try {
                var file = NativeShared.keep(activity, name, content);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    new AlertDialog.Builder(activity).setTitle(file.getName()).setItems(new String[]{"Save", "Share"}, (dialog, chosen) -> {
                        if (chosen == 0) {
                            activity.startActivityForResult(
                                new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind).putExtra(Intent.EXTRA_TITLE, file.getName()), SAVED);
                        } else {
                            var address = NativeShared.address(activity, file.getName());
                            var send = new Intent(Intent.ACTION_SEND).setType(kind).putExtra(Intent.EXTRA_STREAM, address).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            send.setClipData(ClipData.newRawUri(file.getName(), address));
                            activity.startActivity(Intent.createChooser(send, null));
                        }
                    }).show();
                });
            } catch (IOException | RuntimeException e) {
                // a page that gave no name, for instance
                failed("The file couldn't be saved", e);
            }
        });
    }

    private void failed(String what, Exception why) {
        Log.e("rife2", what, why);
        activity.runOnUiThread(() -> Toast.makeText(activity, what, Toast.LENGTH_LONG).show());
    }

    /**
     * What came of asking Android for a file or for a place to save one,
     * from the activity's {@code onActivityResult}.
     *
     * @return whether it was one of these pages
     */
    public boolean chosen(int request, int result, Intent data) {
        if (request == CHOSEN && choosing_ != null) {
            choosing_.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            choosing_ = null;
            return true;
        }
        if (request == SAVED) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                var saved = NativeShared.kept(activity);
                WRITING.execute(() -> {
                    try (var from = new FileInputStream(saved); var to = activity.getContentResolver().openOutputStream(data.getData())) {
                        if (null == to) {
                            throw new IOException("Nothing to write " + data.getData() + " with");
                        }
                        FileUtils.copy(from, to);
                    } catch (IOException | RuntimeException e) {
                        failed("The file couldn't be saved", e);
                    }
                });
            }
            return true;
        }
        return false;
    }
}
