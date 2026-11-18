# Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
# Licensed under the Apache License, Version 2.0 (the "License")

# The bridge in the site's native library calls these by name.
-keep class rife.nativeapp.android.NativeSite$Answer { *; }
-keepclasseswithmembernames class rife.nativeapp.android.NativeSite { native <methods>; }
# What the pages call through the Rife2NativeApp interface.
-keepclassmembers class rife.nativeapp.android.NativePages$* { @android.webkit.JavascriptInterface <methods>; }
