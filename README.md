[![License](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/java-17%2B-blue)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![bld](https://img.shields.io/badge/3.0.1-FA9052?label=bld&labelColor=2392FF)](https://rife2.com/bld)
[![Release](https://img.shields.io/github/release/rife2/rife2-native-app.svg)](https://github.com/rife2/rife2-native-app/releases/latest)
[![Snapshot](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.rife2.com%2Fsnapshots%2Fcom%2Fuwyn%2Frife2%2Fbld-native-app%2Fmaven-metadata.xml&label=snapshot)](https://repo.rife2.com/#/snapshots/com/uwyn/rife2/bld-native-app)
[![bld-ci](https://github.com/rife2/rife2-native-app/actions/workflows/bld.yml/badge.svg)](https://github.com/rife2/rife2-native-app/actions/workflows/bld.yml)

# RIFE2 Native App

Runs a [RIFE2](https://rife2.com) site inside an iOS, macOS, visionOS or Android app.
GraalVM compiles the site to a native library, and the app's web view gets
its pages from that library in the same process, without a server or a port.

This is what [Gates of Humanity](https://hd.uwyn.com) does on the App Store,
lifted out of it so that another app doesn't have to write it again.

The part that runs inside the site is in RIFE2 itself, in the package
`rife.nativeapp`, so a site needs nothing but the RIFE2 jar. This repository
has what goes around it: the code of the apps, and what builds and checks
the native library.

Everything here runs on a Mac with Apple silicon, and builds for arm64:
iOS and visionOS devices and their simulators on that Mac, macOS, and
Android devices and emulators of `arm64-v8a`.

## What's here

| Part | What it does |
|---|---|
| `Package.swift` and `apple/` | the Swift package `RIFE2NativeApp`, which starts the site's Java in an iOS or macOS app and shows its pages in a web view |
| `android/` | the classes that show the site in an Android app, and the bridge that's linked into the site's library |
| `extension/` | the bld extension `bld-native-app`, which builds the site into a native library and checks it |
| `checks/ask.c` | asks a native library for addresses without an app, to compare its answers with the JVM's |
| `checks/android/` | an app made of the Android classes only, which shows a native library and taps through it |

## The site

An app names its site in `src/main/resources/META-INF/services/rife.engine.Site`:

```
com.example.MySite
```

The site needs a constructor without arguments. A site that needs what the
app tells it before it exists, like the directory of its database, is made
by a `rife.nativeapp.SiteProvider` instead, which the app names in
`META-INF/services/rife.nativeapp.SiteProvider`:

```java
public class MyApp implements SiteProvider {
    public Site site(HierarchicalProperties properties) {
        // an app extension isn't told a directory for what lasts
        return new MySite(new File(properties.getValueString(NativeApp.DATA_PROPERTY, "build/data")));
    }
}
```

A provider can also put properties of its own into those it's given, which
the site then reads like the others.

These are classes of RIFE2's `rife.nativeapp`. What the app tells the site
arrives as properties, which a site can also read in its `setup()`:

| Property | What it is |
|---|---|
| `rife.nativeapp.platform` | `ios`, `mac`, `vision` or `android` |
| `rife.nativeapp.data` | the directory where the app keeps what should last; the cookies of the site are kept there |
| `rife.nativeapp.scheme`, `rife.nativeapp.host` | the address that the web view has the site at: `app://site` on Apple's systems and `https://<package>.invalid` on Android, unless the app says otherwise; an Android app keeps `https` |
| `rife.nativeapp.temp` | the directory for temporary files and uploads, when Java's own isn't one that the app may write in |
| `rife.nativeapp.version`, `rife.nativeapp.buildNumber` | the version of the app and its build number |
| `rife.nativeapp.wholeResponses` | `true` when the app takes each answer all at once on the thread that asked, as on Android; a response that goes on after its request, like an event stream, then ends with it |

The cookies of the site are kept by its cookie jar, apart from the web
view's: a cookie that a page's script sets in `document.cookie` doesn't
reach the site, and one that the site sets can't be read there.

The site runs with `java.awt.headless` set to `true`, unless the app set it
first. It has no display, and a native image on a Mac can only draw
images, like the ones that RIFE2's content management scales, when it's
headless.

The scripts, styles and images of the site are served from the classpath
directory `webapp`, which is where the native build puts all of
`src/main/webapp`. They're only served for an address that no route of the
site takes, so a route that takes every path keeps them, the script of the
pages among them, from being served.

## The pages

A template includes this in its `<head>`, after its viewport `<meta>`:

```html
<!--v context:nativeAppScript/-->
```

In a browser, it's empty. Inside an app, it's the script of RIFE2's
`rife.nativeapp`, which does what a web view doesn't do by itself:
printing, and on Android also sharing, saving a file and handing over what
a page posts. It also:

- adds the classes `in-app` and `on-<platform>` to the `<html>` element,
  for styles of a page in an app or on one platform,
- sets `window.rife2NativeApp.platform`, for a page's own scripts,
- on Apple's systems, replaces the content of the viewport `<meta>` with
  `width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover`,
  so that a page keeps its size under two fingers and is laid out to the
  edges of the screen, as a screen of an app is. A page that should zoom
  sets its viewport again after the script.

## The C functions

RIFE2's `rife.nativeapp.EntryPoints` has these, which native-image only
exports when it builds a shared library; an executable of a site doesn't
have them.

```c
int rife2_abi(graal_isolatethread_t* thread);
void rife2_property(graal_isolatethread_t* thread, const char* name, const char* value);
void rife2_handle(graal_isolatethread_t* thread, long long id, const char* url, const char* method, const char* headers,
                  const char* body, int length, void* context,
                  void (*committed)(void* context, int status, const char* headers),
                  void (*content)(void* context, const char* bytes, int length),
                  void (*ended)(void* context, int how, const char* failure));
void rife2_cancel(graal_isolatethread_t* thread, long long id);
```

Texts are UTF-8 that ends with a zero, and headers are lines of a name, a
colon and a value. A request tells the status and the headers once, the
content as it's written, and once how it ended: 0 completed, 1 failed with
why, or with `NULL` when there wasn't the memory to say why, 2 cancelled.
A request that the site couldn't handle at all is only told that it
failed. Nothing comes after the end, so that's where the app lets go of
its context. The text of a failure is only the app's during the
call. Each request has a number that no request had before, which
`rife2_cancel` takes; a cancel can come before its request.
`rife2_abi` answers 1 and changes when these signatures do.

## The Swift package

An iOS 17, macOS 14 or visionOS 26 app adds this repository as a Swift
package, with `branch: "master"` until it has a release to give as a
version. Its target also adds the framework that native-image built from
its site to "Frameworks, Libraries, and Embedded Content" with "Embed &
Sign": `build/native/mysite.xcframework` for iOS and visionOS, which has the
frameworks for their devices and simulators that were built, so build both
of a system before an archive, or `build/native/mac/mysite.framework` for
macOS. Then a SwiftUI
app shows the site with:

```swift
import RIFE2NativeApp
import SwiftUI

@main
struct MyApp: App {
    @StateObject private var pages = NativePages(start: "/")

    var body: some Scene {
        WindowGroup {
            NativePagesView(pages: pages)
        }
    }
}
```

`NativePages` opens links that leave the site in the system, saves or
shares the files that a page downloads, prints, and loads the page again
when the system stopped what draws it. It publishes the `path` of the page
that's shown and whether it `canGoBack`, for what the app shows around the
pages. An app changes or adds to that by subclassing it, for instance to add
its own message handlers in `configure(_:)`, or to say what happens when a
page couldn't be loaded, in its `webView(_:didFail:withError:)` and
`webView(_:didFailProvisionalNavigation:withError:)`.

An app that's put together with `swiftc` rather than Xcode builds the
package with `swift build --product RIFE2NativeApp -c release`, links the
`libRIFE2NativeApp.a` that it writes to `.build/release` with the site's
framework, and imports it with `-I .build/release` and
`-I apple/Sources/CRIFE2NativeApp/include`, which has the module map of
the C functions.

`NativeSite.scheme`, `NativeSite.host` and `NativeSite.properties` are set
before the first page, when the app wants another address than `app://site`
or has properties of its own for the site. `NativeSite.shared.get(path)`
answers what's at a path all at once, for what isn't a page in a web view.

An app extension, like a widget, links the package too and asks
`NativeSite` for what it needs; the web view parts aren't available there.
The site of an extension isn't told the platform, so its pages get no
script, nor a directory for what lasts, so it keeps cookies only while it
runs. It starts
the site with a heap of 16 MB, since iOS stops an extension that uses more
than a few tens of megabytes; `NativeSite.arguments` changes that. watchOS
has neither a web view nor a target of native-image: a watch app gets what
it shows from the iPhone app, which asks its site.

## Android

The site's library for Android has the bridge of
`android/src/rife/nativeapp/android/rife2_native_app.c` linked into it, and
the app has the classes of `rife.nativeapp.android`. They aren't published
as a library yet: an app copies `android/src` into its sources, or adds it
to them in its Gradle build, here from a checkout of this repository beside
the app's project. They're written for Java 17, and use `androidx.webkit`,
whose message listener the system's web view of Android has to support,
as an updated one does (`WebViewFeature.WEB_MESSAGE_LISTENER`):

```groovy
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    sourceSets {
        main.java.srcDirs += "../../rife2-native-app/android/src"
    }
}

dependencies {
    implementation "androidx.webkit:webkit:1.12.1"
}
```

An app that's built without Gradle compiles these classes against the
`classes.jar` of the webkit aar, with `androidx.annotation` on the classpath
too, or javac warns that it can't find `RestrictTo$Scope`. If such a build
checks its dex for calls that are newer than its oldest Android, it should
check the webkit classes apart: they call newer APIs behind their own
version checks, which lint knows about but a plain scan doesn't.

The app puts `build/native/android/libmysite.so` in its `jniLibs/arm64-v8a`,
needs API level 29 or later, and an activity shows the site with:

```java
public class MainActivity extends Activity {
    private NativePages pages_;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        NativeSite.library = "mysite";
        pages_ = new NativePages(this, "/").start(state);
        setContentView(pages_.shown);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        pages_.chosen(request, result, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        pages_.keep(state);
    }

    @Override
    public void onBackPressed() {
        if (!pages_.back()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        pages_.gone();
        super.onDestroy();
    }
}
```

and declares the provider that hands a saved file to another app:

```xml
<provider android:name="rife.nativeapp.android.NativeShared" android:authorities="${applicationId}.rife2.files"
          android:exported="false" android:grantUriPermissions="true"/>
```

`NativeSite.library`, `NativeSite.host` and `NativeSite.properties` are set
before the first request, in the `onCreate` of the app's `Application` when
it has widgets, since a widget can ask the site first. The site is at
`https://<package>.invalid`, since a web view of Android only keeps what a
page stores for an address of the web. That address is where the pages
keep what they store, so an app that changes it starts without what its
pages kept before. `NativeSite.get(context, path)`
answers what's at a path all at once. A response that doesn't end, like an
event stream, is cancelled when its request returns, since Android takes an
answer all at once: an `EventSource` in a page gets nothing on Android.

A subclass of `NativePages` adds what's its own to the web view in
`configure(WebView)`. The pages ask the app through an object that only the
site's own frames get, so a frame of elsewhere or a sandboxed one can't, and
the app checks where each message comes from. An interface that an app adds
with `addJavascriptInterface` reaches every frame, so it's better added the
same way, with `WebViewCompat.addWebMessageListener`. Nothing but the site
loads in the web view; an app whose pages show what's on the web allows it
there with `setBlockNetworkLoads(false)`, which also needs the `INTERNET`
permission. A
link out of the site goes to Android, except to a file, a content address
or an intent. An app that opts into predictive back, with
`android:enableOnBackInvokedCallback` or a target SDK that makes it the
default, gets the back button through an `OnBackInvokedCallback` instead of
`onBackPressed`, and calls `back()` from there. A service worker's requests
don't reach the app.

`android/resources/META-INF/proguard/rife2-native-app-android.pro` has the
rules that keep R8 from removing what the native library calls by name. An
app that shrinks its code with R8 adds it to its `proguardFiles`.

A web view of Android doesn't hand over what a page posts. The script of
the pages hands it to the app first, for `fetch`, an `XMLHttpRequest` that
isn't synchronous, and a form that doesn't post as plain text. A
synchronous `XMLHttpRequest`, `navigator.sendBeacon` and a worker post
nothing.

## Building the native library

The bld extension builds the site with GraalVM's native-image. It's
published to the RIFE2 repository, which a build names with the extension
in `lib/bld/bld-wrapper.properties`:

```
bld.extensions=com.uwyn.rife2:bld-native-app:1.11.0-SNAPSHOT
bld.repositories=MAVEN_CENTRAL,RIFE2_SNAPSHOTS,RIFE2_RELEASES
```

bld keeps the extension it resolved in `lib/bld/bld.cache`, so a newer
snapshot of it is only taken once that file is deleted.

Its build class extends `NativeAppProject` instead of `WebProject`. A native
image can't compile templates while it runs, so the build precompiles them,
and `app-library` stops when there are templates of a type that it
doesn't. The native library has all of them, rendered by a check or not. The
site is asked on the JVM before each build, which needs the servlet API that
RIFE2's interfaces name, but no servlet container:

```java
public class MyBuild extends NativeAppProject {
    public MyBuild() {
        // ...
        repositories = List.of(MAVEN_CENTRAL, RIFE2_SNAPSHOTS, RIFE2_RELEASES);
        scope(compile)
            .include(dependency("com.uwyn.rife2", "rife2", version(1, 11, 0, "SNAPSHOT")));
        scope(provided)
            .include(dependency("jakarta.servlet", "jakarta.servlet-api", version(6, 0, 0)));
        precompileOperation().templateTypes(TemplateType.HTML);

        nativeApp()
            .libraryName("mysite")
            .addresses("/", "/css/site.css", "/nothing-here")
            .walk("com.example.Walk");
    }
}
```

A site that uses continuations, the workflow engine, meta-data merging or
lazy-loading also has its classes instrumented ahead of time, as
[GraalVM Native Image](https://github.com/rife2/rife2/wiki/GraalVM-Native-Image#ahead-of-time-instrumentation)
in RIFE2's wiki describes, since a native image has no java agent.

`nativeApp()` takes these:

| Setting | What it is |
|---|---|
| `libraryName(name)` | the name of the library and its framework, `mysite` for `libmysite.so` and `mysite.framework`; the name of the project when it's not set |
| `bundleIdentifier(identifier)` | the bundle identifier of the framework; the package of the project with `.site` when it's not set |
| `addresses(addresses...)` | the addresses that the site is asked for before each build and by `app-check` |
| `walk(mainClass, arguments...)` | a class whose `main` goes through the site as the app does, with GraalVM's tracing agent |
| `property(name, value)` | a property that the site is told on the JVM and by `app-check`, like the app would tell it |
| `privacyManifest(file)` | the privacy manifest of the framework; without one, it's written from what the library's code uses |
| `simulator(name)` | the simulator of `app-check ios-sim`, `iPhone 17` unless it's set |
| `visionSimulator(name)` | the simulator of `app-check vision-sim`, `Apple Vision Pro` unless it's set |
| `androidSdk(directory)` | the Android SDK; `ANDROID_HOME`, or `~/Library/Android/sdk`, when it's not set |
| `androidNdk(version)` | the version of the NDK in that SDK, `28.2.13676358` unless it's set |
| `androidApi(level)` | the oldest Android that the library runs on, 29 unless it's set |
| `jdkLibraries(directory)` | where `app-jdk-libs` keeps what it builds; `RIFE2_NATIVE_JDK_LIBS`, or `~/.rife2/native-app/jdk-libs`, when it's not set |
| `destinationDirectory(directory)` | where the libraries are built, a directory for each target and the xcframework beside them; `build/native` unless it's set, which `./bld clean` removes. A directory outside `build`, like `native`, keeps them across a clean build |

A build that extends another subclass of `WebProject` than
`NativeAppProject` uses the operations of the commands itself, in commands
of its own that make the jar first:

```java
@BuildCommand(value = "app-library")
public void appLibrary()
throws Exception {
    jar();
    new NativeLibraryOperation().fromProject(this)
        .settings(new NativeAppSettings().libraryName("mysite").addresses("/"))
        .target(Target.MAC)
        .execute();
}
```

`NativeCheckOperation` and `NativeJdkLibrariesOperation` are used the same
way.

That adds three commands, which run on a Mac, for these targets:

| Command | What it does |
|---|---|
| `./bld app-library mac\|ios\|ios-sim\|vision\|vision-sim\|android` | builds `build/native/<target>/`, or that folder of `destinationDirectory(...)`: a framework for Apple's systems, with a privacy manifest that declares what its code uses, or a shared library for Android with the bridge linked in. For iOS and visionOS it also makes `build/native/<name>.xcframework` of the frameworks for their devices and simulators that were built, which Xcode links |
| `./bld app-check mac\|ios-sim\|vision-sim\|android` | asks the native library for the addresses of `nativeApp().addresses()`, on this Mac, in a simulator (which it boots and shuts down again when it wasn't running) or on what adb sees, and compares its answers with the JVM's |
| `./bld app-jdk-libs ios\|ios-sim\|android` | builds the JDK libraries that iOS and Android need and GraalVM doesn't ship, once for every app on this Mac; `android` uses the sources that a run for iOS fetched |

GraalVM has no target for visionOS, so a library for it is built as one for
iOS is, with the JDK libraries for iOS under the platform of visionOS, and
linked against the SDK of visionOS. Both run the same processor
instructions, and the functions that these libraries call exist on both.

Before every native build, the site is asked for those addresses on the
JVM, and goes through the `main` of `nativeApp().walk(...)` when there is
one, with GraalVM's tracing agent, which records what the library needs
that native-image can't see by itself, like the classes that RIFE2 finds by
reflection for a page. The site is told the platform of the target, as the
app tells it, so pages include their script. An address that the site
answers with a server error stops the build; a 404, like `/nothing-here`
above, is checked like any other answer. An address whose answer changes
each time, like a page with a CSRF token or one whose styles and scripts
have `context:paramRandom` in their address, can't be checked by
`app-check`, and is better left to the walk, as is an event stream,
which the checks end with its request.

The walk is a class of the site's own sources, in `src/main/java`, which
asks for what the app would, posts included. It's run with the properties
that the checks tell the site, in system properties that a `NativeApp`
starts with, among them a directory for what the site keeps that's empty
each time. Here the site has a route that takes the post to `/orders`:

```java
public class Walk {
    public static void main(String[] arguments) {
        try (var app = new NativeApp()) {
            var runner = app.runner();
            runner.handle("/");
            runner.handle("/orders", new SiteRequest(RequestMethod.POST)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("title=Leaking+tap"));
        }
    }
}
```

It builds with the GraalVM of `GRAALVM_HOME` when that's set, or else
GraalVM Community Edition 25.5 as sdkman installs it
(`sdk install java 25.5.5.0+3-graalce`), in `SDKMAN_DIR` or `~/.sdkman`.
Android only builds with that exact version, since the container that builds it downloads the same
one for Linux and checks it, and `app-jdk-libs` only with a GraalVM of
JDK 25. It also needs Xcode with its command line tools, and python3.
Without the JDK libraries that `app-jdk-libs` builds, a library for iOS
is built with stand-ins, the macOS libraries with the platform of their
object files rewritten, which is fine in the simulator; a library for a
device that ships uses the real ones. Those are built from the sources of
the JDK in GraalVM with the port of OpenJDK's mobile project, which needs
autoconf and GNU make (`brew install autoconf make`) and takes about ten
minutes the first time.

Android also needs Docker, since native-image builds for it in a Linux
container, the NDK, and an emulator or a device to answer what native-image
asks about Android's C types. The first library for Android is built in
this order:

1. `./bld app-jdk-libs ios-sim`, which fetches the sources of the JDK
2. `./bld app-jdk-libs android`, with the emulator or the device running
3. `./bld app-library android`

## Checking a site

On the JVM, before a native build:

```console
java -cp "<the site's classpath>:build/native/mac/static" rife.nativeapp.NativeApp -Dmy.setting=value /some/page /another
```

prints the status, size and CRC-32 of each answer. Here
`build/native/<target>/static` is the copy of `src/main/webapp` that the
native build made. A `-Dname=value` sets a property of the site first, so
`-Drife.nativeapp.platform=ios` asks as an app would, and the system
property `rife.nativeapp.saved` names a directory that gets each answer as
a file. `checks/ask.c`, linked
with the native library, prints the status, size and CRC-32 of the same
addresses, as `address status size crc`, which the two can be compared by;
`./bld app-check` does that.

From the project of the site, with this repository checked out beside it:

```console
../rife2-native-app/checks/android/check.sh build/native/android/libmysite.so "see:Welcome" "tap:Sign in" "see:Your account"
```

builds an app of the Android classes around a library, installs it on
what adb sees, and goes through it: each step waits until the screen shows
a text, or taps it. It needs build-tools 35.0.0 and platform 35 in the
Android SDK.

## More

RIFE2's wiki has what a site needs for a native image, on
[GraalVM Native Image](https://github.com/rife2/rife2/wiki/GraalVM-Native-Image),
and how a site runs without a server, on
[Site Runner](https://github.com/rife2/rife2/wiki/Site-Runner).

The javadocs of the bld extension are at https://rife2.github.io/rife2-native-app/,
and those of `rife.nativeapp` are in RIFE2's at https://rife2.github.io/rife2/.

## License

Licensed under the Apache License 2.0.
