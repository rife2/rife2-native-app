/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension;

import rife.bld.BuildCommand;
import rife.bld.WebProject;
import rife.bld.extension.nativeapp.*;

/**
 * A web project whose site also runs inside an iOS, macOS or Android app,
 * with rife2-native-app. It adds the commands that build the site into a
 * native library, check that library against the JVM, and build the JDK
 * libraries that iOS and Android need; {@link #nativeApp()} says how.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeAppProject extends WebProject {
    private final NativeAppSettings nativeApp_ = new NativeAppSettings();

    /**
     * What the native library is, and how it's checked.
     */
    public NativeAppSettings nativeApp() {
        return nativeApp_;
    }

    private Target target() {
        if (arguments().isEmpty()) {
            throw new IllegalArgumentException("Name what to build for: mac, ios, ios-sim or android");
        }
        return Target.from(arguments().remove(0));
    }

    @BuildCommand(value = "native-library", summary = "Builds the site into a native library for an app: mac, ios, ios-sim or android")
    public void nativeLibrary()
    throws Exception {
        var target = target();
        jar();
        new NativeLibraryOperation().fromProject(this).target(target).execute();
    }

    @BuildCommand(value = "native-check", summary = "Checks that the native library answers like the JVM: mac, ios-sim or android")
    public void nativeCheck()
    throws Exception {
        var target = target();
        jar();
        new NativeCheckOperation().fromProject(this).target(target).execute();
    }

    @BuildCommand(value = "native-jdk-libs", summary = "Builds the JDK libraries that native libraries for ios, ios-sim or android need")
    public void nativeJdkLibraries()
    throws Exception {
        new NativeJdkLibrariesOperation().fromProject(this).target(target()).execute();
    }
}
