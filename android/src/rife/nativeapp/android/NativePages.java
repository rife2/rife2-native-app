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

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The pages of the site in a web view, and what they need of Android that a
 * web view doesn't do by itself: links that lead out of the site, sharing,
 * files that a page saves or asks for, printing, what a page posts, and a
 * page that's shown again when Android stopped what draws it.
 * <p>An activity calls {@link #start}, puts {@link #shown} on the screen and hands on
 * {@code onActivityResult}, {@code onSaveInstanceState}, {@code onResume}, the back
 * button and {@code onDestroy}, which {@link NativeActivity} does.
 * <p>A page and the app tell each other what's their own through RIFE2's
 * script of the pages: the app {@link #listen}s for what a page tells it with
 * {@code rife2NativeApp.tell(name, data)}, and {@link #tell}s a page what it
 * hears with {@code rife2NativeApp.on(name, heard)}. An app adds more of its
 * own by subclassing this.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativePages {
    /**
     * What a page and its app tell each other about, which a page reads as
     * {@code rife2NativeApp.protocol}: 1 has {@link #listen} and {@link #tell}.
     */
    public static final int PROTOCOL = 1;
    /** The request code of the activity result of a file that a page saved, which {@link #chosen} takes. */
    public static final int SAVED = 0x5201;
    /** The request code of the activity result of files that a page asked for, which {@link #chosen} takes. */
    public static final int CHOSEN = 0x5202;
    private static final Set<String> INSIDE = Set.of("about", "blob", "data", "javascript");
    // what another app can't be handed, or what would let a page start anything
    private static final Set<String> KEPT_IN = Set.of("file", "content", "intent");
    private static final String NOT_STARTED = "the pages didn't start yet, see start()";
    // files are written away from the thread that the screen is drawn on
    private static final Executor WRITING = Executors.newSingleThreadExecutor();

    /** What holds the web view, which is another one after Android stopped what draws the pages. */
    public final FrameLayout shown;
    /** The activity that shows the pages. */
    protected final Activity activity;
    private NativeRequests requests_;
    private WebView view_;
    // the address of the page that's shown, to go back to when Android stops what draws it
    private String at_;
    private ValueCallback<Uri[]> choosing_;
    private final Map<String, Consumer<Object>> listened_ = new TreeMap<>();
    private ScriptHandler told_;
    // what the pages are told when a script can't be added at their start
    private volatile String host_ = "{}";
    // what runs once the page that's loading is there, and whether one is loading
    private List<Runnable> waiting_ = new ArrayList<>();
    private boolean loading_;
    private Consumer<Boolean> backChanged_ = back -> {};
    // the address of a link out of the site that was just refused, whose load Android says finished
    private String refused_;
    // when a web view last took the place of one whose pages stopped being drawn, and how long it waited
    private long replaced_ = -1_000_000;
    private long waited_ = 0;

    /**
     * @param activity the activity that shows the pages
     * @param start    the path of the first page, which {@link #open} or
     *                 {@link #go} before {@link #start} replace
     */
    public NativePages(Activity activity, String start) {
        this.activity = activity;
        shown = new FrameLayout(activity);
        // without the slash, the path would be part of the host
        at_ = NativeSite.root(activity) + (start.startsWith("/") ? start : "/" + start);
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
        requests_.saving(this::offer);
        view_ = made();
        if (null == state || null == view_.restoreState(state)) {
            load(view_, at_);
        } else {
            // the page that it restored loads like any other
            loading_ = true;
        }
        return this;
    }

    private void load(WebView view, String address) {
        loading_ = true;
        view.loadUrl(address);
    }

    /**
     * Changes a web view before its first page, to {@link #listen} for what
     * the pages tell the app for instance. It's called again for the web view
     * that takes the place of one whose pages Android stopped drawing.
     */
    protected void configure(WebView view) {
    }

    /**
     * Whether a page can be enlarged with two fingers, which a screen of an
     * app usually can't; {@code false} unless a subclass says otherwise.
     */
    protected boolean zooms() {
        return false;
    }

    /**
     * Whether a link of a page to an address out of the site is opened by
     * another app, as a browser opens a link: a web page, a phone number or
     * a mail address for instance. It's never one of a file, a content
     * provider or an intent, which another app can't be handed or which would
     * let a page start anything; nothing opens one that this refuses.
     */
    protected boolean leaves(Uri address) {
        return true;
    }

    /**
     * Called each time the activity is in front again, from
     * {@link #resumed}.
     */
    protected void activated() {
    }

    /** From the activity's {@code onResume}, see {@link #activated}. */
    public void resumed() {
        activated();
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
        // a page keeps its size, as a screen of an app does, unless the app says otherwise
        settings.setSupportZoom(zooms());
        settings.setBuiltInZoomControls(zooms());
        settings.setDisplayZoomControls(false);
        // Nothing but the site loads in the web view. An app whose pages show
        // what's on the web allows it in configure().
        settings.setBlockNetworkLoads(true);
        // only the site's own frames get the object that reaches the app, not
        // one of elsewhere or a sandboxed one, and the app checks who asks
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "Rife2NativeApp", Set.of(origin()), this::asked);
        } else {
            Log.e("rife2", "This web view has no WebViewFeature.WEB_MESSAGE_LISTENER, so pages can't print, share, save, or post what they post");
        }
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
                // what a frame inside a page loads stays there
                if (!request.isForMainFrame() || NativeSite.own(activity, address) || INSIDE.contains(scheme)) {
                    return false;
                }
                // A page that leaves the site doesn't load here, and what waits for it
                // doesn't run, unless the page that it leaves is still loading itself.
                if (view == view_) {
                    refused_ = address.toString();
                    shown.post(() -> {
                        if (view == view_ && loading_ && view.getProgress() == 100) {
                            loading_ = false;
                            waiting_.clear();
                        }
                    });
                }
                if (!KEPT_IN.contains(scheme) && leaves(address)) {
                    try {
                        // only what's meant to be opened from a link, as a browser does
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, address).addCategory(Intent.CATEGORY_BROWSABLE));
                    } catch (RuntimeException e) {
                        Log.w("rife2", "Nothing on this device opens " + address, e);
                    }
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (view == view_) {
                    loading_ = true;
                }
                starting(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Not of a load that was refused, which Android says finished too. A
                // page that sends the web view on isn't the one that loads, the one
                // that it leads to is, and one that says why a page couldn't be shown
                // is one that didn't load.
                if (url != null && url.equals(refused_)) {
                    refused_ = null;
                    // a page that the site sent out of the site ended there, unless another one still loads
                    if (view == view_ && loading_ && view.getProgress() == 100) {
                        loading_ = false;
                        waiting_.clear();
                    }
                } else if (view == view_ && view.getProgress() == 100) {
                    var own = requests_.answeredItself(url);
                    if (Boolean.FALSE.equals(own)) {
                        loading_ = false;
                        waiting_.clear();
                    } else if (!Boolean.TRUE.equals(own)) {
                        finished();
                    }
                }
                loaded(view, url);
            }

            // a page that doesn't load doesn't have what waits for it
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (view == view_ && request.isForMainFrame()) {
                    waiting_.clear();
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (url != null && NativeSite.own(activity, Uri.parse(url))) {
                    at_ = url;
                }
                if (view == view_) {
                    backChanged_.accept(view.canGoBack());
                    // a step in the history of the same page, which a page that pushed one has, loads nothing
                    shown.post(() -> {
                        if (view == view_ && loading_ && view.getProgress() == 100) {
                            finished();
                        }
                    });
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
                    // what belonged to the web view that's gone
                    told_ = null;
                    if (choosing_ != null) {
                        choosing_.onReceiveValue(null);
                        choosing_ = null;
                    }
                    view_ = made();
                    backChanged_.accept(false);
                    // a page that stops it each time is loaded again later, longer each time
                    var now = android.os.SystemClock.elapsedRealtime();
                    waited_ = now - replaced_ < 10_000 ? Math.min(Math.max(waited_ * 2, 10_000), 300_000) : 0;
                    var replacement = view_;
                    loading_ = true;
                    shown.postDelayed(() -> {
                        if (replacement == view_) {
                            // since the page was loaded again, not since it stopped, so that the wait grows
                            replaced_ = android.os.SystemClock.elapsedRealtime();
                            load(replacement, at_);
                        }
                    }, waited_);
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
            // a page that became a file to save doesn't load
            if (view == view_) {
                loading_ = false;
                waiting_.clear();
            }
            if (NativeSite.own(activity, Uri.parse(url))) {
                WRITING.execute(() -> {
                    // what the web view already asked for, which isn't asked again
                    var answer = requests_.downloaded(url);
                    if (null == answer) {
                        answer = NativeSite.ask(activity, url, "GET", "", null);
                    }
                    if (null == answer.failure && answer.status == 200) {
                        offer(URLUtil.guessFileName(url, disposition, type), type, answer.body);
                    } else {
                        Log.w("rife2", "The file at " + url + " couldn't be saved: " + (null == answer.failure ? answer.status : answer.failure));
                    }
                });
            }
        });
        configure(view);
        told(view);
        shown.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return view;
    }

    // what the pages know of the app before their own scripts run, in place of what they knew before
    private void told(WebView view) {
        try {
            host_ = new JSONObject().put("protocol", PROTOCOL).put("heard", new JSONArray(listened_.keySet())).put("zooms", zooms()).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (told_ != null) {
                told_.remove();
            }
            told_ = WebViewCompat.addDocumentStartJavaScript(view, "window.rife2NativeAppHost = " + host_ + ";", Set.of(origin()));
        } else if (told_ == null && !view.equals(hosted_)) {
            // An older web view asks for it as the page starts, which every
            // frame can, as it can read it of the site's own pages anyway.
            hosted_ = view;
            view.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public String told() {
                    return host_;
                }
            }, "Rife2NativeAppHost");
        }
    }

    private WebView hosted_;

    // androidx takes a scheme of its own without a host
    private String origin() {
        var http = NativeSite.scheme.equalsIgnoreCase("http") || NativeSite.scheme.equalsIgnoreCase("https");
        return http ? NativeSite.root(activity) : NativeSite.scheme + "://";
    }

    /**
     * Hears what the pages tell the app with {@code rife2NativeApp.tell(name, data)},
     * on the thread of the screen, as a {@link String}, a {@link Number}, a
     * {@link Boolean}, a {@link JSONObject}, a {@link JSONArray} or
     * {@link JSONObject#NULL}. Only a page of the site is heard. A page knows
     * what the app hears with {@code rife2NativeApp.hears(name)}, from the
     * next page on when this is called after {@link #configure}.
     */
    public void listen(String name, Consumer<Object> heard) {
        var known = listened_.containsKey(name);
        listened_.put(name, heard);
        if (view_ != null && !known) {
            told(view_);
        }
    }

    /**
     * Tells the page that's shown something, which it hears with
     * {@code rife2NativeApp.on(name, heard)}; nothing happens when it doesn't
     * listen. What's told is what {@link JSONObject#wrap} takes.
     */
    public void tell(String name, Object data) {
        run("(app => app && app.told ? app.told(" + JSONObject.quote(name) + ", " + written(data) + ") === true : false)(window.rife2NativeApp)", null);
    }

    /** What a function of the page answered. */
    public interface Called {
        /**
         * @param called whether the page has the function
         * @param result what the function returned when it's something that a
         *               script can return, as {@link #listen} hears it; or {@code null}
         */
        void answered(boolean called, Object result);
    }

    /**
     * Calls a function of the page that's shown, {@code showChart} or
     * {@code charts.show} for instance, with arguments that
     * {@link JSONObject#wrap} takes. What it returns is answered as it is,
     * not after a promise that it returns was kept, and nothing when it
     * failed. The function is found from {@code window} on, as a
     * {@code function} or a {@code var} of a page's script is, not a
     * {@code const}, a {@code let} or a {@code class}.
     *
     * @param function  the name of the function, from {@code window} on
     * @param arguments what the function is given
     * @param answered  told whether the page has the function and what it returned; or {@code null}
     */
    public void call(String function, Object[] arguments, Called answered) {
        var script = "(() => { let owner = window, found = window;" +
                     " for (const name of " + JSONObject.quote(function) + ".split('.')) { owner = found; found = found == null ? undefined : found[name]; }" +
                     " if (typeof found !== 'function') return [false];" +
                     " let result; try { result = found.apply(owner, " + written(arguments) + "); } catch (e) { return [true, null]; }" +
                     " try { return [true, JSON.parse(JSON.stringify(result === undefined ? null : result))]; } catch (e) { return [true, null]; } })()";
        run(script, said -> {
            if (answered != null) {
                var result = said instanceof JSONArray array ? array : new JSONArray();
                var value = result.opt(1);
                answered.answered(result.optBoolean(0), JSONObject.NULL.equals(value) ? null : value);
            }
        });
    }

    /**
     * Presses what a selector finds on the page that's shown, a button for instance.
     *
     * @param selector the CSS selector of what's pressed, the first it finds
     * @param pressed  told whether the page has it; or {@code null}
     */
    public void press(String selector, Consumer<Boolean> pressed) {
        run("(() => { const found = document.querySelector(" + JSONObject.quote(selector) + "); if (!found) return false; found.click(); return true; })()", said -> {
            if (pressed != null) {
                pressed.accept(Boolean.TRUE.equals(said));
            }
        });
    }

    // a value as a script writes it
    private static String written(Object value) {
        var wrapped = value instanceof Object[] array ? new JSONArray(java.util.Arrays.asList(array)) : JSONObject.wrap(value instanceof Character character ? character.toString() : value);
        return null == wrapped ? "null" : wrapped instanceof String string ? JSONObject.quote(string) : wrapped.toString();
    }

    private void run(String script, Consumer<Object> answered) {
        if (null == view_) {
            if (answered != null) {
                answered.accept(null);
            }
            return;
        }
        view_.evaluateJavascript(script, said -> {
            if (answered != null) {
                try {
                    answered.accept(null == said ? null : new JSONTokener(said).nextValue());
                } catch (JSONException e) {
                    answered.accept(null);
                }
            }
        });
    }

    // the page is there, for what waits for it
    private void finished() {
        loading_ = false;
        var run = waiting_;
        waiting_ = new ArrayList<>();
        for (var each : run) {
            each.run();
        }
    }

    // who's told whether there's a page to go back to, each time that can have changed
    void backChanged(Consumer<Boolean> changed) {
        backChanged_ = changed;
        changed.accept(canGoBack());
    }

    /** Whether a page is loading. */
    public boolean loading() {
        return null == view_ || loading_;
    }

    /**
     * Runs something once the page that's loading is there, or now when
     * none is. When the page doesn't load, it doesn't run.
     */
    public void whenLoaded(Runnable run) {
        if (loading()) {
            waiting_.add(run);
        } else {
            run.run();
        }
    }

    /** Shows the page at a path of the site. Before {@link #start}, that's the first page. */
    public void go(String path) {
        // without the slash, the path would be part of the host
        open(NativeSite.root(activity) + (path.startsWith("/") ? path : "/" + path));
    }

    /**
     * Shows the page at an address of the site, one that a widget or a link
     * to the app was tapped for for instance; one of elsewhere isn't shown.
     * Before {@link #start}, that's the first page.
     *
     * @return whether the address is one of the site
     */
    public boolean open(String address) {
        if (!NativeSite.own(activity, Uri.parse(address))) {
            return false;
        }
        if (null == view_) {
            at_ = address;
        } else {
            load(view_, address);
        }
        return true;
    }

    /** Whether there's a page to go back to. */
    public boolean canGoBack() {
        return view_ != null && view_.canGoBack();
    }

    /** Whether there's a page to go forward to. */
    public boolean canGoForward() {
        return view_ != null && view_.canGoForward();
    }

    /** Goes back a page, when there's one to go back to. */
    public boolean back() {
        if (view().canGoBack()) {
            loading_ = true;
            view_.goBack();
            return true;
        }
        return false;
    }

    /** Goes forward a page, when there's one to go forward to. */
    public boolean forward() {
        if (view().canGoForward()) {
            loading_ = true;
            view_.goForward();
            return true;
        }
        return false;
    }

    /** Loads the page that's shown again. */
    public void reload() {
        loading_ = true;
        view().reload();
    }

    /** Keeps where the pages are for when Android takes the activity away and brings it back. */
    public void keep(Bundle state) {
        view().saveState(state);
    }

    /** Lets go of the web view, whose page would otherwise go on behind an activity that's gone. */
    public void gone() {
        shown.removeAllViews();
        told_ = null;
        if (view_ != null) {
            view_.destroy();
            // a reload that's still to come leaves it alone
            view_ = null;
        }
    }

    // what a page asks of the app through the script of the pages, on the thread of the screen
    private void asked(WebView view, WebMessageCompat message, Uri from, boolean mainFrame, JavaScriptReplyProxy reply) {
        // a scheme of the app's own has no host for androidx, which only lets the site's frames ask
        var http = NativeSite.scheme.equalsIgnoreCase("http") || NativeSite.scheme.equalsIgnoreCase("https");
        if (!(http ? NativeSite.own(activity, from) : NativeSite.scheme.equalsIgnoreCase(from.getScheme())) || null == message.getData() || view != view_) {
            return;
        }
        try {
            var said = new JSONObject(message.getData());
            switch (said.optString("action")) {
                case "print" -> {
                    var title = null == view.getTitle() ? "" : view.getTitle();
                    ((PrintManager) activity.getSystemService(Context.PRINT_SERVICE)).print(title, view.createPrintDocumentAdapter(title), new PrintAttributes.Builder().build());
                }
                case "share" -> activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, said.optString("title")).putExtra(Intent.EXTRA_TEXT, said.optString("url")), null));
                case "save" -> offer(said.optString("name"), said.optString("type"), Base64.decode(said.optString("data"), Base64.DEFAULT));
                case "posted" -> {
                    // a page waits for the answer, which says nothing when what it posts can't be read
                    var named = "";
                    try {
                        named = requests_.posted(said.optString("type"), Base64.decode(said.optString("data"), Base64.DEFAULT));
                    } catch (IllegalArgumentException e) {
                        Log.w("rife2", "What a page posts couldn't be read, it goes without it");
                    }
                    reply.postMessage(new JSONObject().put("id", said.optLong("id")).put("name", named).toString());
                }
                case "tell" -> {
                    var heard = listened_.get(said.optString("name"));
                    if (heard != null) {
                        heard.accept(said.has("data") ? said.get("data") : JSONObject.NULL);
                    }
                }
                default -> Log.w("rife2", "A page asked for " + said.optString("action") + ", which this version of rife2-native-app doesn't know");
            }
        } catch (JSONException | IllegalArgumentException e) {
            Log.w("rife2", "A page asked what isn't understood: " + e);
        }
    }

    // What a page saves is kept as a file where the person chooses, or goes
    // to another app. It's a file of the app first, the one that another app
    // is handed and that's still there when Android took the activity away
    // meanwhile.
    private void offer(String name, String type, byte[] content) {
        var kind = null == type || type.isEmpty() ? "application/octet-stream" : type.split(";", 2)[0].trim();
        WRITING.execute(() -> {
            try {
                var file = NativeShared.keep(activity, name, content);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    new AlertDialog.Builder(activity).setTitle(file.getName()).setItems(new String[]{NativeTexts.save, NativeTexts.share}, (dialog, chosen) -> {
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
                failed(NativeTexts.saveFailed, e);
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
                        failed(NativeTexts.saveFailed, e);
                    }
                });
            }
            return true;
        }
        return false;
    }
}
