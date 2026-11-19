/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

// What an app uses of Android that only came after the oldest Android that
// it's for. The compiler is given the newest Android, so it accepts a method
// that came later, and the app then stops on an older phone the first time
// that it gets there. Gradle has lint for this, app-android asks this:
// what the app's classes refer to, as dexdump reads them, looked up in the
// list that the SDK has of when each class, method and field came. The list
// leaves out members that Android has, so a member of a class of Android
// that it doesn't list passes, as does what the app calls through a class
// of a library that it extends. A class
// whose name is Api and a level, like a nested Api33, is one that's only
// used from that Android on, behind a check of the version, which this
// can't see, so it can use what came up to then.
final class AndroidApiCheck {
    private static final Pattern LEVELED = Pattern.compile(".*[/$]Api(\\d+)(\\$.*)?$");
    private static final Pattern MEMBER = Pattern.compile("L([\\w/$]+);\\.([\\w$<>]+):(\\S+?) //");
    // a class that's made, named or cast to, or caught, which dexdump writes as its own line of the catches
    private static final Pattern MADE = Pattern.compile("(?:new-instance|new-array|filled-new-array|const-class|check-cast|instance-of) [^\\n]*?\\[*L([\\w/$]+);|^\\s+L([\\w/$]+); -> 0x");
    // what Android has of Java's own and of its own, which a class or a member that it doesn't know of isn't
    private static final Pattern PLATFORM = Pattern.compile("^(java|javax|android|dalvik)/.*");

    // of a class of Android: when it came, what it extends, and when each of its members came
    private record Known(int since, List<String> above, Map<String, Integer> members) {
    }

    private final Map<String, Known> known_ = new HashMap<>();
    // the classes of the libraries that the app has inside it
    private final java.util.Set<String> libraries_ = new java.util.HashSet<>();
    // a class of the app or of a library inside it, and the ones above it
    private final Map<String, List<String>> above_ = new HashMap<>();

