#!/usr/bin/env bash
# Prepares what native-image needs to build for Android and GraalVM doesn't ship, once for a
# GraalVM and an NDK:
#
# * the static libraries of the JDK (libjava, libnio, libnet, libzip and the others) for
#   Android, from the sources that build-ios-jdk-libs.sh fetched: the port of OpenJDK's mobile
#   project that it applies to them has Android in it too, where the JDK is the one of Linux
# * GraalVM's own two (libjvm and liblibchelper), from the sources of its commit
# * the answers to what native-image asks of the C types of the system it builds for. It asks
#   by compiling small programs and running them, which it can't do for another system, so it
#   writes the programs out here, the NDK compiles them, and they run on an Android: the
#   emulator or the device that adb sees.
#
#   build-android-jdk-libs.sh
#
# run by `./bld native-jdk-libs android` of bld-native-app, with java_home, work, out and tools as
# for build-ios-jdk-libs.sh, and api, toolchain, compiler and adb of the NDK and the SDK, in the
# environment. Needs what build-ios-jdk-libs.sh needs, and that it ran once.
set -euo pipefail

export JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH"
command -v gmake > /dev/null || { echo "GNU make 4 or later is needed as gmake: brew install make" >&2; exit 1; }

if [ ! -f "$work/src/.ios-port" ] || [ ! -d "$work/graal" ]; then
    echo "./bld native-jdk-libs ios-sim has to run once first: it fetches the sources of the JDK and of GraalVM" >&2
    exit 1
fi
runtime=$(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)"/\1/p' "$java_home/release")

# What the JDK's configure wants and a Mac that builds for Android doesn't have: a font header
# that it only looks for, and one function that Android's C library only declares with
# _GNU_SOURCE, which changes other functions that the JDK uses.
stubs="$work/android-stubs"
mkdir -p "$stubs/fontconfig"
echo "/* only what the configure looks for */" > "$stubs/fontconfig/fontconfig.h"
cat > "$stubs/declared.h" <<'HEADER'
#ifndef RIFE2_ANDROID_DECLARED
#define RIFE2_ANDROID_DECLARED
#ifndef __ASSEMBLER__
int pipe2(int __fds[2], int __flags);
#endif
#endif
HEADER
# on a Mac the configure takes a sysroot for one of Xcode, the NDK's has no frameworks
python3 - "$work/src/make/autoconf/basic.m4" <<'PYTHON'
import sys
path = sys.argv[1]
source = open(path).read()
checked = '    if ! test -f "$SYSROOT/System/Library/Frameworks/Foundation.framework/Headers/Foundation.h"; then'
if checked in source:
    open(path, 'w').write(source.replace(checked, checked.replace('if ! test', 'if test "x$OPENJDK_TARGET_OS" != xandroid && ! test')))
PYTHON

target="-target aarch64-linux-android$api"
# a configure that changed since asks to be run again
if [ "$work/src/make/autoconf/basic.m4" -nt "$work/src/build/android-aarch64/spec.gmk" ]; then
    rm -f "$work/src/build/android-aarch64/spec.gmk"
fi
if [ ! -f "$work/src/build/android-aarch64/spec.gmk" ]; then
    # Without debug symbols: with them the build strips its own tools with the options of
    # Linux's strip, which the Mac's doesn't know. The JVM and the desktop libraries aren't built.
    (cd "$work/src" && bash configure --with-conf-name=android-aarch64 --disable-warnings-as-errors \
        --openjdk-target=aarch64-linux-android --with-toolchain-type=clang \
        --with-sysroot="$toolchain/sysroot" --with-toolchain-path="$toolchain/bin" \
        --with-boot-jdk="$java_home" --with-build-jdk="$java_home" --with-jvm-variants=server \
        --with-cups-include="$(xcrun --sdk macosx --show-sdk-path)/usr/include" --with-fontconfig-include="$stubs" \
        --with-native-debug-symbols=none \
        --with-extra-cflags="$target -include $stubs/declared.h" --with-extra-cxxflags="$target" --with-extra-ldflags="$target" \
        CC="$compiler" CXX="$compiler++" AR="$toolchain/bin/llvm-ar" NM="$toolchain/bin/llvm-nm" STRIP="$toolchain/bin/llvm-strip" \
        OBJCOPY="$toolchain/bin/llvm-objcopy" OBJDUMP="$toolchain/bin/llvm-objdump" READELF="$toolchain/bin/llvm-readelf" > "$work/configure-android.log" 2>&1) ||
        { echo "The JDK's configure failed, see $work/configure-android.log" >&2; exit 1; }
