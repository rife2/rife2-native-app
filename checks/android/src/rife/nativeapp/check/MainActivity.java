/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.check;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import rife.nativeapp.android.NativePages;
import rife.nativeapp.android.NativeSite;

// the site of a native library in the web view of rife2-native-app, as an app would show it
public class MainActivity extends Activity {
    private NativePages pages_;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            NativeSite.library = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA).metaData.getString("rife2.library");
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
        var start = getIntent().getStringExtra("start");
        pages_ = new NativePages(this, start == null ? "/" : start).start(state);
        setContentView(pages_.shown);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        pages_.chosen(request, result, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        pages_.keep(state);
    }

    @Override
    public void onBackPressed() {
        if (!pages_.back()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        pages_.gone();
        super.onDestroy();
    }
}
