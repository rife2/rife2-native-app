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
    private int androidApi_ = 29;
    private String androidNdk_ = "28.2.13676358";
    private File androidSdk_;
    private File jdkLibraries_;
    private String simulator_;
    private String visionSimulator_;
    private String frameworkVersion_;
    private String frameworkBuild_;
    private String androidBuildTools_ = "35.0.0";
    private int androidPlatform_ = 35;
    private final AndroidAppSettings androidApp_ = new AndroidAppSettings();
    private File destinationDirectory_;

    /**
     * The name of the library and of its framework, {@code site} for
     * {@code libsite.so} and {@code site.framework}; the name of the
     * project when it's not set.
     *
     * @param name the name
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings libraryName(String name) {
        libraryName_ = name;
        return this;
    }

    /**
     * The name of the library, when it was set.
     *
     * @return the name of the library, when it was set
     * @since 1.11.0
     */
    public String libraryName() {
        return libraryName_;
    }

    /**
     * The bundle identifier of the framework on Apple's systems; the
     * package of the project with {@code .site} when it's not set.
     *
     * @param identifier the bundle identifier
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings bundleIdentifier(String identifier) {
        bundleIdentifier_ = identifier;
        return this;
    }

    /**
     * The bundle identifier of the framework, when it was set.
     *
     * @return the bundle identifier of the framework, when it was set
     * @since 1.11.0
     */
    public String bundleIdentifier() {
        return bundleIdentifier_;
    }

    /**
     * Addresses that the site is asked for on the JVM before every native
     * build, which shows what's wrong in seconds rather than after it, and
     * which {@code app-check} asks the native library for as well.
     * Their answers have to be the same each time, so a page with a CSRF
     * token or the time in it isn't one of them.
     *
     * @param addresses the addresses
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings addresses(String... addresses) {
        addresses_.addAll(Arrays.asList(addresses));
        return this;
    }

    /**
     * The addresses that the site is asked for.
     *
     * @return the addresses that the site is asked for
     * @since 1.11.0
     */
    public List<String> addresses() {
        return addresses_;
    }

    /**
     * A property that the site is told when it's asked on the JVM and by
     * {@code app-check}, like an app would tell it.
     *
     * @param name the name of the property
     * @param value its value
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings property(String name, String value) {
        properties_.put(name, value);
        return this;
    }

    /**
     * The properties that the site is told.
     *
     * @return the properties that the site is told
     * @since 1.11.0
     */
    public Map<String, String> properties() {
        return properties_;
    }

    /**
     * A class whose {@code main} goes through the site the way the app
     * does, run on the JVM before every native build with GraalVM's tracing
     * agent, which records what the native library needs that native-image
     * can't see by itself.
     *
     * @param mainClass the name of the class
     * @param arguments the arguments of its {@code main}
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings walk(String mainClass, String... arguments) {
        walkClass_ = mainClass;
        walkArguments_.clear();
        walkArguments_.addAll(Arrays.asList(arguments));
        return this;
    }

    /**
     * The class that goes through the site, when there's one.
     *
     * @return the class that goes through the site, when there's one
     * @since 1.11.0
     */
    public String walkClass() {
        return walkClass_;
    }

    /**
     * The arguments of the class that goes through the site.
     *
     * @return the arguments of the class that goes through the site
     * @since 1.11.0
     */
    public List<String> walkArguments() {
        return walkArguments_;
    }

    /**
     * The privacy manifest of the framework on Apple's systems. Without
     * one, it's written from what the library's code asks of the system,
     * with the reasons that a site inside an app has.
     *
     * @param manifest the manifest file
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings privacyManifest(File manifest) {
        privacyManifest_ = manifest;
        return this;
    }

    /**
     * The privacy manifest of the framework, when it was set.
     *
     * @return the privacy manifest of the framework, when it was set
     * @since 1.11.0
     */
    public File privacyManifest() {
        return privacyManifest_;
    }

    /**
     * The Android API level that the library is linked for, the oldest
     * Android that it runs on; 29 when it's not set.
     *
     * @param api the API level
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings androidApi(int api) {
        androidApi_ = api;
        return this;
    }

    /**
     * The Android API level that the library is linked for.
     *
     * @return the Android API level that the library is linked for
     * @since 1.11.0
     */
    public int androidApi() {
        return androidApi_;
    }

    /**
     * The version of the NDK in the Android SDK that links the library.
     *
     * @param version the version
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings androidNdk(String version) {
        androidNdk_ = version;
        return this;
    }

    /**
     * The version of the NDK that links the library.
     *
     * @return the version of the NDK that links the library
     * @since 1.11.0
     */
    public String androidNdk() {
        return androidNdk_;
    }

    /**
     * The Android SDK; {@code ANDROID_HOME}, or where Android Studio puts it
     * on a Mac, when it's not set.
     *
     * @param sdk the directory of the SDK
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings androidSdk(File sdk) {
        androidSdk_ = sdk;
        return this;
    }

    /**
     * The Android SDK.
     *
     * @return the Android SDK
     * @since 1.11.0
     */
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
     *
     * @param directory the directory
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings jdkLibraries(File directory) {
        jdkLibraries_ = directory;
        return this;
    }

    /**
     * Where the JDK libraries for iOS and Android are kept.
     *
     * @return where the JDK libraries for iOS and Android are kept
     * @since 1.11.0
     */
    public File jdkLibraries() {
        if (jdkLibraries_ != null) {
            return jdkLibraries_.getAbsoluteFile();
        }
        var set = System.getenv("RIFE2_NATIVE_JDK_LIBS");
        return set != null && !set.isBlank() ? new File(set).getAbsoluteFile() : new File(System.getProperty("user.home"), ".rife2/native-app/jdk-libs");
    }

    /**
     * The simulator that {@code app-check ios-sim} runs in, and that
     * {@code app-simulator ios-sim} finds; the first iPhone with the newest
     * iOS when it's not set.
     *
     * @param name the name of the simulator
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings simulator(String name) {
        simulator_ = name;
        return this;
    }

    /**
     * The simulator for iOS, when it was set.
     *
     * @return the name of the simulator for iOS, when it was set
     * @since 1.11.0
     */
    public String simulator() {
        return simulator_;
    }

    /**
     * The simulator that {@code app-check vision-sim} runs in, and that
     * {@code app-simulator vision-sim} finds; the first Apple Vision Pro
     * with the newest visionOS when it's not set.
     *
     * @param name the name of the simulator
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings visionSimulator(String name) {
        visionSimulator_ = name;
        return this;
    }

    /**
     * The simulator for visionOS, when it was set.
     *
     * @return the name of the simulator for visionOS, when it was set
     * @since 1.11.0
     */
    public String visionSimulator() {
        return visionSimulator_;
    }

    /**
     * The version and the build number of the frameworks for Apple's
     * systems, which their Info.plist says; the numbers of the project's
     * version and build number 1 when it's not set. An app usually gives
     * them its own.
     *
     * @param version the version, like {@code 1.2.0}
     * @param build the build number, like {@code 52}
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings frameworkVersion(String version, String build) {
        frameworkVersion_ = version;
        frameworkBuild_ = build;
        return this;
    }

    /**
     * The version of the frameworks, when it was set.
     *
     * @return the version of the frameworks, when it was set
     * @since 1.11.0
     */
    public String frameworkVersion() {
        return frameworkVersion_;
    }

    /**
     * The build number of the frameworks, when it was set.
     *
     * @return the build number of the frameworks, when it was set
     * @since 1.11.0
     */
    public String frameworkBuild() {
        return frameworkBuild_;
    }

    /**
     * The version of the Android SDK's build tools that put an Android app
     * together; 35.0.0 when it's not set.
     *
     * @param version the version
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings androidBuildTools(String version) {
        androidBuildTools_ = version;
        return this;
    }

    /**
     * The version of the build tools that put an Android app together.
     *
     * @return the version of the build tools
     * @since 1.11.0
     */
    public String androidBuildTools() {
        return androidBuildTools_;
    }

    /**
     * The API level of the Android platform that an Android app is compiled
     * against, the newest that it knows of; 35 when it's not set.
     *
     * @param api the API level
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings androidPlatform(int api) {
        androidPlatform_ = api;
        return this;
    }

    /**
     * The API level of the platform that an Android app is compiled against.
     *
     * @return the API level of the platform
     * @since 1.11.0
     */
    public int androidPlatform() {
        return androidPlatform_;
    }

    /**
     * What {@code app-android} puts together as an Android app around the
     * library.
     *
     * @return the settings of the Android app
     * @since 1.11.0
     */
    public AndroidAppSettings androidApp() {
        return androidApp_;
    }

    /**
     * Where the libraries are built, a directory for each target and the
     * xcframework beside them; {@code build/native} when it's not set, which
     * {@code ./bld clean} removes. A relative directory is one of the
     * project's.
     *
     * @param directory the directory
     * @return these settings
     * @since 1.11.0
     */
    public NativeAppSettings destinationDirectory(File directory) {
        destinationDirectory_ = directory;
        return this;
    }

    /**
     * Where the libraries are built, when it was set.
     *
     * @return where the libraries are built, when it was set
     * @since 1.11.0
     */
    public File destinationDirectory() {
        return destinationDirectory_;
    }

    /**
     * Where the libraries of a project are built.
     *
     * @param project the project
     * @return the directory
     * @since 1.11.0
     */
    public File destinationDirectory(rife.bld.WebProject project) {
        if (null == destinationDirectory_) {
            return new File(project.buildDirectory(), "native");
        }
        return destinationDirectory_.isAbsolute() ? destinationDirectory_ : new File(project.workDirectory(), destinationDirectory_.getPath());
    }
}
