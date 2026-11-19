package workorders;

import rife.bld.BuildCommand;
import rife.bld.extension.NativeAppProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static rife.bld.dependencies.Repository.*;
import static rife.bld.dependencies.Scope.*;
import static rife.bld.operations.TemplateType.*;

public class WorkOrdersBuild extends NativeAppProject {
    public WorkOrdersBuild() {
        pkg = "workorders";
        name = "workorders";
        mainClass = "workorders.Office";
        uberJarMainClass = "workorders.Office";
        version = version(0, 1, 0);
        javaRelease = 21;

        downloadSources = true;
        autoDownloadPurge = true;

        repositories = List.of(MAVEN_LOCAL, MAVEN_CENTRAL, RIFE2_SNAPSHOTS);
        scope(compile)
            .include(dependency("com.uwyn.rife2", "rife2", version("1.11.0-SNAPSHOT")))
            .include(dependency("com.h2database", "h2", version(2, 4, 240)));
        scope(standalone)
            .include(dependency("org.eclipse.jetty.ee10", "jetty-ee10-servlet", version(12, 1, 15)))
            .include(dependency("org.slf4j", "slf4j-simple", version(2, 0, 20)));
        scope(test)
            .include(dependency("org.junit.jupiter", "junit-jupiter", version(6, 1, 3)))
            .include(dependency("org.junit.platform", "junit-platform-console-standalone", version(6, 1, 3)))
            .include(dependency("jakarta.servlet", "jakarta.servlet-api", version(6, 0, 0)));

        precompileOperation().templateTypes(HTML);

        // the technician's site as a native library for the iOS app, see ios/
        nativeApp()
            .libraryName("workorders")
            .bundleIdentifier("com.uwyn.rife2.workorders.site")
            // days of work on the JVM, which also show GraalVM what the library needs
            .walk("workorders.ios.Embedded", "build/native/walk")
            // answers that stay the same, pages have a new CSRF token each time
            .addresses("/css/workorders.css", "/js/htmx.min.js", "/js/signature.js", "/rife2-native-app.js", "/nothing-here");
    }

    // the sign-off is a continuation, which needs its classes instrumented;
    // doing it ahead of time works the same on the JVM, in a native
    // executable and inside the iOS app
    @BuildCommand(summary = "Instruments the compiled classes for continuations")
    public void instrument()
    throws Exception {
        var classes = buildMainDirectory().getAbsolutePath();
        var command = new ArrayList<String>();
        command.add(javaTool());
        command.add("-cp");
        command.add(classes + File.pathSeparator + joinPaths(compileClasspathJars()));
        command.addAll(List.of("rife.instrument.InstrumentationDeployer", "-d", classes, classes));
        var process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("The instrumentation failed");
        }
    }

    @Override
    public void compile()
    throws Exception {
        super.compile();
        instrument();
    }

    private static String joinPaths(List<File> files) {
        var joined = new StringBuilder();
        for (var file : files) {
            if (!joined.isEmpty()) {
                joined.append(File.pathSeparator);
            }
            joined.append(file.getAbsolutePath());
        }
        return joined.toString();
    }

    public static void main(String[] args) {
        new WorkOrdersBuild().start(args);
    }
}
