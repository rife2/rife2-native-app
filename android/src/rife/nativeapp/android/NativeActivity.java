/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;

/**
 * An activity that shows the pages of the site and hands them what they need
 * of it: the result of a file that was chosen or saved, what's kept when
 * Android takes the activity away, the app being in front again, the back
 * gesture and the end. The pages are laid out inside the bars of the system
 * and beside a camera in the screen, see {@link #fit}.
 * <p>An app's activity extends this and makes its pages in {@link #pages()}:
 * <pre>
 * public class MainActivity extends NativeActivity {
 *     protected NativePages pages() {
 *         return new NativePages(this, "/");
 *     }
 * }
 * </pre>
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public abstract class NativeActivity extends Activity {
    /** The pages that this shows, once {@link #onCreate} made them. */
    protected NativePages pages;
    // the callback of the back gesture, from Android 13 on
    private Object back_;
    private boolean backing_;

    /**
     * Makes the pages that this shows, which {@link #onCreate} starts. An
     * {@link NativePages#open} or a {@link NativePages#go} of the pages
     * before they're returned chooses the first page; {@link #open(Intent)}
     * is for after {@code onCreate}, since {@link #pages} isn't set before.
     */
    protected abstract NativePages pages();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pages = pages().start(state);
        pages.shown.setOnApplyWindowInsetsListener((view, insets) -> {
            fit(view, insets);
            return insets;
        });
        setContentView(pages.shown);
        if (Build.VERSION.SDK_INT >= 33) {
            // the system's back gesture shows where it goes when it isn't asked
            // for a page, which it only is while there's one to go back to
            back_ = Api33.callback(() -> {
                if (!pages.back()) {
                    backs();
                }
            });
            pages.backChanged(this::backChanged);
        }
    }

    // only while a page can go back, so that otherwise the system's gesture leaves the app
    private void backChanged(boolean back) {
        if (Build.VERSION.SDK_INT < 33 || back == backing_) {
            return;
        }
        backing_ = back;
        Api33.back(this, back_, back);
    }

    // what the system would have done with the back gesture
    @SuppressWarnings("deprecation")
    private void backs() {
        backChanged(false);
        super.onBackPressed();
    }

    /**
     * Fits the pages inside the bars of the system and beside a camera in
     * the screen, which an app that targets Android 15 or later draws
     * under. An app whose pages go on behind them, to the edges of the
     * screen, does less here.
     *
     * @param shown  what holds the web view
     * @param insets what the system covers of the window
     */
    @SuppressWarnings("deprecation")
    protected void fit(View shown, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Api30.fit(shown, insets);
        } else {
            // the window keeps its pages clear of the bars, which then don't reach this, and leaves the camera to the app
            var window = shown.getRootWindowInsets();
            if (null == window) {
                window = insets;
            }
            var camera = insets.getDisplayCutout();
            shown.setPadding(edge(window.getSystemWindowInsetLeft(), insets.getSystemWindowInsetLeft(), null == camera ? 0 : camera.getSafeInsetLeft()),
                edge(window.getSystemWindowInsetTop(), insets.getSystemWindowInsetTop(), null == camera ? 0 : camera.getSafeInsetTop()),
                edge(window.getSystemWindowInsetRight(), insets.getSystemWindowInsetRight(), null == camera ? 0 : camera.getSafeInsetRight()),
                edge(window.getSystemWindowInsetBottom(), insets.getSystemWindowInsetBottom(), null == camera ? 0 : camera.getSafeInsetBottom()));
        }
    }

    // how far the pages stay from an edge of the window: clear of its bars and of the camera, less what the window keeps clear itself
    private static int edge(int bars, int reached, int camera) {
        return Math.max(0, Math.max(bars, camera) - (bars - reached));
    }

    /**
     * Opens what an intent names, an address of the site that a widget or a
     * link was tapped for for instance; one of elsewhere isn't opened. Any
     * app can start an activity that's exported with an intent, so an app
     * that does this for one checks first that it's one of its own.
     *
     * @return whether it was an address of the site
     */
    protected boolean open(Intent intent) {
        return intent != null && intent.getData() != null && pages.open(intent.getData().toString());
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        pages.chosen(request, result, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        pages.keep(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pages.resumed();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!pages.back()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        pages.gone();
        super.onDestroy();
    }

    // What came with Android 11 and 13, apart, so that the check of
    // app-android takes them for what's only used from then on.
    private static final class Api30 {
        static void fit(View shown, WindowInsets insets) {
            var covered = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            shown.setPadding(covered.left, covered.top, covered.right, covered.bottom);
        }
    }

    private static final class Api33 {
        static Object callback(Runnable back) {
            return (android.window.OnBackInvokedCallback) back::run;
        }

        static void back(Activity activity, Object callback, boolean register) {
            var dispatcher = activity.getOnBackInvokedDispatcher();
            if (register) {
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) callback);
            } else {
                dispatcher.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
            }
        }
    }
}
