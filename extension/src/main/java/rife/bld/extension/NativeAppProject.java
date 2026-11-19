/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension;

import rife.bld.BuildCommand;
import rife.bld.WebProject;
import rife.bld.extension.nativeapp.*;

import java.io.File;

/**
 * A web project whose site also runs inside an iOS, macOS, visionOS or Android app,
 * with RIFE2's {@code rife.nativeapp}. It adds the commands that build the
 * site into a native library, check that library against the JVM, and
 * build the JDK libraries that iOS and Android need; {@link #nativeApp()}
 * says how.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeAppProject extends WebProject {
    private final NativeAppSettings nativeApp_ = new NativeAppSettings();

    /**
     * What the native library is, and how it's checked.
     *
     * @return the settings of the native library
     * @since 1.11.0
     */
    public NativeAppSettings nativeApp() {
        return nativeApp_;
    }

    private Target target() {
        if (arguments().isEmpty()) {
            throw new IllegalArgumentException("Name what to build for: mac, ios, ios-sim, vision, vision-sim or android");
        }
        return Target.from(arguments().remove(0));
    }

    /**
     * Builds the site into a native library for the target that the first argument names.
     *
     * @throws Exception when it can't be done
     * @since 1.11.0
     */
    @BuildCommand(value = "app-library", summary = "Builds the site into a native library for an app: mac, ios, ios-sim, vision, vision-sim or android")
    public void appLibrary()
    throws Exception {
        var target = target();
        jar();
        new NativeLibraryOperation().fromProject(this).target(target).execute();
    }

    /**
     * Checks that the native library of the target that the first argument names answers like the JVM.
     *
     * @throws Exception when it can't be done
     * @since 1.11.0
     */
    @BuildCommand(value = "app-check", summary = "Checks that the native library answers like the JVM: mac, ios-sim, vision-sim or android")
    public void appCheck()
    throws Exception {
        var target = target();
        jar();
        new NativeCheckOperation().fromProject(this).target(target).execute();
    }

    /**
     * Puts an Android app together around the library for Android that was
     * built last, and installs and starts it when adb sees an emulator or a
     * device; {@link NativeAppSettings#androidApp()} says what it's made of.
     *
     * @throws Exception when it can't be done
     * @since 1.11.0
     */
    @BuildCommand(value = "app-android", summary = "Puts an Android app together around the library for Android, and starts it")
    public void appAndroid()
    throws Exception {
        new AndroidAppOperation().fromProject(this).execute();
    }

    /**
     * Checks the privacy manifests of the app for Apple's systems that the
     * first argument names, a {@code .app} directory, against what its
     * binaries use.
     *
     * @throws Exception when it can't be done, or the manifests are wrong
     * @since 1.11.0
     */
    @BuildCommand(value = "app-check-privacy", summary = "Checks the privacy manifests of a built app against what its binaries use: path/to/App.app")
    public void appCheckPrivacy()
    throws Exception {
        if (arguments().isEmpty()) {
            throw new IllegalArgumentException("Name the app to check: app-check-privacy path/to/App.app");
        }
        var app = new File(arguments().remove(0));
        new PrivacyCheckOperation().app(app.isAbsolute() ? app : new File(workDirectory(), app.getPath())).execute();
    }

    /**
     * Prints the UDID of the simulator for the target that the first
     * argument names, ios-sim or vision-sim: the one that the next argument
     * names as Xcode does, like {@code "iPhone 17 Pro"}, or else the one of the
     * settings. It's started first when the argument after that is
     * {@code boot}.
     *
     * @throws Exception when it can't be done
     * @since 1.11.0
     */
    @BuildCommand(value = "app-simulator", summary = "Prints the UDID of the simulator for ios-sim or vision-sim, [\"name\"] [boot] to start it")
    public void appSimulator()
    throws Exception {
        var target = target();
        // a name of Xcode's isn't one of a command, which is in lower case without spaces
        String name = null;
        if (!arguments().isEmpty() && !arguments().get(0).matches("[a-z0-9-]+")) {
            name = arguments().remove(0);
        }
        var boot = !arguments().isEmpty() && arguments().get(0).equals("boot");
        if (boot) {
            arguments().remove(0);
        }
        new SimulatorOperation().fromProject(this).target(target).name(name).boot(boot).execute();
    }

    /**
     * Builds the JDK libraries that native libraries for the target that the first argument names need.
     *
     * @throws Exception when it can't be done
     * @since 1.11.0
     */
    @BuildCommand(value = "app-jdk-libs", summary = "Builds the JDK libraries that native libraries for ios, ios-sim or android need")
    public void appJdkLibraries()
    throws Exception {
        new NativeJdkLibrariesOperation().fromProject(this).target(target()).execute();
    }
}
