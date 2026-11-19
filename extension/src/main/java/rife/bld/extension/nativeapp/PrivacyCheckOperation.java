/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import rife.bld.operations.AbstractOperation;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks the privacy manifests of a built app for Apple's systems against
 * the APIs that its binaries use that Apple asks a reason for, so that App
 * Store Connect never turns a build away for one that's left out, and a
 * manifest never claims one that isn't used. The app, each framework and
 * each extension inside it, like a widget, has a manifest of its own; one
 * that uses none of these APIs needs none.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public class PrivacyCheckOperation extends AbstractOperation<PrivacyCheckOperation> {
    private File app_;

    /**
     * The app to check, a {@code .app} directory that Xcode or swiftc built.
     *
     * @param app the app
     * @return this operation
     * @since 1.11.0
     */
    public PrivacyCheckOperation app(File app) {
        app_ = app;
        return this;
    }

    /**
     * The app to check.
     *
     * @return the app; or {@code null} when none was given
     * @since 1.11.0
     */
    public File app() {
        return app_;
    }

    @Override
    public void execute()
    throws Exception {
        if (null == app_) throw new IllegalStateException("app() wasn't given an app");
        if (!app_.isDirectory()) throw new IOException("There's no app at " + app_);
        var used = new TreeSet<String>();
        var found = check(app_, used);
        if (!found.isEmpty()) {
            throw new IOException("The privacy manifests of " + app_ + " are wrong: " + String.join("; ", found));
        }
        if (!silent()) {
            System.out.println("The privacy manifests declare what the binaries use: " + (used.isEmpty() ? "nothing" : String.join(", ", used)));
        }
    }

    // what's wrong with the app and what's inside it, and what they use
    static List<String> check(File app, Set<String> used)
    throws IOException, InterruptedException {
        var read = read(app);
        // an app that uses none of these needs no manifest, as what's inside it
        var found = new ArrayList<String>();
        if (!read.uses().isEmpty() || read.manifest() != null) {
            found.addAll(problems(read.uses(), read.manifest()));
        }
        read.uses().forEach(api -> used.add(api.category));
        // a Mac has what's inside an app in its Contents
        var contents = new File(app, "Contents/Info.plist").isFile() ? new File(app, "Contents") : app;
        for (var inside : List.of(List.of("Frameworks", ".framework"), List.of("PlugIns", ".appex"), List.of("Extensions", ".appex"))) {
            var names = new File(contents, inside.get(0)).list((directory, name) -> name.endsWith(inside.get(1)));
            for (var name : null == names ? List.<String>of() : new TreeSet<>(List.of(names))) {
                var inner = read(new File(new File(contents, inside.get(0)), name));
                if (!inner.uses().isEmpty() || inner.manifest() != null) {
                    for (var problem : problems(inner.uses(), inner.manifest())) {
                        found.add(name + ": " + problem);
                    }
                }
                inner.uses().forEach(api -> used.add(api.category));
            }
        }
        return found;
    }

    private record Read(Set<PrivacyApis> uses, Map<String, Object> manifest) {
    }

    // what a bundle of iOS, or of a Mac with its Contents or its Resources, uses and declares
    private static Read read(File bundle)
    throws IOException, InterruptedException {
        File info, binaries, resources;
        if (new File(bundle, "Contents/Info.plist").isFile()) {
            info = new File(bundle, "Contents/Info.plist");
            binaries = new File(bundle, "Contents/MacOS");
            resources = new File(bundle, "Contents/Resources");
        } else if (new File(bundle, "Resources/Info.plist").isFile()) {
            info = new File(bundle, "Resources/Info.plist");
            binaries = bundle;
            resources = new File(bundle, "Resources");
        } else {
            info = new File(bundle, "Info.plist");
            binaries = bundle;
            resources = bundle;
        }
        var executable = info.isFile() ? plist(info).get("CFBundleExecutable") : null;
        // a bundle without code, of resources only, uses nothing
        if (!(executable instanceof String)) {
            return new Read(java.util.Set.of(), null);
        }
        var symbols = new java.util.HashSet<String>();
        var strings = new java.util.HashSet<String>();
        // a debug build of Xcode has the app's code in a library beside its executable
        for (var binary : List.of(new File(binaries, (String) executable), new File(binaries, executable + ".debug.dylib"))) {
            if (binary.isFile()) {
                symbols.addAll(PrivacyApis.imported(binary));
                strings.addAll(PrivacyApis.strings(binary));
            }
        }
        var manifest = new File(resources, "PrivacyInfo.xcprivacy");
        return new Read(PrivacyApis.used(symbols, strings), manifest.isFile() ? plist(manifest) : null);
    }

    static List<String> problems(Set<PrivacyApis> uses, Map<String, Object> manifest) {
        if (null == manifest) {
            return List.of("no PrivacyInfo.xcprivacy");
        }
        var found = new ArrayList<String>();
        // left out, it's false
        if (Boolean.TRUE.equals(manifest.get("NSPrivacyTracking"))) {
            found.add("NSPrivacyTracking isn't false");
        }
        var declared = new TreeMap<String, List<?>>();
        if (manifest.get("NSPrivacyAccessedAPITypes") instanceof List<?> types) {
            for (var type : types) {
                if (type instanceof Map<?, ?> entry) {
                    var category = String.valueOf(entry.get("NSPrivacyAccessedAPIType")).replaceFirst("^" + PrivacyApis.PREFIX, "");
                    declared.put(category, entry.get("NSPrivacyAccessedAPITypeReasons") instanceof List<?> reasons ? reasons : List.of());
                }
            }
        }
        var used = new TreeSet<String>();
        uses.forEach(api -> used.add(api.category));
        for (var name : used) {
            if (!declared.containsKey(name)) {
                found.add(name + " is used and not declared");
            }
        }
        for (var name : declared.keySet()) {
            if (!used.contains(name)) {
                found.add(name + " is declared and not used");
            }
        }
        for (var name : used) {
            if (declared.containsKey(name) && declared.get(name).isEmpty()) {
                found.add(name + " is declared without a reason");
            }
        }
        return found;
    }

    // a property list, binary or not, as maps, lists, texts, numbers and booleans
    static Map<String, Object> plist(File file)
    throws IOException, InterruptedException {
        var xml = Commands.output(file.getParentFile(), List.of("plutil", "-convert", "xml1", "-o", "-", file.getAbsolutePath()));
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            for (var child : elements(root)) {
                if (value(child) instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked") var dictionary = (Map<String, Object>) map;
                    return dictionary;
                }
            }
            throw new IOException(file + " isn't a dictionary");
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException(file + " can't be read: " + e.getMessage(), e);
        }
    }

    private static List<Element> elements(Element parent) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private static Object value(Element element) {
        return switch (element.getTagName()) {
            case "dict" -> {
                var map = new LinkedHashMap<String, Object>();
                var children = elements(element);
                for (var i = 0; i + 1 < children.size(); i += 2) {
                    map.put(children.get(i).getTextContent(), value(children.get(i + 1)));
                }
                yield map;
            }
            case "array" -> elements(element).stream().map(PrivacyCheckOperation::value).toList();
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "integer" -> new java.math.BigInteger(element.getTextContent().trim());
            default -> element.getTextContent();
        };
    }
}
