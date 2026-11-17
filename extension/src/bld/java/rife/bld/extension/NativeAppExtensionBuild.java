package rife.bld.extension;

import rife.bld.Project;
import rife.bld.publish.*;
import rife.tools.FileUtils;

import java.io.File;
import java.util.List;

import static rife.bld.dependencies.Repository.*;
import static rife.bld.dependencies.Scope.*;

public class NativeAppExtensionBuild extends Project {
    public NativeAppExtensionBuild() {
        pkg = "rife.bld.extension";
        name = "bld-native-app";
        archiveBaseName = "bld-native-app";
        version = version(1, 11, 0, "SNAPSHOT");
        javaRelease = 17;

        downloadSources = true;
        autoDownloadPurge = true;

        repositories = List.of(MAVEN_CENTRAL, RIFE2_RELEASES);
        scope(compile)
            .include(dependency("com.uwyn.rife2", "bld", version(3, 0, 1)));
        scope(test)
            .include(dependency("org.junit.jupiter", "junit-jupiter", version(6, 1, 3)))
            .include(dependency("org.junit.platform", "junit-platform-console-standalone", version(6, 1, 3)));

        publishOperation()
            .repository(version.isSnapshot() ? repository("rife2-snapshots") : repository("rife2-releases"))
            .info(new PublishInfo()
                .groupId("com.uwyn.rife2")
                .artifactId("bld-native-app")
                .name("bld Native App")
                .description("bld extension that builds a RIFE2 site into a native library for an iOS, macOS or Android app")
                .url("https://github.com/rife2/rife2-native-app")
                .developer(new PublishDeveloper()
                    .id("gbevin")
                    .name("Geert Bevin")
                    .email("gbevin@uwyn.com")
                    .url("https://github.com/gbevin"))
                .license(new PublishLicense()
                    .name("The Apache License, Version 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
                .scm(new PublishScm()
                    .connection("scm:git:https://github.com/rife2/rife2-native-app.git")
                    .developerConnection("scm:git:git@github.com:rife2/rife2-native-app.git")
                    .url("https://github.com/rife2/rife2-native-app"))
                .signKey(property("sign.key"))
                .signPassphrase(property("sign.passphrase")));
    }

    // the C files are kept once, beside what they belong to, and go into
    // the extension as it's built
    @Override
    public void compile()
    throws Exception {
        super.compile();
        var root = new File(workDirectory(), "..");
        var resources = new File(buildMainDirectory(), "rife/bld/extension/nativeapp");
        resources.mkdirs();
        FileUtils.copy(new File(root, "checks/ask.c"), new File(resources, "ask.c"));
        FileUtils.copy(new File(root, "android/src/rife/nativeapp/android/rife2_native_app.c"), new File(resources, "rife2_native_app.c"));
    }

    public static void main(String[] args) {
        new NativeAppExtensionBuild().start(args);
    }
}
