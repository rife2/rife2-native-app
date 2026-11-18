/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.tools.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// what the frameworks for Apple's systems need
final class Apple {
    private Apple() {
    }

    static String infoPlist(SiteOnJvm site, String version, String platform, String minimum) {
        var name = site.libraryName();
        var plist = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleDevelopmentRegion</key><string>en</string>
            """);
        plist.append("    <key>CFBundleExecutable</key><string>").append(name).append("</string>\n");
        plist.append("    <key>CFBundleIdentifier</key><string>").append(site.bundleIdentifier()).append("</string>\n");
        plist.append("    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>\n");
        plist.append("    <key>CFBundleName</key><string>").append(name).append("</string>\n");
        plist.append("    <key>CFBundlePackageType</key><string>FMWK</string>\n");
        // a version of numbers, which is what a bundle takes
        var numbers = version.replaceAll("[^0-9.].*$", "").replaceAll("\\.+$", "");
        plist.append("    <key>CFBundleShortVersionString</key><string>").append(numbers.isEmpty() ? "1.0" : numbers).append("</string>\n");
        plist.append("    <key>CFBundleVersion</key><string>1</string>\n");
        if (platform != null) {
            plist.append("    <key>CFBundleSupportedPlatforms</key><array><string>").append(platform).append("</string></array>\n");
        }
        if (minimum != null) {
            plist.append("    <key>MinimumOSVersion</key><string>").append(minimum).append("</string>\n");
        }
        plist.append("</dict>\n</plist>\n");
        return plist.toString();
    }

    // Xcode links one framework for the simulator and devices, made of the
    // frameworks that were built for each, the one that's there when only
    // one was
    static void xcframework(rife.bld.WebProject project, String name)
    throws IOException, InterruptedException {
        var xcframework = new File(project.buildDirectory(), "native/" + name + ".xcframework");
        delete(xcframework);
        var command = new java.util.ArrayList<>(List.of("xcodebuild", "-create-xcframework"));
        for (var target : List.of(Target.IOS, Target.IOS_SIMULATOR, Target.VISION, Target.VISION_SIMULATOR)) {
            var framework = new File(NativeLibraryOperation.directory(project, target), name + ".framework");
            if (framework.isDirectory()) {
                command.addAll(List.of("-framework", framework.getAbsolutePath()));
            }
        }
        command.addAll(List.of("-output", xcframework.getAbsolutePath()));
        Commands.output(xcframework.getParentFile(), command);
    }

    private static void delete(File directory)
    throws IOException {
        Commands.delete(directory);
    }

    // Apple's required reason APIs: the symbols that a binary imports for
    // each, and the reason that a site inside an app has for it
    private static final Map<String, Set<String>> SYMBOLS = new LinkedHashMap<>();
    private static final Map<String, String> REASONS = Map.of(
        // the files of the app itself
        "FileTimestamp", "C617.1",
        // time measured inside the app
        "SystemBootTime", "35F9.1",
        // whether there's room to write a file
        "DiskSpace", "E174.1",
        // what the app itself keeps
        "UserDefaults", "CA92.1");

    static {
        SYMBOLS.put("FileTimestamp", Set.of("_stat", "_fstat", "_lstat", "_fstatat", "_getattrlist", "_fgetattrlist", "_getattrlistat",
            "_getattrlistbulk", "_NSFileCreationDate", "_NSFileModificationDate", "_NSURLContentModificationDateKey", "_NSURLCreationDateKey"));
        SYMBOLS.put("SystemBootTime", Set.of("_mach_absolute_time"));
        SYMBOLS.put("DiskSpace", Set.of("_statfs", "_statvfs", "_fstatfs", "_fstatvfs", "_getattrlist", "_fgetattrlist", "_getattrlistat",
            "_NSFileSystemFreeSize", "_NSFileSystemSize", "_NSURLVolumeAvailableCapacityKey", "_NSURLVolumeAvailableCapacityForImportantUsageKey",
            "_NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "_NSURLVolumeTotalCapacityKey"));
        SYMBOLS.put("UserDefaults", Set.of("_OBJC_CLASS_$_NSUserDefaults"));
    }

    // The privacy manifest of the framework, the build's own or one that
    // declares what the binary uses, so that App Store Connect never turns a
    // build away for one that's left out and none is claimed that isn't used.
    static void privacyManifest(NativeAppSettings settings, File binary, File manifest)
    throws IOException, InterruptedException {
        if (settings.privacyManifest() != null) {
            FileUtils.copy(settings.privacyManifest(), manifest);
            return;
        }
        var imported = new TreeSet<>(Arrays.asList(Commands.output(binary.getParentFile(), List.of("nm", "-u", binary.getAbsolutePath())).split("\\s+")));
        var plist = new StringBuilder("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>NSPrivacyAccessedAPITypes</key>
                <array>
            """);
        for (var category : SYMBOLS.entrySet()) {
            if (category.getValue().stream().anyMatch(imported::contains)) {
                plist.append("        <dict>\n");
                plist.append("            <key>NSPrivacyAccessedAPIType</key><string>NSPrivacyAccessedAPICategory").append(category.getKey()).append("</string>\n");
                plist.append("            <key>NSPrivacyAccessedAPITypeReasons</key><array><string>").append(REASONS.get(category.getKey())).append("</string></array>\n");
                plist.append("        </dict>\n");
            }
        }
        plist.append("""
                </array>
                <key>NSPrivacyCollectedDataTypes</key>
                <array/>
                <key>NSPrivacyTracking</key>
                <false/>
                <key>NSPrivacyTrackingDomains</key>
                <array/>
            </dict>
            </plist>
            """);
        FileUtils.writeString(plist.toString(), manifest);
    }

    // A copy of GraalVM with the JDK libraries for iOS where native-image
    // looks for them: the ones that native-jdk-libs built, or else stand-ins,
    // the macOS libraries with the platform of their object files rewritten,
    // which works since both run the same processor instructions. The copy
    // shares its files with the original until they change.
    static File graalvmForIos(GraalVm graalvm, File jdkLibraries, Target target, File out)
    throws IOException, InterruptedException {
        var platform = target.retagged();
        var runtime = graalvm.runtime();
        var built = new File(jdkLibraries, runtime + "/" + target.jdkLibraries());
        var prepared = new File(out.getParentFile(), "graalvm-" + platform);
        // what native-jdk-libs left complete, it moves them in place at its end
        var libraries = new File(built, "clibraries/libjvm.a").isFile() ? "built " + new File(built, "static").lastModified() : "stand-ins";
        var stamp = new File(prepared, "ios-libraries");
        var release = new File(prepared, "release");
        if (!stamp.isFile() || !FileUtils.readString(stamp).equals(libraries) ||
            !release.isFile() || !Files.readString(release.toPath()).equals(Files.readString(new File(graalvm.home(), "release").toPath()))) {
            Commands.delete(prepared);
            prepared.getParentFile().mkdirs();
            Commands.run(out, List.of("cp", "-Rc", graalvm.home().getAbsolutePath(), prepared.getAbsolutePath()));
            var statics = new File(prepared, "lib/static/ios-aarch64");
            var clibraries = new File(prepared, "lib/svm/clibraries/ios-aarch64");
            statics.mkdirs();
            clibraries.mkdirs();
            var retag = Resources.extract("retag.py", out);
            if (!libraries.equals("stand-ins") && platform.equals(target.jdkLibraries())) {
                FileUtils.copyDirectory(new File(built, "static"), statics);
                FileUtils.copyDirectory(new File(built, "clibraries"), clibraries);
            } else if (!libraries.equals("stand-ins")) {
                // the ones for iOS, under the platform of visionOS
                for (var kind : List.of("static", "clibraries")) {
                    var into = kind.equals("static") ? statics : clibraries;
                    var archives = new File(built, kind).listFiles((directory, name) -> name.endsWith(".a"));
                    for (var library : archives == null ? new File[0] : archives) {
                        standIn(library, new File(into, library.getName()), platform, retag);
                    }
                }
            } else {
                for (var library : List.of("java", "nio", "net", "zip", "extnet", "management", "management_ext", "prefs", "jaas")) {
                    standIn(new File(graalvm.home(), "lib/static/darwin-aarch64/lib" + library + ".a"), new File(statics, "lib" + library + ".a"), platform, retag);
                }
                var mac = new File(graalvm.home(), "lib/svm/clibraries/darwin-aarch64").listFiles((directory, name) -> name.endsWith(".a"));
                for (var library : mac == null ? new File[0] : mac) {
                    standIn(library, new File(clibraries, library.getName()), platform, retag);
                }
            }
            FileUtils.writeString(libraries, stamp);
        }
        System.out.println("The JDK libraries for iOS: " + libraries.split(" ")[0] + ", for the JDK " + runtime);
        if (!target.isSimulator() && libraries.equals("stand-ins")) {
            System.err.println("Warning: this device build uses stand-ins, macOS code retagged for iOS. Build the real libraries with " +
                               "./bld native-jdk-libs ios before you ship it.");
        }
        return prepared;
    }

    private static void standIn(File library, File standIn, String platform, File retag)
    throws IOException, InterruptedException {
        var objects = Files.createTempDirectory("rife2-native-app").toFile();
        try {
            Commands.run(objects, List.of("ar", "-x", library.getAbsolutePath()));
            var command = new java.util.ArrayList<>(List.of("python3", retag.getAbsolutePath(), platform));
            for (var object : objects.list((directory, name) -> name.endsWith(".o"))) {
                command.add(new File(objects, object).getAbsolutePath());
            }
            Commands.output(objects, command);
            var archive = new java.util.ArrayList<>(List.of("libtool", "-static", "-o", standIn.getAbsolutePath()));
            for (var object : objects.list((directory, name) -> name.endsWith(".o"))) {
                archive.add(new File(objects, object).getAbsolutePath());
            }
            Commands.output(objects, archive);
        } finally {
            Commands.delete(objects);
        }
    }
}
