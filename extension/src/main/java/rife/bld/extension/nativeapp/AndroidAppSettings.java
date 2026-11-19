/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What {@code app-android} puts together as an Android app around the
 * site's native library: the app's manifest, resources and Java sources,
 * the libraries that it uses beside rife2-native-app's classes and
 * androidx.webkit, which it always has, and the key that it's signed with.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class AndroidAppSettings {
    private File manifest_;
    private File resources_;
    private final List<File> sources_ = new ArrayList<>();
    private final List<File> libraries_ = new ArrayList<>();
    private String versionName_;
    private long versionCode_ = 1;
    private boolean debuggable_;
    private File keystore_;
    private File keystorePassword_;
    private String keyAlias_ = "app";
    private File apk_;
    private boolean install_ = true;

    /**
     * The manifest of the app, with its package, its activities and the
     * Android versions that it's for.
     *
     * @param manifest the manifest file
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings manifest(File manifest) {
        manifest_ = manifest;
        return this;
    }

    /**
     * The manifest of the app.
     *
     * @return the manifest; or {@code null} when it wasn't set
     * @since 1.11.0
     */
    public File manifest() {
        return manifest_;
    }

    /**
     * The directory of the app's resources, the one that has
     * {@code values}, {@code drawable} and so on in it.
     *
     * @param directory the directory
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings resources(File directory) {
        resources_ = directory;
        return this;
    }

    /**
     * The directory of the app's resources.
     *
     * @return the directory; or {@code null} when the app has none
     * @since 1.11.0
     */
    public File resources() {
        return resources_;
    }

    /**
     * Directories of the app's Java sources.
     *
     * @param directories the directories
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings sources(File... directories) {
        sources_.addAll(Arrays.asList(directories));
        return this;
    }

    /**
     * The directories of the app's Java sources.
     *
     * @return the directories
     * @since 1.11.0
     */
    public List<File> sources() {
        return sources_;
    }

    /**
     * Jars that the app uses and has inside it, beside rife2-native-app's
     * classes and androidx.webkit.
     *
     * @param jars the jars
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings libraries(File... jars) {
        libraries_.addAll(Arrays.asList(jars));
        return this;
    }

    /**
     * The jars that the app uses.
     *
     * @return the jars
     * @since 1.11.0
     */
    public List<File> libraries() {
        return libraries_;
    }

    /**
     * The version of the app that people see, and its version code, which
     * Android only installs over a lower one; the project's version and 1
     * when it's not set.
     *
     * @param name the version, like {@code 1.2.0}
     * @param code the version code, like {@code 52}
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings version(String name, long code) {
        versionName_ = name;
        versionCode_ = code;
        return this;
    }

    /**
     * The version of the app.
     *
     * @return the version; or {@code null} for the project's
     * @since 1.11.0
     */
    public String versionName() {
        return versionName_;
    }

    /**
     * The version code of the app.
     *
     * @return the version code
     * @since 1.11.0
     */
    public long versionCode() {
        return versionCode_;
    }

    /**
     * Whether the app can be debugged, and its web view looked into from
     * Chrome on a computer, which an app that's handed to anyone isn't.
     *
     * @param debuggable whether it can be debugged
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings debuggable(boolean debuggable) {
        debuggable_ = debuggable;
        return this;
    }

    /**
     * Whether the app can be debugged.
     *
     * @return whether it can be debugged
     * @since 1.11.0
     */
    public boolean debuggable() {
        return debuggable_;
    }

    /**
     * The key that the app is signed with: a keystore, a file with its
     * password, and the alias of the key in it. Android only installs a
     * newer build over an older one that the same key signed, so the
     * keystore is kept outside of the project, and with care. When the
     * keystore isn't there yet, it's made with a key of that alias and a
     * random password, which is written to the password file.
     *
     * @param keystore the keystore
     * @param password the file with its password
     * @param alias the alias of the key
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings key(File keystore, File password, String alias) {
        keystore_ = keystore;
        keystorePassword_ = password;
        keyAlias_ = alias;
        return this;
    }

    /**
     * The keystore with the key that the app is signed with.
     *
     * @return the keystore; or {@code null} when it wasn't set
     * @since 1.11.0
     */
    public File keystore() {
        return keystore_;
    }

    /**
     * The file with the password of the keystore.
     *
     * @return the file with the password; or {@code null} when it wasn't set
     * @since 1.11.0
     */
    public File keystorePassword() {
        return keystorePassword_;
    }

    /**
     * The alias of the key that the app is signed with.
     *
     * @return the alias
     * @since 1.11.0
     */
    public String keyAlias() {
        return keyAlias_;
    }

    /**
     * Where the app is written; {@code build/android/<library name>.apk}
     * when it's not set. A relative file is one of the project's.
     *
     * @param apk the file
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings apk(File apk) {
        apk_ = apk;
        return this;
    }

    /**
     * Where the app is written, when it was set.
     *
     * @return the file; or {@code null} when it wasn't set
     * @since 1.11.0
     */
    public File apk() {
        return apk_;
    }

    /**
     * Whether the app is installed and started on the emulator or the
     * device that adb sees, when there's one; {@code true} when it's not set.
     *
     * @param install whether it's installed
     * @return these settings
     * @since 1.11.0
     */
    public AndroidAppSettings install(boolean install) {
        install_ = install;
        return this;
    }

    /**
     * Whether the app is installed and started.
     *
     * @return whether it's installed
     * @since 1.11.0
     */
    public boolean install() {
        return install_;
    }
}
