#!/usr/bin/env bash
# Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
# Licensed under the Apache License, Version 2.0 (the "License")

# Builds for iOS the static libraries that native-image links into every image and that GraalVM
# only ships for macOS, run by `./bld native-jdk-libs ios|ios-sim` of bld-native-app:
#
# * those of the JDK (libjava, libnio, libnet, libzip and the others), from the sources of the
#   exact JDK inside the GraalVM in use, with the iOS port of OpenJDK's mobile project applied
#   to them, through the JDK's own build and its static-libs-graal-image target
# * GraalVM's own three (libjvm, liblibchelper and libdarwin), from the sources of the commit
#   that the GraalVM in use was built from, with the flags that GraalVM's build gives them
#
#   build-ios-jdk-libs.sh sim|device
#
# with java_home (the GraalVM), work (where the sources are kept), out (where the libraries go)
# and tools (where jvmfuncs-fallbacks.py is) in the environment.
#
# Needs Xcode, autoconf and GNU make 4 or later as gmake (brew install autoconf make). The first
# run downloads about 1 GB of sources and takes about ten minutes, a run for the other target
# about five. BOOT_JDK names another JDK of the same version to build with than GraalVM itself.
set -euo pipefail

target=${1:-}
case "$target" in
sim) name=iossim; sdk=iphonesimulator; triple=arm64-apple-ios17.0-simulator ;;
device) name=ios; sdk=iphoneos; triple=arm64-apple-ios17.0 ;;
*) echo "Usage: $0 sim|device" >&2; exit 2 ;;
esac

export JAVA_HOME="${java_home:?the GraalVM}" PATH="$java_home/bin:$PATH"
: "${work:?where the sources are kept}" "${out:?where the libraries go}" "${tools:?where jvmfuncs-fallbacks.py is}"
command -v gmake > /dev/null || { echo "GNU make 4 or later is needed as gmake: brew install make" >&2; exit 1; }
command -v autoconf > /dev/null || { echo "autoconf is needed: brew install autoconf" >&2; exit 1; }

# 25.0.3+9-LTS-jvmci-b01 is built from the tag jdk-25.0.3+9 of jdk25u
runtime=$(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)"/\1/p' "$java_home/release")
tag="jdk-${runtime%%-*}"
feature=${runtime%%.*}
graal_commit=$(sed -n 's/^SOURCE=.* substratevm:\([0-9a-f]*\).*/\1/p' "$java_home/release")
# the mobile project follows the main line of the JDK and has no branch for a release: this is
# its last commit before its sources became JDK 26, and the commit of the JDK that it had merged
if [ "$feature" != 25 ]; then
    echo "The commits of the mobile project in this script are those for JDK 25, not $feature" >&2
    exit 1
fi
mobile_port=73d1892dc69
mobile_base=c59e44a7aa2

mkdir -p "$work"
cd "$work"

if [ ! -d mobile ]; then
    git clone --filter=blob:none --no-checkout https://github.com/openjdk/mobile.git mobile
fi
if [ "$(cat src/.ios-port 2>/dev/null)" != "$tag" ]; then
    git -C mobile worktree remove --force ../src 2>/dev/null || rm -rf src
    git -C mobile fetch --filter=blob:none --depth 1 "https://github.com/openjdk/jdk${feature}u.git" "refs/tags/$tag:refs/tags/$tag"
    git -C mobile worktree add ../src "$tag"
    # what the mobile project changed, without its notes for people
    git -C mobile diff "$mobile_base" "$mobile_port" -- . ':!README.md' ':!.jcheck' > mobile-ios.patch
    git -C src apply --3way ../mobile-ios.patch || true
    # 25.0.3 added an include where the port begins its section for iOS: both lines are kept.
    # 25.0.5 added headless builds to the systems without X11, to which the port adds its own.
    python3 - src <<'EOF'
import re, sys

def resolve(path, resolution):
    path = sys.argv[1] + '/' + path
    source = open(path).read()
    open(path, 'w').write(re.sub(r'<<<<<<< ours\n(.*?)=======\n(.*?)>>>>>>> theirs\n', resolution, source, flags=re.S))

def without_x11(conflict):
    ours, port = conflict.group(1), conflict.group(2)
    added = ''.join('     test "x$OPENJDK_TARGET_OS" = x%s ||\n' % system for system in ('android', 'ios') if '= x' + system in port)
    merged = ours.replace(' = xmacosx ||\n', ' = xmacosx ||\n' + added)
    # a conflict of another shape stays one, which stops the script below
    return merged if 'xios' in merged else conflict.group(0)

