# RIFE2 Native App

Runs a [RIFE2](https://rife2.com) site inside an iOS, macOS or Android app.
GraalVM compiles the site to a native library, and the app's web view gets
its pages from that library in the same process, without a server or a port.

This is what [Gates of Humanity](https://hd.uwyn.com) does on the App Store,
lifted out of it so that another app doesn't have to write it again.

## What's here

| Part | What it does |
|---|---|
| `rife2-native-app` jar | the C functions that the app calls, around RIFE2's `SiteRunner`, and the script that the pages need inside an app |
| `RIFE2NativeApp` Swift package | starts the site's Java in an iOS or macOS app and shows its pages in a web view |
| `android/` | the classes that show the site in an Android app, and the bridge that's linked into the site's library |
| `checks/ask.c` | asks a native library for addresses without an app, to compare its answers with the JVM's |

The bld extension that builds the native libraries is next.

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
        return new MySite(new File(properties.getValueString(NativeApp.DATA)));
    }
}
```

What the app tells the site arrives as properties, which a site can also
read in its `setup()`:

| Property | What it is |
|---|---|
| `rife.nativeapp.platform` | `ios`, `mac` or `android` |
| `rife.nativeapp.data` | the directory where the app keeps what should last; the cookies of the site are kept there |
| `rife.nativeapp.scheme`, `rife.nativeapp.host` | the address that the web view has the site at, `app://site` unless the app says otherwise |
| `rife.nativeapp.temp` | the directory for temporary files and uploads, when Java's own isn't one that the app may write in |
| `rife.nativeapp.version`, `rife.nativeapp.build` | the version of the app |
| `rife.nativeapp.wholeAnswers` | `true` when the app takes each answer all at once on the thread that asked, as on Android; a response that goes on after its request, like an event stream, then ends with it |

The scripts, styles and images of the site are served from the classpath
directory `webapp`, which is where the native build puts `src/main/webapp`.
They're only served for an address that no route of the site takes, so a
route that takes every path keeps them, the script of this jar among them,
from being served.

## The pages

A template includes this in its `<head>`, after its viewport `<meta>`:

```html
<!--v context:nativeApp/-->
```

In a browser, it's empty. Inside an app, it's the script of this jar, which
marks the page with the classes `in-app` and `on-<platform>`, and does what
a web view doesn't do by itself: printing, and on Android also sharing,
saving a file and handing over what a page posts.

## The C functions

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
why, or with `NULL` when there wasn't the memory to say why, 2 cancelled. A request that the site couldn't handle at all is only
told that it failed. Nothing comes after the end, so that's where the app
lets go of its context. The text of a failure is only the app's during the
call. Each request has a number that no request had before, which
`rife2_cancel` takes; a cancel can come before its request.
`rife2_abi` answers 1 and changes when these signatures do.

## The Swift package

An iOS or macOS app adds this repository as a Swift package and links the
framework that native-image built from its site. Then a SwiftUI app shows the
site with:

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
when the system stopped what draws it. An app changes or adds to that by
subclassing it, for instance to add its own message handlers in
`configure(_:)`.

An app extension, like a widget, links the package too and asks
`NativeSite` for what it needs; the web view parts aren't available there.

`NativeSite.scheme`, `NativeSite.host` and `NativeSite.properties` are set
before the first page, when the app wants another address than `app://site`
or has properties of its own for the site. An app extension, like a widget,
starts the site with a heap of 16 MB, since iOS stops an extension that uses
more than a few tens of megabytes; `NativeSite.arguments` changes that.
`NativeSite.shared.get(path)` answers what's at a path all at once, for
what isn't a page in a web view.

## Android

The site's library for Android has the bridge of
`android/src/rife/nativeapp/android/rife2_native_app.c` linked into it, and
the app has the classes of `rife.nativeapp.android`. An activity shows the
site with:

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
<provider android:name="rife.nativeapp.android.NativeShared" android:authorities="com.example.app.rife2.files"
          android:exported="false" android:grantUriPermissions="true"/>
```

`NativeSite.library`, `NativeSite.host` and `NativeSite.properties` are set
before the first request, in the `onCreate` of the app's `Application` when
it has widgets, since a widget can ask the site first. The site is at
`https://<package>.invalid`, since a web view of Android only keeps what a
page stores for an address of the web. That address is where the pages
keep what they store, so an app that changes it starts without what its
pages kept before. `NativeSite.get(context, path)`
answers what's at a path all at once; a response that doesn't end, like an
event stream, is cancelled when the request returns, since Android takes an
answer all at once.

A subclass of `NativePages` adds a JavaScript interface of its own in
`configure(WebView)`. Nothing but the site loads in the web view, since a
page from elsewhere would reach the app's interfaces as well; an app whose
pages show what's on the web allows it there with
`setBlockNetworkLoads(false)`, which also needs the `INTERNET` permission. A
link out of the site goes to Android, except to a file, a content address
or an intent. With a target SDK of 33 or later, the back button comes
through an `OnBackInvokedCallback` rather than `onBackPressed`. A service
worker's requests don't reach the app.

`android/resources/META-INF/proguard/rife2-native-app-android.pro` has the
rules that keep R8 from removing what the native library calls by name. An
app that shrinks its code with R8 uses them.

A web view of Android doesn't hand over what a page posts. The script of
the pages hands it to the app first, for `fetch`, an `XMLHttpRequest` that
isn't synchronous, and a form that doesn't post as plain text. A
synchronous `XMLHttpRequest`, `navigator.sendBeacon` and a worker post
nothing.

## Checking a site

On the JVM, before a native build:

```console
java -cp "<the site's classpath>" rife.nativeapp.NativeApp /some/page /another
```

prints the status, size and CRC-32 of each answer. `checks/ask.c`, linked
with the native library, prints the status, size and CRC-32 of the same
addresses, as `address status size crc`, which the two can be compared by.

## License

Licensed under the Apache License 2.0.
