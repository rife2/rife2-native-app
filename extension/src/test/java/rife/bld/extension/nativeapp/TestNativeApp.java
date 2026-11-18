/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.bld.extension.nativeapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rife.bld.WebProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestNativeApp {
    @Test
    void testDeleteLeavesWhatALinkLeadsTo(@TempDir Path temp)
    throws Exception {
        // a GraalVM of the machine, and a copy of it that's a link to it
        var graalvm = Files.createDirectories(temp.resolve("graalvm/lib"));
        Files.writeString(graalvm.resolve("libjava.a"), "kept");
        var prepared = Files.createDirectories(temp.resolve("build/native"));
        Files.createSymbolicLink(prepared.resolve("graalvm-ios"), temp.resolve("graalvm"));
        Files.createSymbolicLink(prepared.resolve("inside"), temp.resolve("graalvm"));

        Commands.delete(prepared.resolve("graalvm-ios").toFile());
        Commands.delete(prepared.toFile());

        assertFalse(Files.exists(prepared));
        assertEquals("kept", Files.readString(graalvm.resolve("libjava.a")));
        Commands.delete(temp.resolve("nothing").toFile());
    }

    @Test
    void testTargetOptionsComeBeforeTheClasspath(@TempDir Path temp) {
        // without a configuration, which a site without addresses or a walk has none of
        var command = NativeLibraryOperation.nativeImage(new File("native-image"), List.of("--target=ios-aarch64"),
            List.of(new File("site.jar")), temp.resolve("config").toFile(), new File("libsite"));
        var classpath = command.indexOf("-cp");
        assertEquals(new File("site.jar").getAbsolutePath(), command.get(classpath + 1));
        assertTrue(command.indexOf("--target=ios-aarch64") < classpath, command.toString());
    }

    @Test
    void testChecksAreToldWhatTheAppTellsTheSite() {
        var ios = site(new NativeAppSettings()).properties(new File("data"));
        assertEquals("ios", ios.get("rife.nativeapp.platform"));
        assertEquals("true", ios.get("rife.nativeapp.wholeResponses"));
        // as ask.c always asks
        assertEquals("true", site(new NativeAppSettings().property("rife.nativeapp.wholeResponses", "false")).properties(new File("data")).get("rife.nativeapp.wholeResponses"));
        var project = new WebProject();
        var android = new SiteOnJvm(project, new NativeAppSettings(), null, Target.ANDROID).properties(new File("data"));
        assertEquals("android", android.get("rife.nativeapp.platform"));
        assertEquals("true", android.get("rife.nativeapp.wholeResponses"));
        assertEquals("mac", new SiteOnJvm(project, new NativeAppSettings(), null, Target.MAC).properties(new File("data")).get("rife.nativeapp.platform"));
        // what the build says wins
        assertEquals("mac", site(new NativeAppSettings().property("rife.nativeapp.platform", "mac")).properties(new File("data")).get("rife.nativeapp.platform"));
    }

    @Test
    void testEveryStaticFileIsEmbedded(@TempDir Path temp)
    throws Exception {
        var project = new WebProject() {
            {
                workDirectory = temp.toFile();
            }
        };
        var file = new File(project.srcMainWebappDirectory(), "js/unasked.js");
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), "unasked");
        var staged = new SiteOnJvm(project, new NativeAppSettings(), null, Target.MAC).stage(temp.resolve("out").toFile());
        assertEquals("unasked", Files.readString(staged.toPath().resolve("webapp/js/unasked.js")));
        var config = Files.readString(staged.toPath().resolve("META-INF/native-image/rife2-native-app-static/resource-config.json"));
        var pattern = java.util.regex.Pattern.compile(config.replaceAll("(?s).*\"pattern\": \"(.*)\".*", "$1").replace("\\\\", "\\"));
        assertTrue(pattern.matcher("webapp/js/unasked.js").matches(), config);
        assertFalse(pattern.matcher("templates/home.class").matches(), config);

        // and every template, rendered by a check or not
        var template = new File(project.srcMainResourcesTemplatesDirectory(), "orders/edit.html");
        template.getParentFile().mkdirs();
        Files.writeString(template.toPath(), "<html></html>");
        project.precompileOperation().templateTypes(rife.bld.operations.TemplateType.HTML);
        staged = new SiteOnJvm(project, new NativeAppSettings(), null, Target.MAC).stage(temp.resolve("out").toFile());
        var reflection = Files.readString(staged.toPath().resolve("META-INF/native-image/rife2-native-app-static/reflect-config.json"));
        assertTrue(reflection.contains("\"name\": \"rife.template.html.orders.edit\""), reflection);
    }

    @Test
    void testTemplatesHaveToBePrecompiled(@TempDir Path temp)
    throws Exception {
        var project = new WebProject() {
            {
                workDirectory = temp.toFile();
            }
        };
        var site = new SiteOnJvm(project, new NativeAppSettings(), null, Target.MAC);
        site.requirePrecompiledTemplates();
        var templates = project.srcMainResourcesTemplatesDirectory();
        templates.mkdirs();
        // what isn't a template doesn't count
        Files.writeString(templates.toPath().resolve(".DS_Store"), "");
        Files.writeString(templates.toPath().resolve("README.md"), "");
        site.requirePrecompiledTemplates();
        Files.writeString(templates.toPath().resolve("home.html"), "<html></html>");
        assertThrows(IllegalStateException.class, site::requirePrecompiledTemplates);
        project.precompileOperation().templateTypes(rife.bld.operations.TemplateType.HTML);
        site.requirePrecompiledTemplates();
        // each type that's there
        Files.createDirectories(templates.toPath().resolve("mail"));
        Files.writeString(templates.toPath().resolve("mail/welcome.txt"), "hi");
        assertThrows(IllegalStateException.class, site::requirePrecompiledTemplates);
        project.precompileOperation().templateTypes(rife.bld.operations.TemplateType.HTML, rife.bld.operations.TemplateType.TXT);
        site.requirePrecompiledTemplates();
        assertEquals(List.of("rife.template.html.home", "rife.template.txt.mail.welcome"), site.templateClasses());
    }

    @Test
    void testTheWalkIsToldAsTheChecksAre() {
        var site = site(new NativeAppSettings().walk("com.example.Walk", "one"));
        var command = site.walkCommand(new File("java"), "-agentlib:x", List.of(new File("site.jar")), new File("walked"));
        assertTrue(command.contains("-Drife.nativeapp.platform=ios"), command.toString());
        assertTrue(command.contains("-Drife.nativeapp.data=" + new File("walked").getAbsolutePath()), command.toString());
        assertEquals(List.of("com.example.Walk", "one"), command.subList(command.size() - 2, command.size()));
    }

    @Test
    void testOperationsTakeAnyWebProject() {
        var app = new rife.bld.extension.NativeAppProject();
        assertSame(app.nativeApp(), new NativeLibraryOperation().fromProject(app).settings());
        assertSame(app.nativeApp(), new NativeCheckOperation().fromProject(app).settings());
        assertSame(app.nativeApp(), new NativeJdkLibrariesOperation().fromProject(app).settings());

        var settings = new NativeAppSettings().libraryName("mysite");
        var operation = new NativeLibraryOperation().fromProject(new WebProject()).settings(settings).target(Target.MAC);
        assertSame(settings, operation.settings());
        assertEquals(Target.MAC, operation.target());
        var missing = assertThrows(IllegalStateException.class, () -> new NativeCheckOperation().fromProject(new WebProject()).target(Target.MAC).execute());
        assertTrue(missing.getMessage().contains("settings()"), missing.getMessage());
    }

    @Test
    void testTheJarIsMadeFirst(@TempDir Path temp) {
        var site = new SiteOnJvm(new WebProject(), new NativeAppSettings(), null, Target.MAC);
        var missing = assertThrows(IllegalStateException.class, () -> site.classpath(temp.toFile()));
        assertTrue(missing.getMessage().contains("jar()"), missing.getMessage());
    }

    @Test
    void testMacLibraryRunsOnTheOldestMacOfThePackage() {
        assertEquals(List.of("-H:NativeLinkerOption=-mmacosx-version-min=14.0"), NativeLibraryOperation.macOptions());
    }

    @Test
    void testVisionOsIsBuiltAsIos() {
        assertEquals(Target.VISION_SIMULATOR, Target.from("vision-sim"));
        assertEquals("vision", Target.VISION.platform());
        assertTrue(Target.VISION.isIos());
        assertEquals("arm64-apple-xros26.0", Target.VISION.triple());
        assertEquals("arm64-apple-xros26.0-simulator", Target.VISION_SIMULATOR.triple());
        assertEquals("arm64-apple-ios17.0-simulator", Target.IOS_SIMULATOR.triple());
        assertEquals("xrsimulator", Target.VISION_SIMULATOR.sdk());
        assertEquals("XROS", Target.VISION.bundlePlatform());
        // the JDK libraries for iOS, under the platform of visionOS
        assertEquals("ios", Target.VISION.jdkLibraries());
        assertEquals("iossim", Target.VISION_SIMULATOR.jdkLibraries());
        assertEquals("visionsim", Target.VISION_SIMULATOR.retagged());
        var plist = Apple.infoPlist(site(new NativeAppSettings()), "1.0", Target.VISION.bundlePlatform(), Target.VISION.minimum());
        assertTrue(plist.contains("<string>XROS</string>") && plist.contains("<key>MinimumOSVersion</key><string>26.0</string>"), plist);
    }

    @Test
    void testLibrariesCanBeKeptOutOfClean(@TempDir Path temp) {
        var project = new WebProject() {
            {
                workDirectory = temp.toFile();
            }
        };
        var settings = new NativeAppSettings();
        assertEquals(new File(project.buildDirectory(), "native/ios-sim"), NativeLibraryOperation.directory(project, settings, Target.IOS_SIMULATOR));
        settings.destinationDirectory(new File("native"));
        assertEquals(new File(temp.toFile(), "native/ios-sim"), NativeLibraryOperation.directory(project, settings, Target.IOS_SIMULATOR));
        settings.destinationDirectory(temp.resolve("elsewhere").toFile());
        assertEquals(temp.resolve("elsewhere/mac").toFile(), NativeLibraryOperation.directory(project, settings, Target.MAC));
    }

    @Test
    void testTargets() {
        assertEquals(Target.IOS_SIMULATOR, Target.from("ios-sim"));
        assertEquals(Target.ANDROID, Target.from("android"));
        assertThrows(IllegalArgumentException.class, () -> Target.from("windows"));
    }

    private static SiteOnJvm site(NativeAppSettings settings) {
        var project = new WebProject() {
            {
                name = "mysite";
                pkg = "com.example";
            }
        };
        return new SiteOnJvm(project, settings, null, Target.IOS_SIMULATOR);
    }

    @Test
    void testLibraryName() {
        assertEquals("mysite", site(new NativeAppSettings()).libraryName());
        assertEquals("my-site_2.0", site(new NativeAppSettings().libraryName("my-site_2.0")).libraryName());
        for (var bad : List.of("my site", "it's", "a/b", "<x>", ".hidden", "")) {
            assertThrows(IllegalArgumentException.class, () -> site(new NativeAppSettings().libraryName(bad)).libraryName(), bad);
        }
        assertEquals("com.example.site", site(new NativeAppSettings()).bundleIdentifier());
    }

    @Test
    void testInfoPlistVersion() {
        var site = site(new NativeAppSettings());
        assertTrue(Apple.infoPlist(site, "1.0.0.RC1", null, null).contains("<string>1.0.0</string>"));
        assertTrue(Apple.infoPlist(site, "2.1-SNAPSHOT", null, null).contains("<string>2.1</string>"));
        assertTrue(Apple.infoPlist(site, "SNAPSHOT", null, null).contains("<key>CFBundleShortVersionString</key><string>1.0</string>"));
        var ios = Apple.infoPlist(site, "1.2.3", "iPhoneOS", "17.0");
        assertTrue(ios.contains("<array><string>iPhoneOS</string></array>"));
        assertTrue(ios.contains("<key>MinimumOSVersion</key><string>17.0</string>"));
    }

    @Test
    void testAnswersAreOnlyTheLinesOfTheAddresses() {
        var site = site(new NativeAppSettings().addresses("/a", "/b"));
        assertEquals(List.of("/a 200 3 123", "/b 404 0 0"),
            site.answers("Starting the site\r\n/a 200 3 123\nsomething printed /a\n/b 404 0 0\n/ab 200 1 1\n"));
    }

    @Test
    void testPrivacyManifestFromWhatTheBinaryUses(@TempDir Path temp)
    throws Exception {
        // a library that asks for the time of a file and for nothing else Apple asks a reason for
        var source = temp.resolve("uses.c");
        Files.writeString(source, "#include <sys/stat.h>\nint size(const char* p) { struct stat s; return stat(p, &s) ? -1 : (int) s.st_size; }\n");
        var binary = temp.resolve("libuses.dylib").toFile();
        Commands.run(temp.toFile(), List.of("clang", "-shared", "-o", binary.getAbsolutePath(), source.toString()));
        var manifest = temp.resolve("PrivacyInfo.xcprivacy").toFile();

        Apple.privacyManifest(new NativeAppSettings(), binary, manifest);
        var written = Files.readString(manifest.toPath());
        assertTrue(written.contains("NSPrivacyAccessedAPICategoryFileTimestamp"), written);
        assertTrue(written.contains("<string>C617.1</string>"), written);
        assertFalse(written.contains("SystemBootTime"), written);
        assertFalse(written.contains("UserDefaults"), written);

        // the build's own is taken as it is
        var own = temp.resolve("Own.xcprivacy").toFile();
        Files.writeString(own.toPath(), "own");
        Apple.privacyManifest(new NativeAppSettings().privacyManifest(own), binary, manifest);
        assertEquals("own", Files.readString(manifest.toPath()));
    }

    @Test
    void testRelativeJdkLibrariesAreAbsolute() {
        assertTrue(new NativeAppSettings().jdkLibraries(new File("libs")).jdkLibraries().isAbsolute());
    }
}