resolve('src/java.base/macosx/native/libjava/java_props_macosx.c', r'\1\2')
resolve('make/autoconf/libraries.m4', without_x11)
EOF
    unmerged=$(git -C src diff --name-only --diff-filter=U)
    if [ -n "$unmerged" ] && (cd src && grep -lE '^(<<<<<<<|>>>>>>>) ' $unmerged); then
        echo "The port of the mobile project doesn't apply to $tag without help, see the files above" >&2
        exit 1
    fi
    echo "$tag" > src/.ios-port
fi

boot=${BOOT_JDK:-$java_home}
mac_sdk=$(xcrun --sdk macosx --show-sdk-path)
if [ ! -f "src/build/$name-aarch64/spec.gmk" ]; then
    # the JVM and the desktop libraries aren't built, the variant and the printing headers of
    # macOS only let configure get past its checks for them
    (cd src && bash configure --with-conf-name="$name-aarch64" --disable-warnings-as-errors \
        --openjdk-target=aarch64-macos-ios --with-sysroot="$(xcrun --sdk "$sdk" --show-sdk-path)" \
        --with-boot-jdk="$boot" --with-build-jdk="$boot" --with-jvm-variants=server \
        --with-cups-include="$mac_sdk/usr/include" \
        --with-extra-cflags="-target $triple" --with-extra-cxxflags="-target $triple" --with-extra-ldflags="-target $triple")
fi
(cd src && gmake CONF="$name-aarch64" static-libs-graal-image)

if [ "$(git -C graal rev-parse HEAD 2>/dev/null)" != "$graal_commit" ]; then
    rm -rf graal
    git init -q graal
    git -C graal remote add origin https://github.com/oracle/graal.git
    git -C graal fetch --depth 1 --filter=blob:none origin "$graal_commit"
    git -C graal sparse-checkout set --no-cone '/substratevm/src/com.oracle.svm.native.*'
    git -C graal checkout -q FETCH_HEAD
fi

# built apart and moved in place at the end, so that a run that fails leaves nothing that looks built
libs="$out.partial"
objects="$work/obj-$name"
rm -rf "$libs" "$objects"
mkdir -p "$libs/static" "$libs/clibraries" "$objects/chelper" "$objects/darwin" "$objects/jvm"
cp "src/build/$name-aarch64/images/static-libs-graal/lib/"*.a "$libs/static/"

native=graal/substratevm/src
compile=(clang -target "$triple" -isysroot "$(xcrun --sdk "$sdk" --show-sdk-path)" -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden)
# the flags of these three in substratevm/mx.substratevm/suite.py
for source in $native/com.oracle.svm.native.libchelper/src/*.c; do
    "${compile[@]}" -g -gdwarf-5 -O2 -D_LITTLE_ENDIAN -D_FORTIFY_SOURCE=0 -I$native/com.oracle.svm.native.libchelper/include \
        -c "$source" -o "$objects/chelper/$(basename "$source" .c).o"
done
"${compile[@]}" -ObjC -O1 -D_LITTLE_ENDIAN -D_FORTIFY_SOURCE=0 -c $native/com.oracle.svm.native.darwin/src/foundation.c -o "$objects/darwin/foundation.o"
python3 "$tools/jvmfuncs-fallbacks.py" $native/com.oracle.svm.native.jvm.posix/src/JvmFuncs.c "$objects/jvm/JvmFuncsFallbacks.c" "$libs/static/"*.a
for source in $native/com.oracle.svm.native.jvm.posix/src/JvmFuncs.c "$objects/jvm/JvmFuncsFallbacks.c"; do
    "${compile[@]}" -g -O2 -w -Isrc/src/java.base/share/native/include -Isrc/src/java.base/unix/native/include \
        -c "$source" -o "$objects/jvm/$(basename "$source" .c).o"
done
libtool -static -no_warning_for_no_symbols -o "$libs/clibraries/liblibchelper.a" "$objects"/chelper/*.o
libtool -static -o "$libs/clibraries/libdarwin.a" "$objects"/darwin/*.o
libtool -static -o "$libs/clibraries/libjvm.a" "$objects"/jvm/*.o
rm -rf "$objects" "$out"
mv "$libs" "$out"
echo "Built $(ls "$out/static" | wc -l | tr -d ' ') libraries of $tag and 3 of GraalVM $graal_commit for $triple in $out"
