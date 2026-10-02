# fbjni-runtime-dist

Matrix of desktop builds of [fbjni](https://github.com/facebookincubator/fbjni) for use with JNI, published to Maven Central as `org.measly:fbjni-natives`.

Each build runs the Conan recipe from [fbjni-conan](https://github.com/measly-java-learning/fbjni-conan), so the library a C++ project compiles against and the one a JVM loads come from the same build definition.

## What the jar contains

- `libfbjni` for Linux x86_64, Linux aarch64 and macOS (one universal2 dylib, minimum macOS 11.0)
- `FbjniShim`, a SoLoader `NativeLoaderDelegate` that extracts the right library to a per-user cache and loads it

It depends on upstream's `com.facebook.fbjni:fbjni-java-only` for the Java classes.

## Status

Work in progress.

## License

Apache-2.0, matching upstream fbjni.
