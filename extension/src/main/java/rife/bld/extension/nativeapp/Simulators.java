/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

// the simulators of this Mac, as simctl lists them
final class Simulators {
    private static final Pattern SYSTEM = Pattern.compile("^-- (iOS|visionOS|xrOS|watchOS|tvOS) ([0-9.]+) --$");
    private static final Pattern DEVICE = Pattern.compile("^\\s+(.+?) \\(([0-9A-F-]{36})\\) \\(([^)]+)\\)\\s*$");

    private Simulators() {
    }

    record Simulator(String name, String udid, boolean booted) {
    }

    // The simulator of that name for a target, or else when no name is
    // given, an iPhone for iOS and a Vision Pro for visionOS: the first
    // one that runs the newest system of the target.
    static Simulator find(Target target, String name, String listed)
    throws IOException {
        var vision = target == Target.VISION || target == Target.VISION_SIMULATOR;
        Simulator newest = null;
        int[] newestVersion = null;
        int[] version = null;
        var ofTarget = false;
        for (var line : listed.split("\n")) {
            var system = SYSTEM.matcher(line.trim());
            if (system.matches()) {
                ofTarget = vision ? !system.group(1).equals("iOS") && (system.group(1).equals("visionOS") || system.group(1).equals("xrOS")) : system.group(1).equals("iOS");
                version = numbers(system.group(2));
                continue;
            }
            var device = DEVICE.matcher(line);
            if (!ofTarget || !device.matches()) {
                continue;
            }
            var found = new Simulator(device.group(1), device.group(2), device.group(3).equals("Booted"));
            var wanted = name != null ? found.name().equals(name) : found.name().startsWith(vision ? "Apple Vision" : "iPhone");
            // the first of the newest system, since one of a name can be there for each
            if (wanted && (null == newestVersion || compare(version, newestVersion) > 0)) {
                newest = found;
                newestVersion = version;
            }
        }
        if (null == newest && name != null) {
            throw new IOException("No simulator is called " + name + ": xcrun simctl list devices available");
        }
        if (null == newest) {
            throw new IOException("There's no simulator for " + target.argument() + ": add one in Xcode's Devices and Simulators");
        }
        return newest;
    }

    static Simulator find(Target target, String name, File directory)
    throws IOException, InterruptedException {
        return find(target, name, Commands.output(directory, List.of("xcrun", "simctl", "list", "devices", "available")));
    }

    private static int[] numbers(String version) {
        return java.util.Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    private static int compare(int[] one, int[] other) {
        for (var i = 0; i < Math.max(one.length, other.length); i++) {
            var difference = Integer.compare(i < one.length ? one[i] : 0, i < other.length ? other[i] : 0);
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    // until it's done starting
    static void boot(String udid, File directory)
    throws IOException, InterruptedException {
        Commands.output(directory, List.of("xcrun", "simctl", "bootstatus", udid, "-b"));
    }
}
