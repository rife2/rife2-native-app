/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.bld.WebProject;
import rife.bld.extension.NativeAppProject;
import rife.bld.operations.AbstractOperation;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Builds what native-image needs to build for iOS or Android and GraalVM
 * doesn't ship: the static libraries of the JDK in GraalVM, from its sources
 * with the port of OpenJDK's mobile project, GraalVM's own libraries, and
 * for Android the answers about its C types, which are asked of the emulator
 * or the device that adb sees. They're kept in
 * {@link NativeAppSettings#jdkLibraries()} for every app on this machine.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeJdkLibrariesOperation extends AbstractOperation<NativeJdkLibrariesOperation> {
    private WebProject project_;
    private NativeAppSettings settings_;
    private Target target_;

    /**
     * The project whose site this is for, with its settings when it's a
     * {@link NativeAppProject}.
     *
     * @param project the project
     * @return this operation
     * @since 1.11.0
     */
    public NativeJdkLibrariesOperation fromProject(WebProject project) {
        project_ = project;
        if (project instanceof NativeAppProject app) {
            settings_ = app.nativeApp();
        }
        return this;
    }

    /**
     * What the native library is, for a project that isn't a
     * {@link NativeAppProject}.
     *
     * @param settings the settings
     * @return this operation
     * @since 1.11.0
     */
    public NativeJdkLibrariesOperation settings(NativeAppSettings settings) {
        settings_ = settings;
        return this;
    }

    /**
     * The settings of the native library.
     *
     * @return the settings; or {@code null} when none were given
     * @since 1.11.0
     */
    public NativeAppSettings settings() {
        return settings_;
    }

    /**
     * The system that this is for.
     *
     * @return the target; or {@code null} when none was given
     * @since 1.11.0
     */
    public Target target() {
        return target_;
    }

    /**
     * The system that this is for.
     *
     * @param target the target
     * @return this operation
     * @since 1.11.0
     */
    public NativeJdkLibrariesOperation target(Target target) {
        target_ = target;
        return this;
    }

    @Override
    public void execute()
    throws Exception {
        if (null == project_) throw new IllegalStateException("fromProject() wasn't given a project");
        if (null == settings_) throw new IllegalStateException("settings() weren't given");
        if (null == target_) throw new IllegalStateException("target() wasn't given a target");
        if (target_ == Target.MAC) {
            throw new IllegalArgumentException("GraalVM has the JDK libraries for the Mac itself");
        }

        Platform.requireMac();
        var graalvm = GraalVm.find();
        var settings = settings_;
        var libraries = new File(settings.jdkLibraries(), graalvm.runtime());
        // the sources are fetched once, next to what's built of them
        var work = new File(settings.jdkLibraries().getParentFile(), "jdk-build");
        work.mkdirs();
        var tools = new File(work, "tools");
        Resources.extract("jvmfuncs-fallbacks.py", work);
        var environment = new java.util.HashMap<>(Map.of(
            "java_home", graalvm.home().getAbsolutePath(),
            "work", work.getAbsolutePath(),
            "tools", tools.getAbsolutePath()));
        if (target_.isIos()) {
            environment.put("out", new File(libraries, target_ == Target.IOS ? "ios" : "iossim").getAbsolutePath());
            Commands.run(work, environment, List.of("bash", Resources.extract("build-ios-jdk-libs.sh", work).getAbsolutePath(),
                target_ == Target.IOS ? "device" : "sim"));
        } else {
            var android = new Android(project_, settings_, graalvm);
            environment.put("out", new File(libraries, "android").getAbsolutePath());
            environment.put("api", String.valueOf(settings.androidApi()));
            environment.put("toolchain", android.ndk().getAbsolutePath());
            environment.put("compiler", android.compiler().getAbsolutePath());
            environment.put("adb", android.adb().getAbsolutePath());
            Commands.run(work, environment, List.of("bash", Resources.extract("build-android-jdk-libs.sh", work).getAbsolutePath()));
        }
    }
}
