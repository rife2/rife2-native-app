/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

// runs the tools that a build needs
final class Commands {
    private Commands() {
    }

    // a directory that isn't there is fine
    static void delete(File directory)
    throws IOException {
        if (directory.exists()) {
            rife.tools.FileUtils.deleteDirectory(directory);
        }
    }

    // runs a command with its output on the console, and fails when it does
    static void run(File directory, Map<String, String> environment, List<String> command)
    throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command).directory(directory).inheritIO();
        builder.environment().putAll(environment);
        var process = builder.start();
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command.subList(0, Math.min(2, command.size()))) + " failed with " + process.exitValue());
        }
    }

    static void run(File directory, List<String> command)
    throws IOException, InterruptedException {
        run(directory, Map.of(), command);
    }

    // runs a command and answers what it wrote, failing when it does
    static String output(File directory, Map<String, String> environment, List<String> command)
    throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command).directory(directory).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environment);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command.subList(0, Math.min(2, command.size()))) + " failed with " + process.exitValue() + ":\n" + output);
        }
        return output;
    }

    static String output(File directory, List<String> command)
    throws IOException, InterruptedException {
        return output(directory, Map.of(), command);
    }
}
