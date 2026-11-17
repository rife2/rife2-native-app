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
     */
    public String argument() {
        return name_;
    }

    boolean isIos() {
        return this == IOS || this == IOS_SIMULATOR;
    }

    /**
     * The target that a command names.
     *
     * @throws IllegalArgumentException for a name of no target
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
