# fbjni-runtime-dist

Desktop builds of [fbjni](https://github.com/facebookincubator/fbjni) for use with JNI, published to Maven Central as `org.measly:fbjni-natives`.

The native libraries come from a pinned [fbjni-conan](https://github.com/measly-java-learning/fbjni-conan) GitHub Release, built there by the same Conan recipe that C++ projects use. This repo only downloads, verifies and packages them, so its build needs no C++ toolchain.

## What the jar contains

- `libfbjni` for Linux x86_64, Linux aarch64 and macOS (one universal2 dylib, minimum macOS 11.0), built on manylinux_2_28 (glibc 2.28 or later)
- `FbjniShim`, a SoLoader `NativeLoaderDelegate` that extracts the right library to a per-user cache and loads it

It depends on upstream's `com.facebook.fbjni:fbjni-java-only` for the Java classes, and needs Java 17 or later.

## Usage

Add the dependency; it brings in `fbjni-java-only` and SoLoader's `nativeloader`:

```kotlin
dependencies {
    implementation("org.measly:fbjni-natives:0.8.1-1")
}
```

```xml
<dependency>
  <groupId>org.measly</groupId>
  <artifactId>fbjni-natives</artifactId>
  <version>0.8.1-1</version>
</dependency>
```

Then call `FbjniShim.init()` once before using any `com.facebook.jni` class:

```java
org.measly.fbjni.FbjniShim.init();
```

The library is extracted once per version to `-Dfbjni.shim.cachedir`, else `FBJNI_SHIM_CACHE_DIR`, else `~/.cache/fbjni-shim` (honoring `XDG_CACHE_HOME`) on Linux or `~/Library/Caches/fbjni-shim` on macOS, else `java.io.tmpdir`. To provide the library another way:

| Property | Effect |
| --- | --- |
| `-Dfbjni.shim.library=/abs/path/libfbjni.so` | Load that file; nothing is extracted |
| `-Dfbjni.shim.system=true` | `System.loadLibrary("fbjni")` from `java.library.path` |
| `-Dfbjni.shim.static=true` | Load nothing: fbjni is linked into your own JNI library |

## Building

```sh
./gradlew build
```

The build downloads the fbjni-conan release named by `fbjniRelease` in `gradle.properties` and fails unless every archive matches `natives/SHA256SUMS`. To move to a new native build, change both in one commit. Unit tests run on JDK 25; smoke tests load the library from the built jar on JDK 17.

`-PsmokeJavaVersion=25` runs the smoke tests on another JDK version, and `-PsmokeJavaHome=/path/to/jdk` on a specific JDK; `-PsmokeExpectArch=x86_64` makes them check the JVM's `os.arch`.

`scripts/publish-dry-run.sh` (Linux) signs a release with a throwaway GPG key, publishes it to `fbjni-natives/build/staging-repo` and checks it holds exactly what Maven Central needs. It uses no real keys or credentials and uploads nothing.

## Releasing

The Maven version is the fbjni version plus `releaseAttempt` from `gradle.properties` (`0.8.1-1`). Maven Central releases are permanent, so any change to a published version, a packaging fix or a new native build (`fbjniRelease` and `natives/SHA256SUMS`), bumps `releaseAttempt`. Merge that to `main`, wait for CI to pass, then push the tag `v<version>`: `release.yml` runs all of CI again, publishes to Maven Central and creates the GitHub Release.

## Design

The design, including the loader's cache rules and the build and publish pipeline, is kept in the [fbjni Desktop Packaging design doc](https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77).

## Status

Work in progress.

## License

Apache-2.0, matching upstream fbjni.
