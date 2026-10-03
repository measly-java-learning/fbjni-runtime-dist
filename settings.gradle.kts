plugins {
    // Downloads a missing JDK toolchain (CI); locally, Gradle finds the JDKs in /usr/lib/jvm
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fbjni-runtime-dist"
include("fbjni-natives")
