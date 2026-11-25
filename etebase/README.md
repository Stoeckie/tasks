# Patched etebase library

The app uses `org.tasks:etebase-android` / `org.tasks:etebase-jvm` version `1.0.0-beta02-tasks1`: the
etebase 1.0.0-beta02 client with two patches, built from source into `etebase/repo` (a local Maven
repository, not in git). Gradle takes these two modules only from there (`settings.gradle.kts`), so build
it once after a clean checkout, before the first Gradle build:

```
etebase/build.sh
```

Needs: git, curl, a C compiler and make, rustup (the script installs the Rust toolchain that etebase-java
pins), `ANDROID_HOME` pointing to an Android SDK with NDK 21.3.6528147, and JDK 21 or newer. It builds the
Android ABIs armeabi-v7a, arm64-v8a, x86 and x86_64, and the desktop JVM native for the build host
(linux-x86_64) for the unit tests. Intermediate files go to `etebase/build`.

Patches (source revisions are pinned in `build.sh`):
- `etebase-rs.patch`: `Item.setContent` wrote wrong chunk indices for content with repeated chunks, so
  reading such an item back panicked (index out of bounds); `content()` now returns an error for an
  index out of range instead of panicking. Items written by the patched library read correctly with the
  unpatched one. To be proposed to etesync/etebase-rs.
- `etebase-java.patch`: a panic in a JNI entry point becomes an `EtebaseException` instead of aborting
  the process.

The native libraries are not byte-for-byte reproducible yet (the generated JNI glue emits statics in
hash order). Once both patches are released upstream, the dependency can go back to Maven Central and
this directory can be removed.
