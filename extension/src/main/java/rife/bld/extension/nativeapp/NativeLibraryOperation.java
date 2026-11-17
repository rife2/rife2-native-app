/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

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
 * native-image, with the C functions of rife2-native-app that an app calls:
 * a framework for macOS, iOS and the iOS simulator, a shared library for
 * Android. It goes in {@code build/native/<target>}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeLibraryOperation extends AbstractOperation<NativeLibraryOperation> {
    private NativeAppProject project_;
    private Target target_;

    public NativeLibraryOperation fromProject(NativeAppProject project) {
        project_ = project;
        return this;
    }

    public NativeLibraryOperation target(Target target) {
        target_ = target;
        return this;
    }

    /**
     * The directory that the library of a target is built in.
     */
    public static File directory(NativeAppProject project, Target target) {
        return new File(project.buildDirectory(), "native/" + target.argument());
    }

    @Override
    public void execute()
    throws Exception {
        if (null == project_) throw new IllegalStateException("fromProject() wasn't given a project");
        if (null == target_) throw new IllegalStateException("target() wasn't given a target");

        var graalvm = GraalVm.find();
        var site = new SiteOnJvm(project_, project_.nativeApp(), graalvm);
        var out = directory(project_, target_);
        Commands.delete(out);
        out.mkdirs();
        var classpath = site.classpath(site.stage(out));
        var configuration = new File(out, "config");
        site.walk(classpath, configuration, new File(out, "jvm-data"));

        var built = switch (target_) {
            case MAC -> mac(graalvm, site, classpath, configuration, out);
            case IOS, IOS_SIMULATOR -> ios(graalvm, site, classpath, configuration, out);
            case ANDROID -> new Android(project_, graalvm).library(site, classpath, configuration, out);
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

    private List<String> nativeImage(File tool, List<File> classpath, File configuration, File library) {
        var command = new ArrayList<>(List.of(tool.getAbsolutePath(), "--no-fallback", "--shared"));
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
        Commands.run(project_.workDirectory(), nativeImage(graalvm.tool("native-image"), classpath, configuration, new File(out, "lib" + name)));
        removeDesktopLibraries(out, name);

        // as a Mac has frameworks: with its versions in a folder, and links to the one that's current
        var framework = new File(out, name + ".framework");
        var version = new File(framework, "Versions/A");
        new File(version, "Resources").mkdirs();
        var binary = new File(version, name);
        Files.move(new File(out, "lib" + name + ".dylib").toPath(), binary.toPath());
        Commands.run(out, List.of("install_name_tool", "-id", "@rpath/" + name + ".framework/Versions/A/" + name, binary.getAbsolutePath()));
        FileUtils.writeString(Apple.infoPlist(site, project_.version().toString(), null, null), new File(version, "Resources/Info.plist"));
        Apple.privacyManifest(project_.nativeApp(), binary, new File(version, "Resources/PrivacyInfo.xcprivacy"));
        Files.createSymbolicLink(new File(framework, "Versions/Current").toPath(), new File("A").toPath());
        Files.createSymbolicLink(new File(framework, name).toPath(), new File("Versions/Current/" + name).toPath());
        Files.createSymbolicLink(new File(framework, "Resources").toPath(), new File("Versions/Current/Resources").toPath());
        Commands.run(out, List.of("codesign", "-f", "-s", "-", framework.getAbsolutePath()));
        return framework;
    }

    // native-image takes iOS as a target with the static libraries of the JDK for it
    private File ios(GraalVm graalvm, SiteOnJvm site, List<File> classpath, File configuration, File out)
    throws Exception {
        var simulator = target_ == Target.IOS_SIMULATOR;
        var settings = project_.nativeApp();
        var triple = "arm64-apple-ios" + settings.minimumIos() + (simulator ? "-simulator" : "");
        var sdk = Commands.output(out, List.of("xcrun", "--sdk", simulator ? "iphonesimulator" : "iphoneos", "--show-sdk-path")).trim();
        var prepared = Apple.graalvmForIos(graalvm, settings.jdkLibraries(), target_, out);

        var name = site.libraryName();
        var command = nativeImage(new File(prepared, "bin/native-image"), classpath, configuration, new File(out, "lib" + name));
        command.addAll(3, List.of("--target=ios-aarch64", "-H:+UnlockExperimentalVMOptions",
            "-H:NativeLinkerOption=-target", "-H:NativeLinkerOption=" + triple,
            "-H:NativeLinkerOption=-isysroot", "-H:NativeLinkerOption=" + sdk));
        Commands.run(project_.workDirectory(), command);
        removeDesktopLibraries(out, name);

        // the App Store takes no library that's a file of its own, only a framework
        var framework = new File(out, name + ".framework");
        framework.mkdirs();
        var binary = new File(framework, name);
        Files.move(new File(out, "lib" + name + ".dylib").toPath(), binary.toPath());
        Commands.run(out, List.of("install_name_tool", "-id", "@rpath/" + name + ".framework/" + name, binary.getAbsolutePath()));
        FileUtils.writeString(Apple.infoPlist(site, project_.version().toString(), simulator ? "iPhoneSimulator" : "iPhoneOS", settings.minimumIos()),
            new File(framework, "Info.plist"));
        Apple.privacyManifest(settings, binary, new File(framework, "PrivacyInfo.xcprivacy"));
        Commands.run(out, List.of("codesign", "-f", "-s", "-", framework.getAbsolutePath()));
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
