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
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the site of a project into a native library with GraalVM's
 * native-image, with the C functions of RIFE2's {@code rife.nativeapp} that an app calls:
 * a framework for macOS, iOS and the iOS simulator, a shared library for
 * Android. It goes in {@code build/native/<target>}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeLibraryOperation extends AbstractOperation<NativeLibraryOperation> {
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
    public NativeLibraryOperation fromProject(WebProject project) {
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
    public NativeLibraryOperation settings(NativeAppSettings settings) {
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
    public NativeLibraryOperation target(Target target) {
        target_ = target;
        return this;
    }

    /**
     * The directory that the library of a target is built in.
     *
     * @param project the project of the site
     * @param target the target
     * @return the directory
     * @since 1.11.0
     */
    public static File directory(WebProject project, Target target) {
        return new File(project.buildDirectory(), "native/" + target.argument());
    }

    @Override
    public void execute()
    throws Exception {
        if (null == project_) throw new IllegalStateException("fromProject() wasn't given a project");
        if (null == settings_) throw new IllegalStateException("settings() weren't given");
        if (null == target_) throw new IllegalStateException("target() wasn't given a target");

        var graalvm = GraalVm.find();
        var site = new SiteOnJvm(project_, settings_, graalvm, target_);
        site.requirePrecompiledTemplates();
        var out = directory(project_, target_);
        Commands.delete(out);
        out.mkdirs();
        var classpath = site.classpath(site.stage(out));
        var configuration = new File(out, "config");
        site.walk(classpath, configuration, new File(out, "jvm-data"));

        Platform.requireMac();
        var built = switch (target_) {
            case MAC -> mac(graalvm, site, classpath, configuration, out);
            case IOS, IOS_SIMULATOR, VISION, VISION_SIMULATOR -> ios(graalvm, site, classpath, configuration, out);
            case ANDROID -> new Android(project_, settings_, graalvm).library(site, classpath, configuration, out);
        };
        if (!silent()) {
            System.out.println("Built " + built.getPath() + ", " + size(built));
        }
    }

    private static String size(File built) {
        var file = built.isDirectory() ? FileUtils.getFileList(built).stream().map(name -> new File(built, name))
            .filter(File::isFile).max((one, other) -> Long.compare(one.length(), other.length())).orElse(built) : built;
        return file.length() / (1024 * 1024) + " MB";
    }

    static List<String> nativeImage(File tool, List<String> options, List<File> classpath, File configuration, File library) {
        var command = new ArrayList<>(List.of(tool.getAbsolutePath(), "--shared", "-H:+UnlockExperimentalVMOptions"));
        command.addAll(options);
        if (configuration.isDirectory()) {
            command.add("-H:ConfigurationFileDirectories=" + configuration.getAbsolutePath());
        }
        command.addAll(List.of("-cp", SiteOnJvm.joined(classpath), "-o", library.getAbsolutePath()));
        return command;
    }

    // a Mac is what GraalVM builds for by itself, with the libraries of the JDK that it ships
    private File mac(GraalVm graalvm, SiteOnJvm site, List<File> classpath, File configuration, File out)
    throws Exception {
        var name = site.libraryName();
        Commands.run(project_.workDirectory(), nativeImage(graalvm.tool("native-image"), List.of(), classpath, configuration, new File(out, "lib" + name)));
        removeDesktopLibraries(out, name);

        // as a Mac has frameworks: with its versions in a folder, and links to the one that's current
        var framework = new File(out, name + ".framework");
        var version = new File(framework, "Versions/A");
        new File(version, "Resources").mkdirs();
        var binary = new File(version, name);
        Files.move(new File(out, "lib" + name + ".dylib").toPath(), binary.toPath());
        Commands.run(out, List.of("install_name_tool", "-id", "@rpath/" + name + ".framework/Versions/A/" + name, binary.getAbsolutePath()));
        FileUtils.writeString(Apple.infoPlist(site, project_.version().toString(), null, null), new File(version, "Resources/Info.plist"));
        Apple.privacyManifest(settings_, binary, new File(version, "Resources/PrivacyInfo.xcprivacy"));
        Files.createSymbolicLink(new File(framework, "Versions/Current").toPath(), new File("A").toPath());
        Files.createSymbolicLink(new File(framework, name).toPath(), new File("Versions/Current/" + name).toPath());
        Files.createSymbolicLink(new File(framework, "Resources").toPath(), new File("Versions/Current/Resources").toPath());
        Commands.run(out, List.of("codesign", "-f", "-s", "-", framework.getAbsolutePath()));
        return framework;
    }

    // native-image takes iOS as a target with the static libraries of the JDK for it
    private File ios(GraalVm graalvm, SiteOnJvm site, List<File> classpath, File configuration, File out)
    throws Exception {
        var settings = settings_;
        var triple = target_.triple();
        var sdk = Commands.output(out, List.of("xcrun", "--sdk", target_.sdk(), "--show-sdk-path")).trim();
        var prepared = Apple.graalvmForIos(graalvm, settings.jdkLibraries(), target_, out);

        var name = site.libraryName();
        var command = nativeImage(new File(prepared, "bin/native-image"), List.of("--target=ios-aarch64",
            "-H:NativeLinkerOption=-target", "-H:NativeLinkerOption=" + triple,
            "-H:NativeLinkerOption=-isysroot", "-H:NativeLinkerOption=" + sdk), classpath, configuration, new File(out, "lib" + name));
        Commands.run(project_.workDirectory(), command);
        removeDesktopLibraries(out, name);

        // the App Store takes no library that's a file of its own, only a framework
        var framework = new File(out, name + ".framework");
        framework.mkdirs();
        var binary = new File(framework, name);
        Files.move(new File(out, "lib" + name + ".dylib").toPath(), binary.toPath());
        Commands.run(out, List.of("install_name_tool", "-id", "@rpath/" + name + ".framework/" + name, binary.getAbsolutePath()));
        FileUtils.writeString(Apple.infoPlist(site, project_.version().toString(), target_.bundlePlatform(), target_.minimum()),
            new File(framework, "Info.plist"));
        Apple.privacyManifest(settings, binary, new File(framework, "PrivacyInfo.xcprivacy"));
        Commands.run(out, List.of("codesign", "-f", "-s", "-", framework.getAbsolutePath()));
        Apple.xcframework(project_, name);
        return framework;
    }

    // GraalVM puts the desktop libraries of the JDK beside what it builds, which the site doesn't use
    private static void removeDesktopLibraries(File out, String name)
    throws IOException {
        var files = out.listFiles((directory, file) -> file.startsWith("lib") && file.endsWith(".dylib") && !file.equals("lib" + name + ".dylib"));
        if (files != null) {
            for (var file : files) {
                Files.delete(file.toPath());
            }
        }
    }
}
