/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * The GraalVM that builds the native libraries: the one of
 * {@code GRAALVM_HOME}, else the Community Edition that sdkman installs as
 * {@link #COMMUNITY}, since its license also lets an app be sold.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public final class GraalVm {
    public static final String COMMUNITY = "25.5.5.0+3-graalce";
    // what sdkman has as these for Linux on arm64, which the Android build runs in
    private static final Map<String, String> LINUX_CHECKSUMS = Map.of(
        "25.5.5.0+3-graalce", "41741c0650ae7564a78335f9e4fc353a902e6ff1d4140f2e4fabd1c55d5cce66");

    private final File home_;

    private GraalVm(File home) {
        home_ = home;
    }

    /**
     * Finds the GraalVM to build with.
     *
     * @throws IOException when there's none with native-image
     */
    public static GraalVm find()
    throws IOException {
        var home = System.getenv("GRAALVM_HOME");
        if (null == home || home.isBlank()) {
            var sdkman = System.getenv("SDKMAN_DIR");
            if (null == sdkman || sdkman.isBlank()) {
                sdkman = System.getProperty("user.home") + "/.sdkman";
            }
            home = sdkman + "/candidates/java/" + COMMUNITY;
        }
        // the real directory, since a link would be copied as a link for iOS, and written into
        var graalvm = new GraalVm(new File(home).getCanonicalFile());
        if (!graalvm.tool("native-image").canExecute()) {
            throw new IOException("No GraalVM with native-image in " + home + ": sdk install java " + COMMUNITY + ", or set GRAALVM_HOME to another one");
        }
        return graalvm;
    }

    public File home() {
        return home_;
    }

    File tool(String name) {
        return new File(home_, "bin/" + name);
    }

    /**
     * The version of the JDK inside it, which the JDK libraries that are
     * built for iOS and Android belong to.
     */
    public String runtime()
    throws IOException {
        for (var line : Files.readAllLines(new File(home_, "release").toPath())) {
            if (line.startsWith("JAVA_RUNTIME_VERSION=")) {
                return line.substring("JAVA_RUNTIME_VERSION=".length()).replace("\"", "");
            }
        }
        throw new IOException("The release file of " + home_ + " doesn't say its version");
    }

    // the name that sdkman has it under, for the same one for Linux
    String community()
    throws IOException {
        var name = home_.getCanonicalFile().getName();
        if (!LINUX_CHECKSUMS.containsKey(name)) {
            throw new IOException("The Android build only knows the GraalVM for Linux of " + String.join(", ", LINUX_CHECKSUMS.keySet()) +
                                  ", which have to be the one that builds for the Mac: " + home_ + " isn't");
        }
        return name;
    }

    String linuxChecksum()
    throws IOException {
        return LINUX_CHECKSUMS.get(community());
    }
}