fi
(cd "$work/src" && gmake CONF=android-aarch64 static-libs-graal-image > "$work/build-android.log" 2>&1) ||
    { echo "The JDK's libraries didn't build, see $work/build-android.log" >&2; exit 1; }

libs="$out"
objects="$work/obj-android"
rm -rf "$libs"
mkdir -p "$libs/static" "$libs/clibraries" "$objects/chelper" "$objects/jvm"
cp "$work/src/build/android-aarch64/images/static-libs-graal/lib/"*.a "$libs/static/"

native="$work/graal/substratevm/src"
compile=("$compiler" -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden)
# the flags of these two in substratevm/mx.substratevm/suite.py
for source in "$native"/com.oracle.svm.native.libchelper/src/*.c; do
    "${compile[@]}" -g -O2 -D_LITTLE_ENDIAN -D_FORTIFY_SOURCE=0 -I"$native/com.oracle.svm.native.libchelper/include" \
        -c "$source" -o "$objects/chelper/$(basename "$source" .c).o"
done
NM="$toolchain/bin/llvm-nm" python3 "$tools/jvmfuncs-fallbacks.py" "$native/com.oracle.svm.native.jvm.posix/src/JvmFuncs.c" "$objects/jvm/JvmFuncsFallbacks.c" "$libs/static/"*.a
for source in "$native/com.oracle.svm.native.jvm.posix/src/JvmFuncs.c" "$objects/jvm/JvmFuncsFallbacks.c"; do
    "${compile[@]}" -g -O2 -w -I"$work/src/src/java.base/share/native/include" -I"$work/src/src/java.base/unix/native/include" \
        -c "$source" -o "$objects/jvm/$(basename "$source" .c).o"
done
"$toolchain/bin/llvm-ar" rcs "$libs/clibraries/liblibchelper.a" "$objects"/chelper/*.o
"$toolchain/bin/llvm-ar" rcs "$libs/clibraries/libjvm.a" "$objects"/jvm/*.o
rm -rf "$objects"

# The questions about the C types don't depend on the site, any class does to ask them for.
query="$work/query-android"
rm -rf "$query" "$libs/cap"
mkdir -p "$query/classes" "$query/bin" "$query/cap"
echo "public class Asked { }" > "$query/Asked.java"
javac -d "$query/classes" "$query/Asked.java"
native-image --shared --target=android-aarch64 -H:+UnlockExperimentalVMOptions -H:+ExitAfterQueryCodeGeneration -H:QueryCodeDir="$query" \
    -cp "$query/classes" -o "$query/asked" > "$query/native-image.log" 2>&1 || true
ls "$query"/*.c > /dev/null 2>&1 || { echo "native-image wrote no questions, see $query/native-image.log" >&2; exit 1; }
there=/data/local/tmp/rife2-native-app-query
trap '"$adb" shell rm -rf "$there" > /dev/null 2>&1 || true' EXIT
"$adb" shell mkdir -p "$there" || { echo "adb sees no emulator or device to ask about the C types" >&2; exit 1; }
for source in "$query"/*.c; do
    name=$(basename "$source" .c)
    "$compiler" -I"$java_home/lib/svm/clibraries/include" -I"$work/src/src/java.base/share/native/include" -I"$work/src/src/java.base/unix/native/include" \
        -o "$query/bin/$name" "$source"
    pushed=$("$adb" push "$query/bin/$name" "$there/" 2>&1) || { echo "$pushed" >&2; exit 1; }
    "$adb" shell "chmod 755 $there/$name && $there/$name" > "$query/cap/$name.cap"
done
# in their place only once they're all there
mv "$query/cap" "$libs/cap"
echo "Built $(ls "$libs/static" | wc -l | tr -d ' ') libraries of the JDK $runtime and 2 of GraalVM for Android $api, and $(ls "$libs/cap" | wc -l | tr -d ' ') answers about its C types, in $libs"