    AndroidApiCheck(File versions, List<File> libraries)
    throws IOException {
        for (var library : libraries) {
            try (var jar = new java.util.zip.ZipFile(library)) {
                jar.stream().map(java.util.zip.ZipEntry::getName).filter(name -> name.endsWith(".class"))
                    .forEach(name -> libraries_.add(name.substring(0, name.length() - ".class".length())));
            }
        }
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(versions, new DefaultHandler() {
                private String class_;
                private int since_;
                private List<String> above_;
                private Map<String, Integer> members_;

                @Override
                public void startElement(String uri, String local, String name, Attributes attributes) {
                    switch (name) {
                        case "class" -> {
                            class_ = attributes.getValue("name");
                            since_ = since(attributes, 1);
                            above_ = new ArrayList<>();
                            members_ = new HashMap<>();
                        }
                        case "extends", "implements" -> {
                            if (class_ != null) {
                                above_.add(attributes.getValue("name"));
                            }
                        }
                        case "method", "field" -> {
                            if (class_ != null) {
                                members_.put(attributes.getValue("name"), since(attributes, since_));
                            }
                        }
                        default -> {
                        }
                    }
                }

                @Override
                public void endElement(String uri, String local, String name) {
                    if (name.equals("class") && class_ != null) {
                        known_.put(class_, new Known(since_, above_, members_));
                        class_ = null;
                    }
                }
            });
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException(versions + " can't be read: " + e.getMessage(), e);
        }
    }

    private static int since(Attributes attributes, int otherwise) {
        var since = attributes.getValue("since");
        return null == since ? otherwise : Integer.parseInt(since);
    }

    // what dexdump -d says the app's classes refer to that came after the oldest Android
    List<String> newer(String dumped, int oldest) {
        String described = null;
        for (var line : dumped.split("\n")) {
            if (line.startsWith("  Class descriptor")) {
                described = between(line);
                above_.put(described, new ArrayList<>());
            } else if (described != null && (line.startsWith("  Superclass") || line.matches("    #\\d+ +: 'L.*"))) {
                above_.get(described).add(between(line));
            }
        }
        var newer = new TreeSet<String>();
        var allowed = oldest;
        // the method that the code is of, as name and signature
        String name = null;
        String in = null;
        for (var line : dumped.split("\n")) {
            if (line.startsWith("  Class descriptor")) {
                var leveled = LEVELED.matcher(between(line));
                allowed = leveled.matches() ? Math.max(oldest, Integer.parseInt(leveled.group(1))) : oldest;
            } else if (line.startsWith("      name          : '")) {
                name = line.split("'")[1];
            } else if (line.startsWith("      type          : '") && name != null) {
                in = name + line.split("'")[1];
            }
            var member = MEMBER.matcher(line);
            while (member.find()) {
                var owner = member.group(1);
                var called = member.group(2);
                var signature = member.group(3);
                var method = signature.startsWith("(");
                // an override that calls what it overrides only runs where that is
                if (line.contains("invoke-super") && (called + signature).equals(in)) {
                    continue;
                }
                var since = came(owner, method ? called + signature : called, new ArrayList<>());
                if (since != null && since > allowed) {
                    newer.add(owner.replace('/', '.') + "." + called + (method ? signature : "") + " came with API " + since);
                } else if (missing(owner)) {
                    // a class of Java's that javac knows and Android doesn't; the list leaves
                    // members of classes that Android has out, so only a whole class is
                    newer.add(owner.replace('/', '.') + " isn't in Android");
                }
            }
            var made = MADE.matcher(line);
            while (made.find()) {
                var type = made.group(1) != null ? made.group(1) : made.group(2);
                var known = known_.get(type);
                if (known != null && known.since() > allowed) {
                    newer.add(type.replace('/', '.') + " came with API " + known.since());
                } else if (missing(type)) {
                    newer.add(type.replace('/', '.') + " isn't in Android");
                }
            }
        }
        above(oldest, newer);
        return new ArrayList<>(newer);
    }

    // A class of the app that extends or implements one of Android that came
    // later can't even be loaded before; one whose name says from which
    // level on it's used can.
    private void above(int oldest, java.util.Set<String> newer) {
        for (var described : above_.entrySet()) {
            var leveled = LEVELED.matcher(described.getKey());
            var allowed = leveled.matches() ? Math.max(oldest, Integer.parseInt(leveled.group(1))) : oldest;
            for (var parent : described.getValue()) {
                var known = known_.get(parent);
                if (known != null && known.since() > allowed) {
                    newer.add(described.getKey().replace('/', '.') + " extends or implements " + parent.replace('/', '.') + ", which came with API " + known.since());
                }
            }
        }
    }

    // a class of Android or of Java that the list doesn't have, unless the app or a library of it has it
    private boolean missing(String owner) {
        return PLATFORM.matcher(owner).matches() && !known_.containsKey(owner) && !above_.containsKey(owner) && !libraries_.contains(owner);
    }

    // a descriptor between quotes, without its L and its ;
    private static String between(String line) {
        var quoted = line.split("'")[1];
        return quoted.substring(1, quoted.length() - 1);
    }

    // when a member came to a class or to what's above it, or nothing when Android doesn't have it
    private Integer came(String owner, String member, List<String> seen) {
        if (seen.contains(owner)) {
            return null;
        }
        List<String> parents;
        var known = known_.get(owner);
        if (known != null) {
            if (known.members().containsKey(member)) {
                return Math.max(known.since(), known.members().get(member));
            }
            parents = known.above();
        } else {
            parents = above_.getOrDefault(owner, List.of());
        }
        seen.add(owner);
        Integer first = null;
        for (var parent : parents) {
            var since = came(parent, member, new ArrayList<>(seen));
            if (since != null && (null == first || since < first)) {
                first = since;
            }
        }
        return first;
    }
}
