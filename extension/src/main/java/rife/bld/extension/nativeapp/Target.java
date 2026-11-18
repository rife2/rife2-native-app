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
            case ANDROID -> "android";
        };
    }

    boolean isIos() {
        return this == IOS || this == IOS_SIMULATOR;
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
        throw new IllegalArgumentException("No target is called " + name + ", these are: mac, ios, ios-sim, android");
    }
}
