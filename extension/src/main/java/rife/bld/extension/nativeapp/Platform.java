/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

// the native libraries are built with Xcode's tools and the NDK of a Mac
final class Platform {
    private Platform() {
    }

    static void requireMac() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("mac")) {
            throw new IllegalStateException("The native libraries of rife2-native-app are built on a Mac, with Xcode, GraalVM, and for Android the NDK and Docker");
        }
    }
}
