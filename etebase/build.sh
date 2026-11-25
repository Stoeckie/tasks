#!/usr/bin/env bash
# Builds org.tasks:etebase-android and org.tasks:etebase-jvm with the patches in this directory into
# etebase/repo (a local Maven repository, not in git). Run once after a clean checkout, before Gradle.
#
# Sources: tasks/etebase-java at the revision of 1.0.0-beta02 and etebase-rs at the revision it pins.
# Patches: etebase-rs.patch (wrong chunk indices for content with repeated chunks, see the commit message),
#          etebase-java.patch (panics in JNI entry points become Java exceptions instead of aborting).
# Needs: git, curl, a C compiler and make, rustup, Android SDK (ANDROID_HOME) with NDK 21.3.6528147, JDK 21+.
# Natives: Android armeabi-v7a, arm64-v8a, x86, x86_64; desktop JVM only for the host (linux-x86_64).
set -euo pipefail

ETEBASE_JAVA_REV=d2d12811027f65fc31ad2fa8fa77c497e236a3cc   # tag 1.0.0-beta02
ETEBASE_RS_REV=ff14aadd7d5bdefee084e8266f0d2642ed268834     # pinned in its Cargo.toml
VERSION=1.0.0-beta02-tasks1
NDK=21.3.6528147

here=$(cd "$(dirname "$0")" && pwd)
work=$here/build
repo=$here/repo
: "${ANDROID_HOME:?set ANDROID_HOME}"
export PATH="$ANDROID_HOME/ndk/$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$HOME/.cargo/bin:$PATH"

checkout() { # url rev dir patch
    if [ ! -d "$3/.git" ]; then git clone -q "$1" "$3"; fi
    git -C "$3" checkout -q --force "$2"
    git -C "$3" clean -q -fdx -e target
    git -C "$3" apply "$4"
}
mkdir -p "$work"
checkout https://github.com/etesync/etebase-rs.git $ETEBASE_RS_REV "$work/etebase-rs" "$here/etebase-rs.patch"
checkout https://github.com/tasks/etebase-java.git $ETEBASE_JAVA_REV "$work/etebase-java" "$here/etebase-java.patch"
cd "$work/etebase-java"
rustup toolchain install --profile minimal "$(sed -n 's/^channel = "\(.*\)"/\1/p' rust-toolchain.toml)"
# Use the patched etebase-rs; Cargo.lock keeps every other dependency pinned.
patch_rs=(--config "patch.'https://github.com/etesync/etebase-rs'.etebase.path='$work/etebase-rs'")
# Allow the -tasksN suffix in the version check.
sed -i 's/(-beta\\d{2}|-SNAPSHOT)?/(-beta\\d{2}(-tasks\\d+)?|-SNAPSHOT)?/' build.gradle

# Android, as in .github/workflows/build.yml of etebase-java
mkdir -p "$work/libunwind-stub" && printf '!<arch>\n' > "$work/libunwind-stub/libunwind.a"
for abi_target_cc in \
    "armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi16-clang" \
    "arm64-v8a aarch64-linux-android aarch64-linux-android21-clang" \
    "x86 i686-linux-android i686-linux-android16-clang" \
    "x86_64 x86_64-linux-android x86_64-linux-android21-clang"; do
    set -- $abi_target_cc
    rustup target add --toolchain "$(rustup show active-toolchain | cut -d' ' -f1)" "$2" > /dev/null
    export CC=$3
    export SODIUM_LIB_DIR=$(./build-libsodium.sh "$2")
    triple_upper=$(echo "$2" | tr 'a-z-' 'A-Z_')
    export "CARGO_TARGET_${triple_upper}_LINKER=$3"
    RUSTFLAGS="-L $work/libunwind-stub -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384" \
        cargo build -q "${patch_rs[@]}" --target "$2" --release
    python3 check-elf-alignment.py "target/$2/release/libetebase_jni.so"
    mkdir -p "client/src/main/jniLibs/$1"
    cp "target/$2/release/libetebase_jni.so" "client/src/main/jniLibs/$1/"
    unset CC
done

# Desktop JVM, host only
host=$(rustc -vV | sed -n 's/^host: //p')
case $host in
    x86_64-unknown-linux-gnu) subdir=linux-x86_64 ;;
    aarch64-unknown-linux-gnu) subdir=linux-aarch64 ;;
    *) echo "unsupported host $host" >&2; exit 1 ;;
esac
SODIUM_LIB_DIR=$(./build-libsodium.sh "$host") cargo build -q "${patch_rs[@]}" --target "$host" --release --no-default-features --features jvm
mkdir -p "client-jvm/src/main/resources/com/etebase/client/native/$subdir"
cp "target/$host/release/libetebase_jni.so" "client-jvm/src/main/resources/com/etebase/client/native/$subdir/"

rm -rf "$repo"
# Local repository: no signing (the Maven Central setup requires it otherwise).
cat > "$work/no-signing.gradle" <<'GRADLE'
allprojects { afterEvaluate { tasks.withType(Sign).configureEach { enabled = false } } }
GRADLE
ETEBASE_VERSION=$VERSION ./gradlew -q --no-daemon --no-configuration-cache --init-script "$work/no-signing.gradle" \
    -Dmaven.repo.local="$repo" :client:publishToMavenLocal :client-jvm:publishToMavenLocal
find "$repo" -name '*.aar' -o -name '*.jar' | sed "s|$here/||"
