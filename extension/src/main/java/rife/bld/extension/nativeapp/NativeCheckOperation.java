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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Checks that the native library of a target answers the addresses of the
 * build exactly as the site does on the JVM: the status, the size and a
 * CRC-32 of each answer. On a Mac directly, in a simulator, and on the
 * emulator or the device that adb sees, each through {@code ask.c}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class NativeCheckOperation extends AbstractOperation<NativeCheckOperation> {
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
    public NativeCheckOperation fromProject(WebProject project) {
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
    public NativeCheckOperation settings(NativeAppSettings settings) {
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
    public NativeCheckOperation target(Target target) {
        target_ = target;
        return this;
    }

    @Override
    public void execute()
    throws Exception {
        if (null == project_) throw new IllegalStateException("fromProject() wasn't given a project");
        if (null == settings_) throw new IllegalStateException("settings() weren't given");
        if (null == target_) throw new IllegalStateException("target() wasn't given a target");
        var settings = settings_;
        if (settings.addresses().isEmpty()) {
            throw new IllegalStateException("The build names no addresses to check: nativeApp().addresses(...)");
        }
        if (target_ == Target.IOS || target_ == Target.VISION) {
            throw new IllegalArgumentException("A library for devices is checked in the simulator: app-check " + target_.argument() + "-sim");
        }

        Platform.requireMac();
        var graalvm = GraalVm.find();
        var site = new SiteOnJvm(project_, settings, graalvm, target_);
        var out = NativeLibraryOperation.directory(project_, settings_, target_);
        var name = site.libraryName();
        if (!new File(out, target_ == Target.ANDROID ? "lib" + name + ".so" : name + ".framework").exists()) {
            throw new IllegalStateException("There's no library for " + target_.argument() + " in " + out + " yet: app-library " + target_.argument());
        }
        var check = new File(out, "check");
        Commands.delete(check);
        check.mkdirs();

        var expected = site.ask(site.classpath(new File(out, "static")), new File(check, "jvm-data"), null);
        var got = switch (target_) {
            case MAC -> mac(site, out, check);
            case IOS_SIMULATOR, VISION_SIMULATOR -> simulator(site, out, check);
            case ANDROID -> android(graalvm, site, out, check);
            case IOS, VISION -> throw new IllegalStateException();
        };
        if (!expected.equals(got)) {
            var said = new StringBuilder("The native library and the JVM answer differently, as the address, the status, the size and the CRC-32:\n");
            for (var i = 0; i < Math.max(expected.size(), got.size()); i++) {
                var jvm = i < expected.size() ? expected.get(i) : "";
                var library = i < got.size() ? got.get(i) : "";
                if (!jvm.equals(library)) {
                    said.append("  JVM:     ").append(jvm).append("\n  library: ").append(library).append('\n');
                }
            }
            throw new IOException(said.toString());
        }
        if (!silent()) {
            System.out.println("The library for " + target_.argument() + " answers " + expected.size() + " addresses as the JVM does");
        }
    }

    private List<String> arguments(SiteOnJvm site, File data) {
        var arguments = new ArrayList<String>();
        for (var property : site.properties(data).entrySet()) {
            arguments.add("-D" + property.getKey() + "=" + property.getValue());
        }
        arguments.addAll(settings_.addresses());
        return arguments;
    }


    private List<String> mac(SiteOnJvm site, File out, File check)
    throws Exception {
        var ask = new File(check, "ask");
        Commands.run(check, List.of("clang", "-O2", "-o", ask.getAbsolutePath(), Resources.extract("ask.c", check).getAbsolutePath(),
            "-I" + out.getAbsolutePath(), "-F" + out.getAbsolutePath(), "-framework", site.libraryName(), "-lz",
            "-Wl,-rpath," + out.getAbsolutePath()));
        var data = new File(check, "native-data");
        data.mkdirs();
        var command = new ArrayList<>(List.of(ask.getAbsolutePath()));
        command.addAll(arguments(site, data));
        return site.answers(Commands.output(check, command));
    }

    // a simulator runs a program of this Mac's disk, built for it, without an app around it
    private List<String> simulator(SiteOnJvm site, File out, File check)
    throws Exception {
        var settings = settings_;
        var udid = simulator(target_ == Target.VISION_SIMULATOR ? settings.visionSimulator() : settings.simulator(), check);
        // one that this boots is shut down again, one that was booted stays
        var booted = Commands.output(check, List.of("xcrun", "simctl", "list", "devices", "booted")).contains(udid);
        Commands.output(check, List.of("xcrun", "simctl", "bootstatus", udid, "-b"));
        try {
            return simulator(site, out, check, udid, settings);
        } finally {
            if (!booted) {
                Commands.output(check, List.of("xcrun", "simctl", "shutdown", udid));
            }
        }
    }

    private List<String> simulator(SiteOnJvm site, File out, File check, String udid, NativeAppSettings settings)
    throws Exception {
        var sdk = Commands.output(check, List.of("xcrun", "--sdk", target_.sdk(), "--show-sdk-path")).trim();
        var ask = new File(check, "ask");
        Commands.run(check, List.of("xcrun", "--sdk", target_.sdk(), "clang", "-O2", "-target", target_.triple(),
            "-isysroot", sdk, "-o", ask.getAbsolutePath(), Resources.extract("ask.c", check).getAbsolutePath(),
            "-I" + out.getAbsolutePath(), "-F" + out.getAbsolutePath(), "-framework", site.libraryName(), "-lz",
            "-Wl,-rpath," + out.getAbsolutePath()));
        var data = new File(check, "native-data");
        data.mkdirs();
        var command = new ArrayList<>(List.of("xcrun", "simctl", "spawn", udid, ask.getAbsolutePath()));
        command.addAll(arguments(site, data));
        return site.answers(Commands.output(check, command));
    }

    private static String simulator(String name, File directory)
    throws Exception {
        for (var line : Commands.output(directory, List.of("xcrun", "simctl", "list", "devices", "available")).split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith(name + " (")) {
                var udid = trimmed.replaceAll(".*\\(([0-9A-F-]{36})\\).*", "$1");
                if (udid.length() == 36) {
                    return udid;
                }
            }
        }
        throw new IOException("No simulator is called " + name + ": nativeApp().simulator(...)");
    }

    // a program and the library go on the emulator or the device that adb sees
    private List<String> android(GraalVm graalvm, SiteOnJvm site, File out, File check)
    throws Exception {
        var android = new Android(project_, settings_, graalvm);
        var adb = android.adb().getAbsolutePath();
        var name = site.libraryName();
        var ask = new File(check, "ask");
        Commands.run(check, List.of(android.compiler().getAbsolutePath(), "-O2", "-o", ask.getAbsolutePath(), Resources.extract("ask.c", check).getAbsolutePath(),
            "-I" + out.getAbsolutePath(), "-L" + out.getAbsolutePath(), "-l" + name, "-lz"));
        var there = "/data/local/tmp/rife2-native-app";
        try {
            Commands.output(check, List.of(adb, "shell", "rm -rf " + there + " && mkdir -p " + there + "/data"));
            Commands.output(check, List.of(adb, "push", new File(out, "lib" + name + ".so").getAbsolutePath(), ask.getAbsolutePath(), there + "/"));
            var arguments = new StringBuilder();
            for (var argument : arguments(site, new File(there + "/data"))) {
                arguments.append(" '").append(argument.replace("'", "'\\''")).append('\'');
            }
            return site.answers(Commands.output(check, Map.of(), List.of(adb, "shell", "cd " + there + " && chmod 755 ask && LD_LIBRARY_PATH=. ./ask" + arguments)));
        } finally {
            // a failure to tidy up doesn't take the place of what happened
            try {
                Commands.output(check, List.of(adb, "shell", "rm -rf " + there));
            } catch (Exception e) {
                System.err.println("The check's files stay in " + there + ": " + e.getMessage());
            }
        }
    }
}
