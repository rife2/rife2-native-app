/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.bld.WebProject;
import rife.bld.operations.TemplateType;
import rife.tools.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

// the site of a project as native-image and the JVM see it
final class SiteOnJvm {
    // as the site runs inside an app, see NativeApp
    private static final String HEADLESS = "-Djava.awt.headless=true";
    private static final Pattern ANSWER = Pattern.compile("^(.*) -> (-?\\d+) .*, (\\d+) bytes with checksum (\\d+) in .*$");

    private final WebProject project_;
    private final NativeAppSettings settings_;
    private final GraalVm graalvm_;
    private final Target target_;

    SiteOnJvm(WebProject project, NativeAppSettings settings, GraalVm graalvm, Target target) {
        project_ = project;
        settings_ = settings;
        graalvm_ = graalvm;
        target_ = target;
    }

    String libraryName() {
        var name = settings_.libraryName() != null ? settings_.libraryName() : project_.name();
        // it's part of file names, commands and property lists
        if (!name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("The library name '" + name + "' can only have letters, digits, '_', '.' and '-': nativeApp().libraryName(...)");
        }
        return name;
    }

    String bundleIdentifier() {
        return settings_.bundleIdentifier() != null ? settings_.bundleIdentifier() : project_.pkg() + ".site";
    }

    private static final List<String> TEMPLATE_TYPES = List.of("html", "xml", "txt", "sql", "json", "svg");

    private List<File> templateDirectories() {
        var directories = project_.precompileOperation().sourceDirectories();
        return directories.isEmpty() ? List.of(project_.srcMainResourcesTemplatesDirectory()) : directories;
    }

    // the class names of the templates, as RIFE2 precompiles them
    List<String> templateClasses() {
        var types = project_.precompileOperation().templateTypes().stream().map(TemplateType::identifier).toList();
        var classes = new java.util.TreeSet<String>();
        for (var directory : templateDirectories()) {
            if (!directory.isDirectory()) {
                continue;
            }
            for (var file : FileUtils.getFileList(directory)) {
                var name = file.replace(File.separatorChar, '/');
                var dot = name.lastIndexOf('.');
                if (dot > 0 && types.contains(name.substring(dot + 1)) && !name.substring(name.lastIndexOf('/') + 1).startsWith(".")) {
                    classes.add("rife.template." + name.substring(dot + 1) + "." + name.substring(0, dot).replace('/', '.'));
                }
            }
        }
        return new ArrayList<>(classes);
    }

    // a native image can't compile a template while it runs
    void requirePrecompiledTemplates() {
        var types = project_.precompileOperation().templateTypes().stream().map(TemplateType::identifier).toList();
        for (var directory : templateDirectories()) {
            if (!directory.isDirectory()) {
                continue;
            }
            for (var file : FileUtils.getFileList(directory)) {
                var dot = file.lastIndexOf('.');
                var type = dot < 0 ? "" : file.substring(dot + 1);
                if (TEMPLATE_TYPES.contains(type) && !types.contains(type) && !new File(file).getName().startsWith(".")) {
                    throw new IllegalStateException("The template " + new File(directory, file) + " is only in a native library when it's precompiled: " +
                                                    "precompileOperation().templateTypes(TemplateType." + type.toUpperCase() + ", ...)");
                }
            }
        }
    }

    // the scripts, styles and images of the site, which it serves from the classpath directory webapp
    File stage(File directory)
    throws IOException {
        var webapp = new File(directory, "static/webapp");
        Commands.delete(webapp.getParentFile());
        webapp.mkdirs();
        var source = project_.srcMainWebappDirectory();
        if (source.isDirectory()) {
            FileUtils.copyDirectory(source, webapp);
        }
        Commands.delete(new File(webapp, "WEB-INF"));
        for (var file : FileUtils.getFileList(webapp)) {
            if (file.endsWith(".DS_Store")) {
                new File(webapp, file).delete();
            }
        }
        // all of them, and all the templates, not only what the tracing agent saw asked for
        var config = new File(directory, "static/META-INF/native-image/rife2-native-app-static");
        config.mkdirs();
        FileUtils.writeString("{\"resources\": {\"includes\": [{\"pattern\": \"\\\\Qwebapp/\\\\E.*\"}]}}\n", new File(config, "resource-config.json"));
        var reflection = new StringBuilder("[");
        for (var template : templateClasses()) {
            reflection.append(reflection.length() > 1 ? ",\n" : "\n").append("  {\"name\": \"").append(template)
                .append("\", \"methods\": [{\"name\": \"<init>\", \"parameterTypes\": []}, ")
                .append("{\"name\": \"isModified\", \"parameterTypes\": [\"rife.resources.ResourceFinder\", \"java.lang.String\"]}]}");
        }
        FileUtils.writeString(reflection.append("\n]\n").toString(), new File(config, "reflect-config.json"));
        return webapp.getParentFile();
    }

