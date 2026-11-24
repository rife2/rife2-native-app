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
        var plist = Apple.infoPlist(site(new NativeAppSettings()), "1.0", null, Target.VISION.bundlePlatform(), Target.VISION.minimum());
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
        assertTrue(Apple.infoPlist(site, "1.0.0.RC1", null, null, null).contains("<string>1.0.0</string>"));
        assertTrue(Apple.infoPlist(site, "2.1-SNAPSHOT", null, null, null).contains("<string>2.1</string>"));
        assertTrue(Apple.infoPlist(site, "SNAPSHOT", null, null, null).contains("<key>CFBundleShortVersionString</key><string>1.0</string>"));
        assertTrue(Apple.infoPlist(site, "SNAPSHOT", null, null, null).contains("<key>CFBundleVersion</key><string>1</string>"));
        assertTrue(Apple.infoPlist(site, "2.0.0", "52", null, null).contains("<key>CFBundleVersion</key><string>52</string>"));
        var ios = Apple.infoPlist(site, "1.2.3", null, "iPhoneOS", "17.0");
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

    private static final String SIMULATORS = """
        == Devices ==
        -- iOS 18.5 --
            iPhone 16 Pro (FA0EED40-B1B4-490A-8584-3C4698780D26) (Shutdown)\s
            iPad Pro 11-inch (M4) (736B5078-3A74-4BED-A5F6-3C6EDC50C5C4) (Shutdown)\s
        -- iOS 27.0 --
            iPad Air 13-inch (M3) (163BCDF8-EA42-4191-9786-FCF33B27806A) (Shutdown)\s
            iPhone 18 Pro (EA8C3998-ED3B-4883-89A0-D630B250159E) (Booted)\s
            iPhone 16 Pro (1944DE4B-527B-42FA-BFBB-56E76E1637D2) (Shutdown)\s
            iPhone 18 Pro Max (6AD1C806-6E29-41F8-A5C5-270049162D22) (Shutdown)\s
        -- iOS 26.1 --
            iPhone 17 (39249029-9D02-47DB-8F87-31D8656A78BB) (Shutdown)\s
        -- visionOS 26.0 --
            Apple Vision Pro (356D9288-A8E9-474C-974C-5629EA392006) (Shutdown)\s
        -- watchOS 27.0 --
            Apple Watch Ultra 3 (49mm) (11111111-A8E9-474C-974C-5629EA392006) (Shutdown)\s
        """;

    @Test
    void testSimulators()
    throws Exception {
        // the first iPhone of the newest iOS, not the first of the list nor an iPad
        var newest = Simulators.find(Target.IOS_SIMULATOR, null, SIMULATORS);
        assertEquals(new Simulators.Simulator("iPhone 18 Pro", "EA8C3998-ED3B-4883-89A0-D630B250159E", true), newest);
        assertEquals("39249029-9D02-47DB-8F87-31D8656A78BB", Simulators.find(Target.IOS, "iPhone 17", SIMULATORS).udid());
        // of a name that's there for several systems, the newest
        assertEquals("1944DE4B-527B-42FA-BFBB-56E76E1637D2", Simulators.find(Target.IOS_SIMULATOR, "iPhone 16 Pro", SIMULATORS).udid());
        assertFalse(Simulators.find(Target.IOS_SIMULATOR, "iPhone 16 Pro", SIMULATORS).booted());
        assertEquals("356D9288-A8E9-474C-974C-5629EA392006", Simulators.find(Target.VISION_SIMULATOR, null, SIMULATORS).udid());
        // a name of another system isn't one of the target
        assertThrows(java.io.IOException.class, () -> Simulators.find(Target.IOS_SIMULATOR, "Apple Vision Pro", SIMULATORS));
        assertThrows(java.io.IOException.class, () -> Simulators.find(Target.IOS_SIMULATOR, "iPhone 99", SIMULATORS));
        assertThrows(java.io.IOException.class, () -> Simulators.find(Target.VISION_SIMULATOR, null, "== Devices ==\n-- iOS 27.0 --\n"));
        // while it's shutting down too
        assertEquals("EA8C3998-ED3B-4883-89A0-D630B250159E",
            Simulators.find(Target.IOS_SIMULATOR, null, "-- iOS 27.0 --\n    iPhone 18 Pro (EA8C3998-ED3B-4883-89A0-D630B250159E) (Shutting Down)\n").udid());
    }

    // an app for iOS whose binary asks for the time of a file, with a manifest and a framework inside it
    private static File app(Path temp, String manifest, String framework)
    throws Exception {
        var app = temp.resolve("Demo.app");
        Files.createDirectories(app);
        var source = temp.resolve("uses.c");
        Files.writeString(source, "#include <sys/stat.h>\nint main(int c, char** v) { struct stat s; return stat(v[0], &s); }\n");
        Commands.run(temp.toFile(), List.of("clang", "-o", app.resolve("Demo").toString(), source.toString()));
        Files.writeString(app.resolve("Info.plist"), "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>CFBundleExecutable</key><string>Demo</string></dict></plist>");
        if (manifest != null) {
            Files.writeString(app.resolve("PrivacyInfo.xcprivacy"), manifest);
        }
        if (framework != null) {
            var inside = Files.createDirectories(app.resolve("Frameworks/site.framework"));
            Files.writeString(inside.resolve("Info.plist"), "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>CFBundleExecutable</key><string>site</string></dict></plist>");
            var quiet = temp.resolve("quiet.c");
            Files.writeString(quiet, "int answer(void) { return 42; }\n");
            Commands.run(temp.toFile(), List.of("clang", "-shared", "-o", inside.resolve("site").toString(), quiet.toString()));
            if (!framework.isEmpty()) {
                Files.writeString(inside.resolve("PrivacyInfo.xcprivacy"), framework);
            }
        }
        return app.toFile();
    }

    private static String manifest(boolean tracking, String... declared) {
        var types = new StringBuilder();
        for (var category : declared) {
            types.append("<dict><key>NSPrivacyAccessedAPIType</key><string>NSPrivacyAccessedAPICategory").append(category)
                .append("</string><key>NSPrivacyAccessedAPITypeReasons</key><array><string>C617.1</string></array></dict>");
        }
        return "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>NSPrivacyAccessedAPITypes</key><array>" + types +
               "</array><key>NSPrivacyTracking</key><" + tracking + "/></dict></plist>";
    }

    @Test
    void testPrivacyCheck(@TempDir Path temp)
    throws Exception {
        var used = new java.util.TreeSet<String>();
        assertEquals(List.of(), PrivacyCheckOperation.check(app(temp.resolve("right"), manifest(false, "FileTimestamp"), null), used));
        assertEquals(java.util.Set.of("FileTimestamp"), used);
        assertEquals(List.of("FileTimestamp is used and not declared"),
            PrivacyCheckOperation.check(app(temp.resolve("left-out"), manifest(false), null), new java.util.TreeSet<>()));
        assertEquals(List.of("NSPrivacyTracking isn't false", "DiskSpace is declared and not used"),
            PrivacyCheckOperation.check(app(temp.resolve("claims"), manifest(true, "FileTimestamp", "DiskSpace"), null), new java.util.TreeSet<>()));
        assertEquals(List.of("no PrivacyInfo.xcprivacy"), PrivacyCheckOperation.check(app(temp.resolve("none"), null, null), new java.util.TreeSet<>()));
        // one that uses none of them needs none
        var quiet = app(temp.resolve("uses-nothing"), null, null).toPath();
        Files.writeString(temp.resolve("nothing.c"), "int main(void) { return 0; }\n");
        Commands.run(temp.toFile(), List.of("clang", "-o", quiet.resolve("Demo").toString(), temp.resolve("nothing.c").toString()));
        assertEquals(List.of(), PrivacyCheckOperation.check(quiet.toFile(), new java.util.TreeSet<>()));
        // left out, tracking is false
        assertEquals(List.of(), PrivacyCheckOperation.check(app(temp.resolve("untold"), manifest(false, "FileTimestamp").replace("<key>NSPrivacyTracking</key><false/>", ""), null), new java.util.TreeSet<>()));
        // an extension of ExtensionKit is checked too, a bundle of resources only uses nothing
        var extended = app(temp.resolve("extended"), manifest(false, "FileTimestamp"), null).toPath();
        Files.createDirectories(extended.resolve("Extensions/Widget.appex"));
        Files.copy(extended.resolve("Demo"), extended.resolve("Extensions/Widget.appex/Widget"));
        Files.writeString(extended.resolve("Extensions/Widget.appex/Info.plist"), "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>CFBundleExecutable</key><string>Widget</string></dict></plist>");
        Files.createDirectories(extended.resolve("Frameworks/Resources.framework"));
        Files.writeString(extended.resolve("Frameworks/Resources.framework/Info.plist"), "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>CFBundleName</key><string>Resources</string></dict></plist>");
        assertEquals(List.of("Widget.appex: no PrivacyInfo.xcprivacy"), PrivacyCheckOperation.check(extended.toFile(), new java.util.TreeSet<>()));
        // a framework that uses nothing needs no manifest, one that has one is checked
        assertEquals(List.of(), PrivacyCheckOperation.check(app(temp.resolve("quiet"), manifest(false, "FileTimestamp"), ""), new java.util.TreeSet<>()));
        assertEquals(List.of("site.framework: UserDefaults is declared and not used"),
            PrivacyCheckOperation.check(app(temp.resolve("framework"), manifest(false, "FileTimestamp"), manifest(false, "UserDefaults")), new java.util.TreeSet<>()));
        assertThrows(java.io.IOException.class, () -> new PrivacyCheckOperation().app(app(temp.resolve("failing"), manifest(false), null)).silent(true).execute());
        new PrivacyCheckOperation().app(app(temp.resolve("passing"), manifest(false, "FileTimestamp"), null)).silent(true).execute();
    }

    @Test
    void testPrivacyCheckOfAMacApp(@TempDir Path temp)
    throws Exception {
        // the binary in MacOS, the manifest among the resources
        var app = app(temp.resolve("built"), null, null);
        var mac = temp.resolve("Mac.app/Contents");
        Files.createDirectories(mac.resolve("MacOS"));
        Files.createDirectories(mac.resolve("Resources"));
        Files.copy(app.toPath().resolve("Demo"), mac.resolve("MacOS/Demo"));
        Files.copy(app.toPath().resolve("Info.plist"), mac.resolve("Info.plist"));
        Files.writeString(mac.resolve("Resources/PrivacyInfo.xcprivacy"), manifest(false, "FileTimestamp"));
        assertEquals(List.of(), PrivacyCheckOperation.check(temp.resolve("Mac.app").toFile(), new java.util.TreeSet<>()));
        Files.writeString(mac.resolve("Resources/PrivacyInfo.xcprivacy"), manifest(false));
        assertEquals(List.of("FileTimestamp is used and not declared"), PrivacyCheckOperation.check(temp.resolve("Mac.app").toFile(), new java.util.TreeSet<>()));
    }

    @Test
    void testAndroidApiCheck(@TempDir Path temp)
    throws Exception {
        var versions = temp.resolve("api-versions.xml");
        Files.writeString(versions, """
            <api version="3">
              <class name="android/view/View" since="1">
                <extends name="java/lang/Object"/>
                <method name="setPadding(IIII)V"/>
                <method name="getRootWindowInsets()Landroid/view/WindowInsets;" since="23"/>
              </class>
              <class name="android/view/WindowInsets$Type" since="30">
                <method name="systemBars()I"/>
              </class>
            </api>
            """);
        var dumped = """
              Class descriptor  : 'Lapp/Own;'
              Superclass        : 'Landroid/view/View;'
            000: invoke-virtual {v0, v1, v1, v1, v1}, Landroid/view/View;.setPadding:(IIII)V // method@0001
            001: invoke-static {}, Landroid/view/WindowInsets$Type;.systemBars:()I // method@0002
            002: invoke-virtual {v0}, Lapp/Own;.getRootWindowInsets:()Landroid/view/WindowInsets; // method@0003
            003: new-instance v0, Landroid/view/WindowInsets$Type; // type@0004
            """;
        var check = new AndroidApiCheck(versions.toFile(), List.of());
        // what came later, also through a class of the app that extends one of Android
        assertEquals(List.of("android.view.WindowInsets$Type came with API 30",
                             "android.view.WindowInsets$Type.systemBars()I came with API 30",
                             "app.Own.getRootWindowInsets()Landroid/view/WindowInsets; came with API 23"), check.newer(dumped, 21));
        assertEquals(List.of("android.view.WindowInsets$Type came with API 30", "android.view.WindowInsets$Type.systemBars()I came with API 30"),
            new AndroidApiCheck(versions.toFile(), List.of()).newer(dumped, 29));
        assertEquals(List.of(), new AndroidApiCheck(versions.toFile(), List.of()).newer(dumped, 30));

        // what javac knows of Java and Android doesn't have, arrays of what came later, and a class that implements it
        var unknown = """
              Class descriptor  : 'Lapp/Back;'
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
                #0              : 'Landroid/view/WindowInsets$Type;'
            001: invoke-static {}, Ljava/lang/ProcessHandle;.current:()Ljava/lang/ProcessHandle; // method@0005
            002: new-array v0, v1, [Landroid/view/WindowInsets$Type; // type@0006
            003: invoke-virtual {v0}, Landroid/view/View;.notThere:()V // method@0007
            004: invoke-virtual {v0}, Lapp/Back;.own:()V // method@0008
            """;
        // a member that the list leaves out of a class that Android has is taken to be there, as StringBuilder's are
        assertEquals(List.of("android.view.WindowInsets$Type came with API 30",
                             "app.Back extends or implements android.view.WindowInsets$Type, which came with API 30",
                             "java.lang.ProcessHandle isn't in Android"),
            new AndroidApiCheck(versions.toFile(), List.of()).newer(unknown, 29));

        // a class of a library inside the app isn't Android's, and an override calls what it overrides where that's there
        var library = temp.resolve("library.jar");
        try (var jar = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(library.toFile()))) {
            jar.putNextEntry(new java.util.zip.ZipEntry("javax/inject/Provider.class"));
            jar.closeEntry();
        }
        var overriding = """
              Class descriptor  : 'Lapp/Own;'
              Superclass        : 'Landroid/view/View;'
                #0              : (in Lapp/Own;)
                  name          : 'getRootWindowInsets'
                  type          : '()Landroid/view/WindowInsets;'
            000: invoke-super {v0}, Landroid/view/View;.getRootWindowInsets:()Landroid/view/WindowInsets; // method@0003
                #1              : (in Lapp/Own;)
                  name          : 'other'
                  type          : '()V'
            000: invoke-static {}, Landroid/view/WindowInsets$Type;.systemBars:()I // method@0002
            001: invoke-interface {v1}, Ljavax/inject/Provider;.get:()Ljava/lang/Object; // method@0009
            002: invoke-interface {v1}, Ljavax/other/Thing;.get:()Ljava/lang/Object; // method@0010
                  catches       : 1
                    0x0001 - 0x0002
                      Landroid/view/WindowInsets$Type; -> 0x0003
            """;
        assertEquals(List.of("android.view.WindowInsets$Type came with API 30", "android.view.WindowInsets$Type.systemBars()I came with API 30", "javax.other.Thing isn't in Android"),
            new AndroidApiCheck(versions.toFile(), List.of(library.toFile())).newer(overriding, 21));

        // what a class only uses from a level on, behind a check of the version, which its name says
        var leveled = """
              Class descriptor  : 'Lapp/Own$Api30;'
            001: invoke-static {}, Landroid/view/WindowInsets$Type;.systemBars:()I // method@0002
              Class descriptor  : 'Lapp/Own$Api29;'
            001: invoke-static {}, Landroid/view/WindowInsets$Type;.systemBars:()I // method@0002
              Class descriptor  : 'Lapp/Own$Api30$1;'
            003: new-instance v0, Landroid/view/WindowInsets$Type; // type@0004
            """;
        assertEquals(List.of("android.view.WindowInsets$Type.systemBars()I came with API 30"), new AndroidApiCheck(versions.toFile(), List.of()).newer(leveled, 21));
    }

    @Test
    void testAndroidAppIsPacked(@TempDir Path temp)
    throws Exception {
        var linked = temp.resolve("linked.apk").toFile();
        try (var out = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(linked))) {
            out.putNextEntry(new java.util.zip.ZipEntry("AndroidManifest.xml"));
            out.write("manifest".getBytes());
            out.closeEntry();
        }
        var dex = Files.createDirectories(temp.resolve("dex"));
        Files.writeString(dex.resolve("classes.dex"), "dex");
        var library = temp.resolve("libsite.so");
        Files.writeString(library, "library");
        for (var extracted : List.of(true, false)) {
            var packed = temp.resolve("packed-" + extracted + ".apk").toFile();
            AndroidAppOperation.pack(linked, dex.toFile(), library.toFile(), extracted, packed);
            try (var zip = new java.util.zip.ZipFile(packed)) {
                assertNotNull(zip.getEntry("AndroidManifest.xml"));
                assertNotNull(zip.getEntry("classes.dex"));
                var so = zip.getEntry("lib/arm64-v8a/libsite.so");
                // an app that doesn't take its libraries out loads them from the app as they are
                assertEquals(extracted ? java.util.zip.ZipEntry.DEFLATED : java.util.zip.ZipEntry.STORED, so.getMethod());
                assertEquals("library", new String(zip.getInputStream(so).readAllBytes()));
            }
        }
    }

    @Test
    void testAndroidBundleModule(@TempDir Path temp)
    throws Exception {
        // what aapt2 links as protocol buffers
        var linked = temp.resolve("proto.apk").toFile();
        try (var out = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(linked))) {
            out.putNextEntry(new java.util.zip.ZipEntry("res/"));
            out.closeEntry();
            for (var name : List.of("AndroidManifest.xml", "resources.pb", "res/layout/main.xml", "assets/page.html", "kept.properties")) {
                out.putNextEntry(new java.util.zip.ZipEntry(name));
                out.write(name.getBytes());
                out.closeEntry();
            }
        }
        var dex = Files.createDirectories(temp.resolve("dex"));
        Files.writeString(dex.resolve("classes.dex"), "dex");
        Files.writeString(dex.resolve("classes2.dex"), "dex2");
        var library = temp.resolve("libsite.so");
        Files.writeString(library, "library");
        var module = temp.resolve("base.zip").toFile();
        AndroidAppOperation.module(linked, dex.toFile(), library.toFile(), module);
        try (var zip = new java.util.zip.ZipFile(module)) {
            var names = zip.stream().map(java.util.zip.ZipEntry::getName).sorted().toList();
            assertEquals(List.of("assets/page.html", "dex/classes.dex", "dex/classes2.dex", "lib/arm64-v8a/libsite.so", "manifest/AndroidManifest.xml",
                "res/layout/main.xml", "resources.pb", "root/kept.properties"), names);
            assertEquals("AndroidManifest.xml", new String(zip.getInputStream(zip.getEntry("manifest/AndroidManifest.xml")).readAllBytes()));
            assertEquals("library", new String(zip.getInputStream(zip.getEntry("lib/arm64-v8a/libsite.so")).readAllBytes()));
        }
    }

    @Test
    void testAndroidBundleKeepsLibrariesAsTheAppTakesThem() {
        // a library that's taken out of the app when it's installed stays compressed, one that's loaded from the app is aligned for it
        assertEquals("{\"optimizations\": {\"uncompressNativeLibraries\": {\"enabled\": false}}}\n", AndroidAppOperation.bundleConfig(true));
        assertEquals("{\"optimizations\": {\"uncompressNativeLibraries\": {\"enabled\": true, \"alignment\": \"PAGE_ALIGNMENT_16K\"}}}\n",
            AndroidAppOperation.bundleConfig(false));
    }

    @Test
    void testAndroidClassesAreInTheExtension(@TempDir Path temp)
    throws Exception {
        var sources = AndroidAppOperation.androidSources(temp.toFile()).stream().map(File::getName).toList();
        assertTrue(sources.containsAll(List.of("NativePages.java", "NativeSite.java", "NativeRequests.java", "NativeShared.java", "NativeActivity.java", "NativeTexts.java")), sources.toString());
        assertTrue(Files.readString(temp.resolve("rife/nativeapp/android/NativePages.java")).contains("public class NativePages"));
    }

    @Test
    void testOldestAndroidOfTheManifest(@TempDir Path temp)
    throws Exception {
        var manifest = temp.resolve("AndroidManifest.xml");
        Files.writeString(manifest, "<manifest><uses-sdk android:minSdkVersion='24' android:targetSdkVersion=\"35\"/></manifest>");
        assertThrows(java.io.IOException.class, () -> AndroidAppOperation.oldest(manifest.toFile(), 29));
        AndroidAppOperation.oldest(manifest.toFile(), 24);
        Files.writeString(manifest, "<manifest/>");
        AndroidAppOperation.oldest(manifest.toFile(), 29);
        // what a comment says isn't what the manifest says
        Files.writeString(manifest, "<manifest><!-- was android:minSdkVersion=\"21\" --><uses-sdk android:minSdkVersion=\"29\"/></manifest>");
        AndroidAppOperation.oldest(manifest.toFile(), 29);
    }

    @Test
    void testPackageOfTheManifest(@TempDir Path temp)
    throws Exception {
        var manifest = temp.resolve("AndroidManifest.xml");
        Files.writeString(manifest, "<?xml version=\"1.0\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    package=\"com.example.app\">\n</manifest>\n");
        assertEquals("com.example.app", AndroidAppOperation.packageOf(manifest.toFile()));
    }

    @Test
    void testFrameworkBuildNumber() {
        assertThrows(IllegalArgumentException.class, () -> Apple.infoPlist(site(new NativeAppSettings()), "1.0", "52</string>", null, null));
    }
}
