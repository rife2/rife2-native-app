/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// Apple's required reason APIs, from
// developer.apple.com/documentation/bundleresources/privacy-manifest-files:
// the symbols that a binary imports for each, the selectors among its texts,
// and the reason that a site inside an app has for it, when it can have one
enum PrivacyApis {
    FILE_TIMESTAMP("FileTimestamp", "C617.1", Set.of("_stat", "_fstat", "_lstat", "_fstatat", "_getattrlist", "_fgetattrlist", "_getattrlistat",
        "_getattrlistbulk", "_NSFileCreationDate", "_NSFileModificationDate", "_NSURLContentModificationDateKey", "_NSURLCreationDateKey"), Set.of()),
    SYSTEM_BOOT_TIME("SystemBootTime", "35F9.1", Set.of("_mach_absolute_time"), Set.of("systemUptime")),
    DISK_SPACE("DiskSpace", "E174.1", Set.of("_statfs", "_statvfs", "_fstatfs", "_fstatvfs", "_getattrlist", "_fgetattrlist", "_getattrlistat",
        "_NSFileSystemFreeSize", "_NSFileSystemSize", "_NSURLVolumeAvailableCapacityKey", "_NSURLVolumeAvailableCapacityForImportantUsageKey",
        "_NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "_NSURLVolumeTotalCapacityKey"), Set.of()),
    // SwiftUI's AppStorage keeps its values there without the binary naming the class
    USER_DEFAULTS("UserDefaults", "CA92.1", Set.of("_OBJC_CLASS_$_NSUserDefaults", "_$s7SwiftUI10AppStorageVMn"), Set.of("standardUserDefaults")),
    ACTIVE_KEYBOARDS("ActiveKeyboards", null, Set.of(), Set.of("activeInputModes"));

    static final String PREFIX = "NSPrivacyAccessedAPICategory";

    final String category;
    // what a site inside an app uses it for, or nothing when a site never does
    final String reason;
    private final Set<String> symbols_;
    private final Set<String> selectors_;

    PrivacyApis(String category, String reason, Set<String> symbols, Set<String> selectors) {
        this.category = category;
        this.reason = reason;
        symbols_ = symbols;
        selectors_ = selectors;
    }

    // the ones that a binary uses, by what it imports and the texts in it
    static Set<PrivacyApis> used(Set<String> symbols, Set<String> strings) {
        var used = new TreeSet<PrivacyApis>();
        for (var api : values()) {
            if (api.symbols_.stream().anyMatch(symbols::contains) || api.selectors_.stream().anyMatch(strings::contains)) {
                used.add(api);
            }
        }
        return used;
    }

    // what a binary imports
    static Set<String> imported(File binary)
    throws IOException, InterruptedException {
        return new HashSet<>(Arrays.asList(Commands.output(binary.getParentFile(), List.of("nm", "-u", binary.getAbsolutePath())).split("\\s+")));
    }

    // the texts in a binary, as lines
    static Set<String> strings(File binary)
    throws IOException, InterruptedException {
        return new HashSet<>(Arrays.asList(Commands.output(binary.getParentFile(), List.of("strings", binary.getAbsolutePath())).split("\n")));
    }

    static PrivacyApis of(String category) {
        for (var api : values()) {
            if (api.category.equals(category)) {
                return api;
            }
        }
        return null;
    }
}
