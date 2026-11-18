/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a build says about the native library of its site.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeAppSettings {
    private String libraryName_;
    private String bundleIdentifier_;
    private final List<String> addresses_ = new ArrayList<>();
    private final Map<String, String> properties_ = new LinkedHashMap<>();
    private String walkClass_;
    private final List<String> walkArguments_ = new ArrayList<>();
    private File privacyManifest_;
    private String minimumIos_ = "17.0";
    private int androidApi_ = 29;
    private String androidNdk_ = "28.2.13676358";
    private File androidSdk_;
    private File jdkLibraries_;
    private String simulator_ = "iPhone 17";

    /**
     * The name of the library and of its framework, {@code site} for
     * {@code libsite.so} and {@code site.framework}; the name of the
     * project when it's not set.
     */
    public NativeAppSettings libraryName(String name) {
        libraryName_ = name;
        return this;
    }

    public String libraryName() {
        return libraryName_;
    }

    /**
     * The bundle identifier of the framework on Apple's systems; the
     * package of the project with {@code .site} when it's not set.
     */
    public NativeAppSettings bundleIdentifier(String identifier) {
        bundleIdentifier_ = identifier;
        return this;
    }

    public String bundleIdentifier() {
        return bundleIdentifier_;
    }

    /**
     * Addresses that the site is asked for on the JVM before every native
     * build, which shows what's wrong in seconds rather than after it, and
     * which {@code native-check} asks the native library for as well.
     * Their answers have to be the same each time, so a page with a CSRF
     * token or the time in it isn't one of them.
     */
    public NativeAppSettings addresses(String... addresses) {
        addresses_.addAll(Arrays.asList(addresses));
        return this;
    }

    public List<String> addresses() {
        return addresses_;
    }

    /**
     * A property that the site is told when it's asked on the JVM and by
     * {@code native-check}, like an app would tell it.
     */
    public NativeAppSettings property(String name, String value) {
        properties_.put(name, value);
        return this;
    }

    public Map<String, String> properties() {
        return properties_;
    }

    /**
     * A class whose {@code main} goes through the site the way the app
     * does, run on the JVM before every native build with GraalVM's tracing
     * agent, which records what the native library needs that native-image
     * can't see by itself.
     */
    public NativeAppSettings walk(String mainClass, String... arguments) {
        walkClass_ = mainClass;
        walkArguments_.clear();
        walkArguments_.addAll(Arrays.asList(arguments));
        return this;
    }

    public String walkClass() {
        return walkClass_;
    }

    public List<String> walkArguments() {
        return walkArguments_;
    }

    /**
     * The privacy manifest of the framework on Apple's systems. Without
     * one, it's written from what the library's code asks of the system,
     * with the reasons that a site inside an app has.
     */
    public NativeAppSettings privacyManifest(File manifest) {
        privacyManifest_ = manifest;
        return this;
    }

    public File privacyManifest() {
        return privacyManifest_;
    }

    public NativeAppSettings minimumIos(String version) {
        minimumIos_ = version;
        return this;
    }

    public String minimumIos() {
        return minimumIos_;
    }

    public NativeAppSettings androidApi(int api) {
        androidApi_ = api;
        return this;
    }

    public int androidApi() {
        return androidApi_;
    }

    public NativeAppSettings androidNdk(String version) {
        androidNdk_ = version;
        return this;
    }

    public String androidNdk() {
        return androidNdk_;
    }

    /**
     * The Android SDK; {@code ANDROID_HOME}, or where Android Studio puts it
     * on a Mac, when it's not set.
     */
    public NativeAppSettings androidSdk(File sdk) {
        androidSdk_ = sdk;
        return this;
    }

    public File androidSdk() {
        if (androidSdk_ != null) {
            return androidSdk_;
        }
        var home = System.getenv("ANDROID_HOME");
        return home != null && !home.isBlank() ? new File(home) : new File(System.getProperty("user.home"), "Library/Android/sdk");
    }

    /**
     * Where the JDK libraries for iOS and Android are kept, which every app
     * on this machine shares; {@code RIFE2_NATIVE_JDK_LIBS}, or
     * {@code ~/.rife2/native-app/jdk-libs}, when it's not set.
     */
    public NativeAppSettings jdkLibraries(File directory) {
        jdkLibraries_ = directory;
        return this;
    }

    public File jdkLibraries() {
        if (jdkLibraries_ != null) {
            return jdkLibraries_.getAbsoluteFile();
        }
        var set = System.getenv("RIFE2_NATIVE_JDK_LIBS");
        return set != null && !set.isBlank() ? new File(set).getAbsoluteFile() : new File(System.getProperty("user.home"), ".rife2/native-app/jdk-libs");
    }

    /**
     * The simulator that {@code native-check ios-sim} runs in.
     */
    public NativeAppSettings simulator(String name) {
        simulator_ = name;
        return this;
    }

    public String simulator() {
        return simulator_;
    }
}
