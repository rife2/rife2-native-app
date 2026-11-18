/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

/**
 * What a native library of a site is built for.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public enum Target {
    MAC("mac"),
    IOS("ios"),
    IOS_SIMULATOR("ios-sim"),
    VISION("vision"),
    VISION_SIMULATOR("vision-sim"),
    ANDROID("android");

    private final String name_;

    Target(String name) {
        name_ = name;
    }

    /**
     * The name of the target as a command takes it.
     *
     * @return the name
     * @since 1.11.0
     */
    public String argument() {
        return name_;
    }

    // what the app tells the site, see NativeApp.PLATFORM_PROPERTY
    String platform() {
        return switch (this) {
            case MAC -> "mac";
            case IOS, IOS_SIMULATOR -> "ios";
            case VISION, VISION_SIMULATOR -> "vision";
            case ANDROID -> "android";
        };
    }

    // built by native-image as for iOS, which visionOS isn't a target of but runs the same code as
    boolean isIos() {
        return this == IOS || this == IOS_SIMULATOR || this == VISION || this == VISION_SIMULATOR;
    }

    boolean isSimulator() {
        return this == IOS_SIMULATOR || this == VISION_SIMULATOR;
    }

    // the JDK libraries that native-jdk-libs builds for it, the ones for iOS on visionOS too
    String jdkLibraries() {
        return isSimulator() ? "iossim" : "ios";
    }

    // what retag.py writes into the object files of the JDK libraries for it
    String retagged() {
        return switch (this) {
            case IOS -> "ios";
            case IOS_SIMULATOR -> "iossim";
            case VISION -> "vision";
            case VISION_SIMULATOR -> "visionsim";
            default -> throw new IllegalStateException();
        };
    }

    String sdk() {
        return switch (this) {
            case IOS -> "iphoneos";
            case IOS_SIMULATOR -> "iphonesimulator";
            case VISION -> "xros";
            case VISION_SIMULATOR -> "xrsimulator";
            default -> throw new IllegalStateException();
        };
    }

    // the oldest system that the library runs on, which retag.py and Package.swift say as well
    String minimum() {
        return this == VISION || this == VISION_SIMULATOR ? "26.0" : "17.0";
    }

    String triple() {
        var system = this == VISION || this == VISION_SIMULATOR ? "xros" : "ios";
        return "arm64-apple-" + system + minimum() + (isSimulator() ? "-simulator" : "");
    }

    // what the Info.plist of a framework for it says
    String bundlePlatform() {
        return switch (this) {
            case IOS -> "iPhoneOS";
            case IOS_SIMULATOR -> "iPhoneSimulator";
            case VISION -> "XROS";
            case VISION_SIMULATOR -> "XRSimulator";
            default -> throw new IllegalStateException();
        };
    }

    /**
     * The target that a command names.
     *
     * @param name the name of the target
     * @return the target
     * @throws IllegalArgumentException for a name of no target
     * @since 1.11.0
     */
    public static Target from(String name) {
        for (var target : values()) {
            if (target.name_.equals(name)) {
                return target;
            }
        }
        throw new IllegalArgumentException("No target is called " + name + ", these are: mac, ios, ios-sim, vision, vision-sim, android");
    }
}
