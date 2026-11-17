/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.bld.extension.NativeAppProject;
import rife.tools.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// what building and checking for Android needs
final class Android {
    private final NativeAppProject project_;
    private final GraalVm graalvm_;

    Android(NativeAppProject project, GraalVm graalvm) {
        project_ = project;
        graalvm_ = graalvm;
    }

    File ndk() {
        return new File(project_.nativeApp().androidSdk(), "ndk/" + project_.nativeApp().androidNdk() + "/toolchains/llvm/prebuilt/darwin-x86_64");
    }

    File compiler()
    throws IOException {
        var compiler = new File(ndk(), "bin/aarch64-linux-android" + project_.nativeApp().androidApi() + "-clang");
        if (!compiler.canExecute()) {
            throw new IOException("No NDK " + project_.nativeApp().androidNdk() + " in " + project_.nativeApp().androidSdk() +
                                  ": sdkmanager \"ndk;" + project_.nativeApp().androidNdk() + "\"");
        }
        return compiler;
    }

    File adb()
    throws IOException {
        var adb = new File(project_.nativeApp().androidSdk(), "platform-tools/adb");
        if (!adb.canExecute()) {
            throw new IOException("No adb in " + project_.nativeApp().androidSdk() + ": sdkmanager platform-tools");
        }
        return adb;
    }

    // the libraries of the JDK for Android and the answers about its C types, which native-jdk-libs makes
    File libraries()
    throws IOException {
        var libraries = new File(project_.nativeApp().jdkLibraries(), graalvm_.runtime() + "/android");
        if (!new File(libraries, "static").isDirectory() || !new File(libraries, "cap").isDirectory()) {
            throw new IOException("The JDK libraries for Android of the JDK " + graalvm_.runtime() + " aren't in " + libraries +
                                  " yet: ./bld native-jdk-libs android");
        }
        return libraries;
    }

    // native-image compiles the Java classes of the JDK that it runs on, and
    // Android has those of Linux, so it runs in a Linux container, which
    // stops at the object file; the NDK links it on this Mac
    File library(SiteOnJvm site, List<File> classpath, File configuration, File out)
    throws Exception {
        var compiler = compiler();
        var libraries = libraries();
        var name = site.libraryName();

        // the container sees the folder of the build, so it gets copies of what's elsewhere
        var inside = new File(out, "classpath");
        inside.mkdirs();
        var paths = new ArrayList<String>();
        for (var entry : classpath) {
            if (entry.isDirectory()) {
                paths.add("/work/" + out.toPath().relativize(entry.toPath()));
            } else {
                FileUtils.copy(entry, new File(inside, entry.getName()));
                paths.add("/work/classpath/" + entry.getName());
            }
        }

        var image = "rife2-native-app-android:" + graalvm_.community().replace('+', '_');
        var docker = new File(out, "docker");
        docker.mkdirs();
        FileUtils.copy(Resources.extract("Dockerfile", out), new File(docker, "Dockerfile"));
        Commands.output(out, List.of("docker", "build", "-q", "-t", image, "--build-arg", "GRAALVM=" + graalvm_.community(),
            "--build-arg", "GRAALVM_SHA256=" + graalvm_.linuxChecksum(), docker.getAbsolutePath()));
        var configured = configuration.isDirectory() ? "-H:ConfigurationFileDirectories=/work/config " : "";
        // what native-image wants to know of Android's C types was answered
        // on an Android, and it leaves the linking to the NDK
        Commands.run(out, List.of("docker", "run", "--rm", "-v", out.getAbsolutePath() + ":/work", "-v", libraries.getAbsolutePath() + ":/jdk:ro",
            "-w", "/work", image, "bash", "-c",
            "set -e; " +
            "mkdir -p /opt/graalvm/lib/static/android-aarch64/bionic /opt/graalvm/lib/svm/clibraries/android-aarch64/bionic; " +
            "cp /jdk/static/*.a /opt/graalvm/lib/static/android-aarch64/bionic/; " +
            "cp /jdk/clibraries/*.a /opt/graalvm/lib/svm/clibraries/android-aarch64/bionic/; " +
            "native-image --shared --target=android-aarch64 --libc=bionic -H:+UnlockExperimentalVMOptions -H:-CheckToolchain " +
            "-H:-UseContainerSupport -H:+UseCAPCache -H:CAPCacheDir=/jdk/cap -H:+ExitAfterRelocatableImageWrite -H:TempDirectory=/work/tmp " +
            configured + "-cp '" + String.join(":", paths) + "' -o /work/lib" + name));
        File object;
        try (var found = Files.walk(new File(out, "tmp").toPath())) {
            object = found.filter(path -> path.getFileName().toString().equals("lib" + name + ".o")).findFirst()
                .orElseThrow(() -> new IOException("native-image left no object file to link")).toFile();
        }

        // the bridge between Android's Java and the site's goes into the same library
        var bridge = new File(out, "rife2_native_app.o");
        Commands.run(out, List.of(compiler.getAbsolutePath(), "-c", "-fPIC", "-O2", "-Wall", "-Wextra", "-Wno-unused-parameter", "-Werror",
            "-o", bridge.getAbsolutePath(), Resources.extract("rife2_native_app.c", out).getAbsolutePath()));
        var library = new File(out, "lib" + name + ".so");
        Commands.run(out, List.of(compiler.getAbsolutePath(), "-shared", "-o", library.getAbsolutePath(), object.getAbsolutePath(), bridge.getAbsolutePath(),
            "-Wl,--no-undefined", "-Wl,--gc-sections", "-Wl,--start-group",
            "-L" + new File(libraries, "static").getAbsolutePath(), "-L" + new File(libraries, "clibraries").getAbsolutePath(),
            "-ljava", "-lnio", "-lnet", "-lzip", "-lextnet", "-lmanagement", "-lmanagement_ext", "-lprefs", "-ljaas", "-ljvm", "-llibchelper",
            "-Wl,--end-group", "-lz", "-ldl", "-llog", "-lm"));
        Commands.delete(new File(out, "tmp"));
        Commands.delete(inside);
        return library;
    }
}
