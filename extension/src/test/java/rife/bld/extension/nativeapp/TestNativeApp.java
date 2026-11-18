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
        return new SiteOnJvm(project, settings, null);
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
