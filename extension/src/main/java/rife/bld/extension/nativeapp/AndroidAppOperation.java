/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.bld.WebProject;
import rife.bld.extension.NativeAppProject;
import rife.bld.operations.AbstractOperation;
import rife.tools.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Puts an Android app together around the site's native library for
 * Android, which {@code app-library android} built, with the tools of the
 * Android SDK themselves: the app's manifest, resources and Java sources,
 * rife2-native-app's classes for Android and androidx.webkit, which they
 * use, and the jars that the app adds. What the app's classes and
 * rife2-native-app's use of Android is checked against the oldest Android
 * that the library is for, see {@link NativeAppSettings#androidApi(int)}.
 * The app is signed, and installed and started on the emulator or the
 * device that adb sees, when there's one. What it's made of is in
 * {@link NativeAppSettings#androidApp()}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class AndroidAppOperation extends AbstractOperation<AndroidAppOperation> {
    static final String WEBKIT_VERSION = "1.12.1";
    private static final String WEBKIT_SHA256 = "18a0a51ad129e0c8563f031d46c8a7e61169f56ae84e562a0d75686941262111";

    private WebProject project_;
    private NativeAppSettings settings_;
    private File apk_;

    /**
     * The project whose site this is for, with its settings when it's a
     * {@link NativeAppProject}.
     *
     * @param project the project
     * @return this operation
     * @since 1.11.0
     */
    public AndroidAppOperation fromProject(WebProject project) {
        project_ = project;
        if (project instanceof NativeAppProject app) {
            settings_ = app.nativeApp();
        }
        return this;
    }

    /**
     * What the native library and the app are, for a project that isn't a
     * {@link NativeAppProject}.
     *
     * @param settings the settings
     * @return this operation
     * @since 1.11.0
     */
    public AndroidAppOperation settings(NativeAppSettings settings) {
        settings_ = settings;
        return this;
    }

    /**
     * The settings of the native library and the app.
     *
     * @return the settings; or {@code null} when none were given
     * @since 1.11.0
     */
    public NativeAppSettings settings() {
        return settings_;
    }

    /**
     * The app that was put together.
     *
     * @return the app; or {@code null} before this ran
     * @since 1.11.0
     */
    public File apk() {
        return apk_;
    }

    @Override
    public void execute()
    throws Exception {
        if (null == project_) throw new IllegalStateException("fromProject() wasn't given a project");
        if (null == settings_) throw new IllegalStateException("settings() weren't given");
        var app = settings_.androidApp();
        if (null == app.manifest()) throw new IllegalStateException("The build names no manifest of the app: nativeApp().androidApp().manifest(...)");
        if (app.versionCode() < 1 || app.versionCode() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A version code is from 1 to " + Integer.MAX_VALUE + ", not " + app.versionCode() + ": nativeApp().androidApp().version(...)");
        }
        if (null == app.keystore() || null == app.keystorePassword()) {
            throw new IllegalStateException("The build names no key to sign the app with: nativeApp().androidApp().key(...)");
        }

        var sdk = settings_.androidSdk();
        var tools = new File(sdk, "build-tools/" + settings_.androidBuildTools());
        var platform = new File(sdk, "platforms/android-" + settings_.androidPlatform());
        var android = new File(platform, "android.jar");
        for (var part : List.of(new File(tools, "aapt2"), new File(tools, "d8"), new File(tools, "zipalign"), new File(tools, "apksigner"), android)) {
            if (!part.exists()) {
                throw new IOException("The Android SDK in " + sdk + " has no " + sdk.toPath().relativize(part.toPath()) + ": sdkmanager \"build-tools;" +
                                      settings_.androidBuildTools() + "\" \"platforms;android-" + settings_.androidPlatform() + "\"");
            }
        }
        var name = new SiteOnJvm(project_, settings_, null, Target.ANDROID).libraryName();
        var library = new File(NativeLibraryOperation.directory(project_, settings_, Target.ANDROID), "lib" + name + ".so");
        if (!library.isFile()) {
            throw new IllegalStateException("There's no library for android in " + library.getParent() + " yet: app-library android");
        }
        var out = new File(settings_.destinationDirectory(project_), "android-app");
        Commands.delete(out);
        out.mkdirs();
        var work = project_.workDirectory();

        var webkit = new File(out, "webkit/classes.jar");
        unzip(webkit(), "classes.jar", webkit);
        var classpath = new ArrayList<File>(List.of(android, webkit));
        app.libraries().forEach(jar -> classpath.add(file(jar)));

        // the resources, and the class that names them
        var linked = new File(out, "linked.apk");
        var generated = new File(out, "generated");
        var link = new ArrayList<>(List.of(new File(tools, "aapt2").getAbsolutePath(), "link", "-o", linked.getAbsolutePath(),
            "--manifest", file(app.manifest()).getAbsolutePath(), "-I", android.getAbsolutePath(), "--java", generated.getAbsolutePath(),
            "--version-name", null == app.versionName() ? project_.version().toString() : app.versionName(),
            "--version-code", String.valueOf(app.versionCode()), "--replace-version", "--min-sdk-version", String.valueOf(settings_.androidApi())));
        oldest(file(app.manifest()), settings_.androidApi());
        if (app.debuggable()) {
            link.add("--debug-mode");
        }
        if (app.resources() != null) {
            var compiled = new File(out, "resources.zip");
            Commands.output(work, List.of(new File(tools, "aapt2").getAbsolutePath(), "compile", "--dir", file(app.resources()).getAbsolutePath(), "-o", compiled.getAbsolutePath()));
            link.add(compiled.getAbsolutePath());
        }
        Commands.run(work, link);

        // the app's classes and rife2-native-app's, which are compiled for the app
        var sources = new ArrayList<File>(androidSources(new File(out, "rife2-native-app")));
        sources.addAll(javaFiles(generated));
        for (var directory : app.sources()) {
            sources.addAll(javaFiles(file(directory)));
        }
        var classes = new File(out, "classes");
        classes.mkdirs();
        var javac = new ArrayList<>(List.of(new File(System.getProperty("java.home"), "bin/javac").getAbsolutePath(), "--release", "17", "-nowarn",
            "-encoding", "UTF-8", "-cp", SiteOnJvm.joined(classpath), "-d", classes.getAbsolutePath()));
        sources.forEach(source -> javac.add(source.getAbsolutePath()));
        Commands.run(work, javac);
        var compiled = FileUtils.getFileList(classes).stream().filter(file -> file.endsWith(".class")).map(file -> new File(classes, file).getAbsolutePath()).toList();

        // Checked by themselves, since a library of Android has what it does
        // for a newer Android behind checks of its own.
        var own = new File(out, "own");
        own.mkdirs();
        var d8 = new File(tools, "d8").getAbsolutePath();
        var ownDex = new ArrayList<>(List.of(d8, "--lib", android.getAbsolutePath(), "--min-api", String.valueOf(settings_.androidApi()), "--output", own.getAbsolutePath()));
        for (var jar : classpath.subList(1, classpath.size())) {
            ownDex.addAll(List.of("--classpath", jar.getAbsolutePath()));
        }
        ownDex.addAll(compiled);
        Commands.output(work, ownDex);
        var dumped = Commands.output(work, List.of(new File(tools, "dexdump").getAbsolutePath(), "-d", new File(own, "classes.dex").getAbsolutePath()));
        var newer = new AndroidApiCheck(new File(platform, "data/api-versions.xml"), classpath.subList(1, classpath.size())).newer(dumped, settings_.androidApi());
        if (!newer.isEmpty()) {
            throw new IOException("The app uses what Android only has since after API " + settings_.androidApi() + ":\n  " + String.join("\n  ", newer));
        }

        var dex = new File(out, "dex");
        dex.mkdirs();
        var allDex = new ArrayList<>(List.of(d8, "--lib", android.getAbsolutePath(), "--min-api", String.valueOf(settings_.androidApi()), "--output", dex.getAbsolutePath()));
        allDex.addAll(compiled);
        classpath.subList(1, classpath.size()).forEach(jar -> allDex.add(jar.getAbsolutePath()));
        Commands.output(work, allDex);

        // an app that doesn't take its libraries out of itself when it's installed has them as they are
        var extracted = !Files.readString(file(app.manifest()).toPath()).replaceAll("\\s", "").matches("(?s).*extractNativeLibs=[\"']false[\"'].*");
        var packed = new File(out, "packed.apk");
        pack(linked, dex, library, extracted, packed);
        var aligned = new File(out, "aligned.apk");
        // a library that's loaded from the app as it is starts on a page of 16 KB, which some devices have
        Commands.output(work, List.of(new File(tools, "zipalign").getAbsolutePath(), "-f", "-P", "16", "4", packed.getAbsolutePath(), aligned.getAbsolutePath()));

        var keystore = file(app.keystore());
        var password = file(app.keystorePassword());
        if (!keystore.exists()) {
            key(keystore, password, app.keyAlias(), packageOf(file(app.manifest())));
        } else if (!password.isFile()) {
            throw new IOException(keystore + " is there without its password, which belongs in " + password);
        }
        apk_ = null == app.apk() ? new File(settings_.destinationDirectory(project_), "android/" + name + ".apk") : file(app.apk());
        apk_.getParentFile().mkdirs();
        Commands.output(work, List.of(new File(tools, "apksigner").getAbsolutePath(), "sign", "--ks", keystore.getAbsolutePath(), "--ks-pass", "file:" + password.getAbsolutePath(),
            "--ks-key-alias", app.keyAlias(), "--out", apk_.getAbsolutePath(), aligned.getAbsolutePath()));

        var said = apk_ + ", " + apk_.length() / (1024 * 1024) + " MB";
        var adb = new File(sdk, "platform-tools/adb");
        if (app.install() && adb.canExecute() && seen(adb, work)) {
            Commands.output(work, List.of(adb.getAbsolutePath(), "install", "-r", apk_.getAbsolutePath()));
            // the activity that the launcher starts, as the last line says
            var resolved = Commands.output(work, List.of(adb.getAbsolutePath(), "shell", "cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageOf(file(app.manifest())))).trim().split("\\s*\n");
            Commands.output(work, List.of(adb.getAbsolutePath(), "shell", "am", "start", "-W", "-n", resolved[resolved.length - 1].trim()));
            said = "Started " + said;
        } else {
            said = "Built " + said;
        }
        if (!silent()) {
            System.out.println(said);
        }
    }

    // An app that says it runs on an older Android than the library and the
    // classes were checked for would stop there; one that says nothing gets
    // that level from aapt2.
    static void oldest(File manifest, int api)
    throws IOException {
        var written = Files.readString(manifest.toPath()).replaceAll("(?s)<!--.*?-->", "");
        var said = java.util.regex.Pattern.compile("minSdkVersion\\s*=\\s*[\"'](\\d+)[\"']").matcher(written);
        if (said.find() && Integer.parseInt(said.group(1)) < api) {
            throw new IOException(manifest + " says the app runs on API " + said.group(1) + ", the library and the classes are for " + api +
                                  " and later: nativeApp().androidApi(...)");
        }
    }

    // relative to the project
    private File file(File file) {
        return file.isAbsolute() ? file : new File(project_.workDirectory(), file.getPath());
    }

    // whether adb sees an emulator or a device
    private static boolean seen(File adb, File directory) {
        try {
            // what adb says when there's none isn't of interest
            var process = new ProcessBuilder(adb.getAbsolutePath(), "get-state").directory(directory).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && said.equals("device");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // the library that the Android classes need beside Android's own, kept where every app on this machine finds it
    static File webkit()
    throws IOException, java.security.NoSuchAlgorithmException {
        var webkit = new File(System.getProperty("user.home"), ".rife2/native-app/androidx/webkit-" + WEBKIT_VERSION + ".aar");
        if (webkit.isFile()) {
            return webkit;
        }
        webkit.getParentFile().mkdirs();
        // a file of its own, another build can be fetching it too
        var partial = File.createTempFile("webkit-", ".partial", webkit.getParentFile());
        try (InputStream in = URI.create("https://dl.google.com/android/maven2/androidx/webkit/webkit/" + WEBKIT_VERSION + "/webkit-" + WEBKIT_VERSION + ".aar").toURL().openStream()) {
            Files.copy(in, partial.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(partial.toPath());
            throw e;
        }
        // under its name only when it's the one that this is for, so that half of it isn't taken for it later
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(partial.toPath())));
        if (!digest.equals(WEBKIT_SHA256)) {
            Files.delete(partial.toPath());
            throw new IOException("What Google has as androidx.webkit " + WEBKIT_VERSION + " isn't the one that this is for");
        }
        Files.move(partial.toPath(), webkit.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return webkit;
    }

    private static void unzip(File archive, String entry, File to)
    throws IOException {
        to.getParentFile().mkdirs();
        try (var zip = new ZipFile(archive)) {
            var found = zip.getEntry(entry);
            if (null == found) {
                throw new IOException(archive + " has no " + entry);
            }
            try (var in = zip.getInputStream(found)) {
                Files.copy(in, to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // rife2-native-app's classes for Android, as sources that the extension has
    static List<File> androidSources(File directory)
    throws IOException {
        var listed = Resources.class.getResourceAsStream("android/sources");
        if (null == listed) {
            throw new IOException("The extension has no Android classes");
        }
        var sources = new ArrayList<File>();
        var into = new File(directory, "rife/nativeapp/android");
        into.mkdirs();
        try (listed) {
            for (var name : new String(listed.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (name.isBlank()) {
                    continue;
                }
                try (var source = Resources.class.getResourceAsStream("android/" + name.trim())) {
                    if (null == source) {
                        throw new IOException("The extension has no " + name);
                    }
                    var file = new File(into, name.trim());
                    Files.copy(source, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    sources.add(file);
                }
            }
        }
        return sources;
    }

    private static List<File> javaFiles(File directory) {
        if (!directory.isDirectory()) {
            return List.of();
        }
        return FileUtils.getFileList(directory).stream().filter(file -> file.endsWith(".java")).sorted().map(file -> new File(directory, file)).toList();
    }

    // what aapt2 linked, with the classes and the library added
    static void pack(File linked, File dex, File library, boolean compressed, File packed)
    throws IOException {
        try (var zip = new ZipFile(linked); var out = new ZipOutputStream(new FileOutputStream(packed))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var copied = new ZipEntry(entry.getName());
                if (entry.getMethod() == ZipEntry.STORED) {
                    copied.setMethod(ZipEntry.STORED);
                    copied.setSize(entry.getSize());
                    copied.setCompressedSize(entry.getSize());
                    copied.setCrc(entry.getCrc());
                }
                out.putNextEntry(copied);
                try (var in = zip.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
            var dexes = dex.list((directory, name) -> name.endsWith(".dex"));
            for (var name : null == dexes ? new String[0] : dexes) {
                add(out, name, new File(dex, name), true);
            }
            add(out, "lib/arm64-v8a/" + library.getName(), library, compressed);
        }
    }

    private static void add(ZipOutputStream out, String name, File file, boolean compressed)
    throws IOException {
        var bytes = Files.readAllBytes(file.toPath());
        var entry = new ZipEntry(name);
        if (!compressed) {
            var crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    // the package that a manifest names
    static String packageOf(File manifest)
    throws IOException {
        var found = java.util.regex.Pattern.compile("<manifest[^>]*\\spackage\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(manifest.toPath()));
        if (!found.find()) {
            throw new IOException(manifest + " names no package");
        }
        return found.group(1);
    }

    // a key for the app, with a password of its own that only its owner can read
    private void key(File keystore, File password, String alias, String name)
    throws IOException, InterruptedException {
        keystore.getParentFile().mkdirs();
        if (!password.exists()) {
            password.getParentFile().mkdirs();
            var random = new byte[16];
            new SecureRandom().nextBytes(random);
            try {
                Files.createFile(password.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                // a file system without them
                Files.createFile(password.toPath());
            }
            Files.writeString(password.toPath(), HexFormat.of().formatHex(random) + "\n");
        }
        Commands.output(project_.workDirectory(), List.of(new File(System.getProperty("java.home"), "bin/keytool").getAbsolutePath(), "-genkeypair",
            "-keystore", keystore.getAbsolutePath(), "-storepass:file", password.getAbsolutePath(), "-alias", alias, "-keyalg", "RSA", "-keysize", "4096",
            "-validity", "20000", "-dname", "CN=" + name));
        System.out.println("Made the key that signs the app in " + keystore + ", with its password in " + password + ": keep both, " +
                           "Android only installs a newer build over one that the same key signed");
    }
}
