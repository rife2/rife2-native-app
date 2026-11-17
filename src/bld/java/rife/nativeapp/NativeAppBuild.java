package rife.nativeapp;

import rife.bld.Project;
import rife.bld.publish.*;

import java.util.List;

import static rife.bld.dependencies.Repository.*;
import static rife.bld.dependencies.Scope.*;

public class NativeAppBuild extends Project {
    public NativeAppBuild() {
        pkg = "rife.nativeapp";
        name = "rife2-native-app";
        version = version(1, 11, 0, "SNAPSHOT");
        javaRelease = 17;

        downloadSources = true;
        autoDownloadPurge = true;

        repositories = List.of(MAVEN_LOCAL, MAVEN_CENTRAL, RIFE2_SNAPSHOTS, RIFE2_RELEASES);
        scope(compile)
            .include(dependency("com.uwyn.rife2", "rife2", version(1, 11, 0, "SNAPSHOT")));
        // only native-image has these, on the JVM the C functions are never loaded
        scope(provided)
            .include(dependency("org.graalvm.sdk", "nativeimage", version(25, 0, 5)));
        scope(test)
            .include(dependency("org.junit.jupiter", "junit-jupiter", version(6, 1, 3)))
            .include(dependency("org.junit.platform", "junit-platform-console-standalone", version(6, 1, 3)))
            .include(dependency("jakarta.servlet", "jakarta.servlet-api", version(6, 0, 0)));

        publishOperation()
            .repository(version.isSnapshot() ? "rife2-snapshots" : "rife2-releases")
            .repository(version.isSnapshot() ? "central-snapshots" : "central-releases")
            .info(new PublishInfo()
                .groupId("com.uwyn.rife2")
                .artifactId("rife2-native-app")
                .name("RIFE2 Native App")
                .description("Runs a RIFE2 site inside an iOS, macOS or Android app, compiled with GraalVM native-image.")
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

    public static void main(String[] args) {
        new NativeAppBuild().start(args);
    }
}
