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

    // A directory that isn't there is fine. A link is removed as a link,
    // never what it leads to, which can be a GraalVM of the machine.
    static void delete(File directory)
    throws IOException {
        var path = directory.toPath();
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        java.nio.file.Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attributes)
            throws IOException {
                java.nio.file.Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path visited, IOException failure)
            throws IOException {
                if (failure != null) {
                    throw failure;
                }
                java.nio.file.Files.delete(visited);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
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