    // what native-image compiles: the site's jar, its dependencies and the
    // servlet API, which RIFE2's interfaces name, but no servlet container
    List<File> classpath(File staged) {
        var classpath = new LinkedHashSet<File>();
        classpath.add(staged);
        classpath.add(project_.jarOperation().destinationFile());
        classpath.addAll(project_.compileClasspathJars());
        classpath.addAll(project_.runtimeClasspathJars());
        classpath.addAll(project_.providedClasspathJars());
        if (classpath.stream().noneMatch(SiteOnJvm::isServletApi)) {
            var others = new ArrayList<File>();
            others.addAll(project_.standaloneClasspathJars());
            others.addAll(project_.testClasspathJars());
            others.stream().filter(SiteOnJvm::isServletApi).findFirst().ifPresent(classpath::add);
        }
        if (classpath.stream().noneMatch(SiteOnJvm::isServletApi)) {
            throw new IllegalStateException("The build has no jakarta.servlet-api jar, whose types RIFE2's interfaces name: " +
                                            "add dependency(\"jakarta.servlet\", \"jakarta.servlet-api\", version(6, 0, 0)) to a scope");
        }
        classpath.removeIf(file -> file.getName().endsWith("-sources.jar") || file.getName().endsWith("-javadoc.jar"));
        return new ArrayList<>(classpath);
    }

    private static boolean isServletApi(File file) {
        return file.getName().startsWith("jakarta.servlet-api-");
    }

    static String joined(List<File> files) {
        var joined = new StringBuilder();
        for (var file : files) {
            if (!joined.isEmpty()) {
                joined.append(File.pathSeparator);
            }
            joined.append(file.getAbsolutePath());
        }
        return joined.toString();
    }

    // Goes through the site on the JVM with GraalVM's tracing agent, which
    // records what native-image can't see by itself: the walk of the build
    // when it has one, and the addresses that are checked. A site that fails
    // here fails in seconds rather than after a native build.
    void walk(List<File> classpath, File configuration, File data)
    throws IOException, InterruptedException {
        Commands.delete(configuration);
        var agent = "-agentlib:native-image-agent=config-merge-dir=" + configuration.getAbsolutePath();
        if (settings_.walkClass() != null) {
            // told as the checks are, which a NativeApp starts with
            var walked = new File(data.getParentFile(), data.getName() + "-walk");
            Commands.delete(walked);
            walked.mkdirs();
            Commands.run(project_.workDirectory(), walkCommand(new File(graalvm_.tool("java").getAbsolutePath()), agent, classpath, walked));
        }
        if (!settings_.addresses().isEmpty()) {
            var answers = ask(classpath, data, agent);
            // an error page is an answer too, but not one to check against
            var failed = answers.stream().filter(answer -> answer.contains(" -") || answer.contains(" failed") || answer.matches(".* 5\\d\\d \\d+ \\d+$")).toList();
            if (!failed.isEmpty()) {
                throw new IOException("The site didn't answer on the JVM:\n" + String.join("\n", failed));
            }
        }
    }

    List<String> walkCommand(File java, String agent, List<File> classpath, File data) {
        var command = new ArrayList<>(List.of(java.getAbsolutePath(), agent, HEADLESS));
        for (var property : properties(data).entrySet()) {
            command.add("-D" + property.getKey() + "=" + property.getValue());
        }
        command.addAll(List.of("-cp", joined(classpath), settings_.walkClass()));
        command.addAll(settings_.walkArguments());
        return command;
    }

    // what the site answers at the addresses that are checked, as lines of
    // the address, the status, the size and a CRC-32, like ask.c prints them
    List<String> ask(List<File> classpath, File data, String agent)
    throws IOException, InterruptedException {
        Commands.delete(data);
        data.mkdirs();
        var command = new ArrayList<>(List.of(graalvm_.tool("java").getAbsolutePath(), HEADLESS));
        if (agent != null) {
            command.add(agent);
        }
        command.addAll(List.of("-cp", joined(classpath), "rife.nativeapp.NativeApp"));
        for (var property : properties(data).entrySet()) {
            command.add("-D" + property.getKey() + "=" + property.getValue());
        }
        command.addAll(settings_.addresses());
        var answers = new ArrayList<String>();
        for (var line : Commands.output(project_.workDirectory(), command).split("\n")) {
            var answer = ANSWER.matcher(line);
            if (answer.matches()) {
                answers.add(answer.group(1) + " " + answer.group(2) + " " + answer.group(3) + " " + answer.group(4));
            } else if (line.contains(" -> ")) {
                answers.add(line);
            }
        }
        return answers;
    }

    // what the site is told: what the app of the target tells it, the
    // build's properties, and a directory of its own for what it keeps
    java.util.Map<String, String> properties(File data) {
        var properties = new java.util.LinkedHashMap<String, String>();
        properties.put("rife.nativeapp.platform", target_.platform());
        properties.putAll(settings_.properties());
        // each side keeps what it keeps apart
        properties.put("rife.nativeapp.data", data.getAbsolutePath());
        // as ask.c asks for each answer, which an Android app does too
        properties.put("rife.nativeapp.wholeResponses", "true");
        return properties;
    }

    // the lines of a program's output that answer the addresses, not what the site printed
    List<String> answers(String output) {
        var answers = new ArrayList<String>();
        for (var line : output.replace("\r", "").split("\n")) {
            for (var address : settings_.addresses()) {
                if (line.startsWith(address + " ")) {
                    answers.add(line);
                    break;
                }
            }
        }
        return answers;
    }
}
