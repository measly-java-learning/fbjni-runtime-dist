# fbjni-runtime-dist Slice 1 (Shim and Jar) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `org.measly:fbjni-natives:0.8.1-1` locally: a jar holding libfbjni for Linux x86_64, Linux aarch64 and macOS universal2 from the pinned fbjni-conan release `v0.8.1-r1`, plus the `FbjniShim` loader, proven by unit tests on JDK 25 and smoke tests on JDK 17.

**Architecture:** A Java-only Gradle build. A `fetchNatives` task downloads the three release archives and checks them against a committed `natives/SHA256SUMS`; `prepareNatives` extracts each `libfbjni` into a generated resource directory with a `fbjni-natives.properties` manifest. At runtime `FbjniShim.init()` installs a SoLoader `NativeLoaderDelegate` that extracts the host's library to a content-addressed per-user cache and `System.load`s it.

**Tech Stack:** Gradle 9.6.1 (Kotlin DSL), JDK 25 toolchain compiling with `--release 17`, JUnit Jupiter 6.0.1, `com.facebook.fbjni:fbjni-java-only:0.8.1`, `com.facebook.soloader:nativeloader:0.10.5`, vanniktech maven-publish 0.37.0 (configured, not run in this slice).

**Spec:** "fbjni-runtime-dist build" and "Java loader shim" sections of the design doc, https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77

## Global Constraints

- Maven coordinates `org.measly:fbjni-natives`, version `<fbjni version>-<releaseAttempt>` = `0.8.1-1`.
- Native release pinned as `fbjniRelease=v0.8.1-r1`; archives `fbjni-0.8.1-r1-{linux-x86_64,linux-aarch64,macos-universal2}.tar.gz` from `https://github.com/measly-java-learning/fbjni-conan/releases/download/v0.8.1-r1/`.
- Any SHA-256 mismatch against `natives/SHA256SUMS` fails the build.
- Compile with a JDK 25 toolchain and `options.release = 17` (class file major version 61). Unit tests run on JDK 25; smoke tests run on JDK 17.
- Jar resources: `fbjni-natives/linux-x86_64/libfbjni.so`, `fbjni-natives/linux-aarch64/libfbjni.so`, `fbjni-natives/macos/libfbjni.dylib`, `fbjni-natives/fbjni-natives.properties`.
- `Automatic-Module-Name: org.measly.fbjni.natives`. Java package `org.measly.fbjni`. `FbjniShim.init()` is the only public API.
- Shim properties: `fbjni.shim.library`, `fbjni.shim.system`, `fbjni.shim.static`, `fbjni.shim.cachedir`; environment variable `FBJNI_SHIM_CACHE_DIR`. Cache dirs: `$XDG_CACHE_HOME/fbjni-shim` (default `~/.cache/fbjni-shim`) on Linux, `~/Library/Caches/fbjni-shim` on macOS, then `java.io.tmpdir`.
- Cache path `<cache>/<version>/<resource dir>/<first 16 hex of sha256>/<file name>`; write to a temp file in the same directory, then atomic rename; never overwrite an existing file; a shared tmp subdirectory is mode 0700 and owned by the current user.
- Follow torch-runtime's conventions: `gradle/libs.versions.toml`, foojay resolver in settings, `org.gradle.configuration-cache=true`. Task actions must not capture script-scope references (configuration cache).
- Local machine: default `java` is Zulu 17 (`/etc/alternatives/java` → `/usr/lib/jvm/zulu-17-amd64`), Zulu 25 is at `/usr/lib/jvm/zulu-25-amd64`; Gradle auto-detects both under `/usr/lib/jvm`, so no download is needed. Gradle itself runs on JDK 17.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.

## File Structure

```
fbjni-runtime-dist/
├── settings.gradle.kts            # foojay toolchain resolver, includes fbjni-natives
├── gradle.properties              # Gradle flags, fbjniRelease, releaseAttempt
├── gradle/libs.versions.toml      # versions of fbjni, nativeloader, JUnit, publish plugin
├── gradlew, gradlew.bat, gradle/wrapper/   # Gradle 9.6.1 wrapper
├── natives/SHA256SUMS             # copy of the pinned release's SHA256SUMS
├── README.md                      # usage and build instructions
└── fbjni-natives/
    ├── build.gradle.kts           # toolchains, natives tasks, test suites, publishing
    └── src/
        ├── main/java/org/measly/fbjni/
        │   ├── FbjniShim.java           # public entry point; installs the delegate
        │   ├── FbjniShimDelegate.java   # NativeLoaderDelegate: override modes, extraction, System.load
        │   ├── Platform.java            # os.name/os.arch -> resource dir and file name
        │   ├── BundledLibrary.java      # manifest entry for one platform + how to open its bytes
        │   ├── LibraryCache.java        # cache dir candidates, verified extraction, private tmp dir
        │   ├── ShimEnvironment.java     # system properties and env vars, injectable for tests
        │   └── Sha256.java              # hex SHA-256 of bytes, streams and files
        ├── test/java/org/measly/fbjni/  # unit tests, JDK 25
        │   ├── PlatformTest.java
        │   ├── BundledLibraryTest.java
        │   └── LibraryCacheTest.java
        └── smokeTest/java/org/measly/fbjni/  # one class per JVM, JDK 17, against the built jar
            ├── BundledExtractionSmokeTest.java
            ├── LibraryPropertySmokeTest.java
            ├── SystemPropertySmokeTest.java
            └── StaticPropertySmokeTest.java
```

---

### Task 1: Gradle project and platform mapping

**Files:**
- Create: `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `fbjni-natives/build.gradle.kts`, Gradle wrapper files
- Create: `fbjni-natives/src/main/java/org/measly/fbjni/Platform.java`
- Test: `fbjni-natives/src/test/java/org/measly/fbjni/PlatformTest.java`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `Platform.resourceDir(String osName, String osArch) -> String` returning `Platform.LINUX_X86_64` (`"linux-x86_64"`), `Platform.LINUX_AARCH64` (`"linux-aarch64"`) or `Platform.MACOS` (`"macos"`), throwing `UnsatisfiedLinkError` for anything else; `Platform.fileName(String resourceDir) -> String` returning `"libfbjni.dylib"` for `macos`, else `"libfbjni.so"`.
- Produces: Gradle values `fbjniRelease` (`"v0.8.1-r1"`) and project `version` (`"0.8.1-1"`) used by Task 2.

- [ ] **Step 1: Create the root build files**

`settings.gradle.kts`:

```kotlin
plugins {
    // Downloads a missing JDK toolchain (CI); locally, Gradle finds the JDKs in /usr/lib/jvm
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fbjni-runtime-dist"
include("fbjni-natives")
```

`gradle.properties`:

```properties
org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.caching=true

# fbjni-conan release whose libraries this build packs; natives/SHA256SUMS is that release's
fbjniRelease=v0.8.1-r1
# Release attempt, the org's pkgrev convention: a packaging fix or a retry after a failed
# Central upload gets a new coordinate (0.8.1-1, 0.8.1-2, ...)
releaseAttempt=1
```

`gradle/libs.versions.toml`:

```toml
[versions]
fbjni = "0.8.1"
nativeloader = "0.10.5"
junit = "6.0.1"

[libraries]
fbjni-java-only = { module = "com.facebook.fbjni:fbjni-java-only", version.ref = "fbjni" }
soloader-nativeloader = { module = "com.facebook.soloader:nativeloader", version.ref = "nativeloader" }

[plugins]
maven-publish = { id = "com.vanniktech.maven.publish", version = "0.37.0" }
```

Append to `.gitignore`:

```
.kotlin/
```

- [ ] **Step 2: Generate the wrapper**

Run from the repo root: `gradle wrapper --gradle-version 9.6.1 --distribution-type bin`
Expected: `BUILD SUCCESSFUL`, and `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` exist. (Gradle may warn that `fbjni-natives` has no build file yet; that is fine.)

- [ ] **Step 3: Create the module build**

`fbjni-natives/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "org.measly"

val fbjniRelease: String = providers.gradleProperty("fbjniRelease").get()
val fbjniVersion: String = Regex("""v(\d+\.\d+\.\d+)-r\d+""").matchEntire(fbjniRelease)?.groupValues?.get(1)
    ?: throw GradleException("fbjniRelease must look like v0.8.1-r1, got $fbjniRelease")
if (fbjniVersion != libs.versions.fbjni.get()) {
    throw GradleException("fbjniRelease $fbjniRelease does not match fbjni-java-only ${libs.versions.fbjni.get()}")
}
version = "$fbjniVersion-${providers.gradleProperty("releaseAttempt").get()}"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Upstream's fbjni-java-only 0.8.1 is Java 17 bytecode, so 17 is the floor. --release also
// rejects calls to APIs newer than 17, unlike -source/-target.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    api(libs.fbjni.java.only)
    // Upstream declares nativeloader runtime-only; the shim compiles against it
    api(libs.soloader.nativeloader)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
        }
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.measly.fbjni.natives")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "fbjni-natives", version.toString())

    pom {
        name.set("fbjni natives")
        description.set(
            "Desktop builds of Meta's fbjni (Linux x86_64 and aarch64, macOS universal2) " +
                "with a SoLoader NativeLoader delegate that loads them."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/measly-java-learning/fbjni-runtime-dist")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("corey-cole")
                name.set("Corey Cole")
                url.set("https://github.com/corey-cole")
            }
        }
        scm {
            url.set("https://github.com/measly-java-learning/fbjni-runtime-dist")
            connection.set("scm:git:git://github.com/measly-java-learning/fbjni-runtime-dist.git")
            developerConnection.set("scm:git:ssh://git@github.com/measly-java-learning/fbjni-runtime-dist.git")
        }
    }
}
```

- [ ] **Step 4: Write the failing test**

`fbjni-natives/src/test/java/org/measly/fbjni/PlatformTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PlatformTest {
    @ParameterizedTest
    @CsvSource({
        "Linux, amd64, linux-x86_64",
        "Linux, x86_64, linux-x86_64",
        "Linux, aarch64, linux-aarch64",
        "Linux, arm64, linux-aarch64",
        "Mac OS X, aarch64, macos",
        "Mac OS X, x86_64, macos",
    })
    void mapsSupportedPlatforms(String osName, String osArch, String expected) {
        assertEquals(expected, Platform.resourceDir(osName, osArch));
    }

    @ParameterizedTest
    @CsvSource({
        "Windows 11, amd64",
        "Linux, riscv64",
        "FreeBSD, amd64",
    })
    void rejectsUnsupportedPlatformsWithOverrideHint(String osName, String osArch) {
        UnsatisfiedLinkError e = assertThrows(UnsatisfiedLinkError.class, () -> Platform.resourceDir(osName, osArch));
        assertTrue(e.getMessage().contains(osName + " " + osArch), e.getMessage());
        assertTrue(e.getMessage().contains("-Dfbjni.shim.library="), e.getMessage());
    }

    @Test
    void fileNames() {
        assertEquals("libfbjni.so", Platform.fileName(Platform.LINUX_X86_64));
        assertEquals("libfbjni.so", Platform.fileName(Platform.LINUX_AARCH64));
        assertEquals("libfbjni.dylib", Platform.fileName(Platform.MACOS));
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew :fbjni-natives:test --tests org.measly.fbjni.PlatformTest`
Expected: FAIL at `compileTestJava` with `cannot find symbol ... Platform`.

- [ ] **Step 6: Write the implementation**

`fbjni-natives/src/main/java/org/measly/fbjni/Platform.java`:

```java
package org.measly.fbjni;

import java.util.Locale;

/** Maps the JVM's {@code os.name} and {@code os.arch} to the jar's resource directory for libfbjni. */
final class Platform {
    static final String LINUX_X86_64 = "linux-x86_64";
    static final String LINUX_AARCH64 = "linux-aarch64";
    static final String MACOS = "macos";

    private Platform() {}

    static String resourceDir(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            // One universal2 dylib covers arm64 and x86_64, including x86_64 JVMs under Rosetta
            return MACOS;
        }
        if (os.equals("linux")) {
            switch (osArch) {
                case "amd64":
                case "x86_64":
                    return LINUX_X86_64;
                case "aarch64":
                case "arm64":
                    return LINUX_AARCH64;
                default:
                    break;
            }
        }
        throw new UnsatisfiedLinkError("fbjni-natives has no libfbjni for " + osName + " " + osArch
                + "; it supports Linux x86_64 and aarch64, and macOS arm64 and x86_64."
                + " Provide the library with -Dfbjni.shim.library=/path/to/libfbjni,"
                + " -Dfbjni.shim.system=true or -Dfbjni.shim.static=true.");
    }

    static String fileName(String resourceDir) {
        return resourceDir.equals(MACOS) ? "libfbjni.dylib" : "libfbjni.so";
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass, and check the bytecode level**

Run: `./gradlew :fbjni-natives:test --tests org.measly.fbjni.PlatformTest`
Expected: `BUILD SUCCESSFUL`, 10 tests passed (`build/test-results/test/TEST-org.measly.fbjni.PlatformTest.xml` shows `tests="10" failures="0"`).

Run: `javap -v -cp fbjni-natives/build/classes/java/main org.measly.fbjni.Platform | grep major`
Expected: `major version: 61`

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts gradle.properties gradle/ gradlew gradlew.bat .gitignore fbjni-natives/
git commit -m "Add Gradle build and platform mapping

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Pinned native download and jar resources

**Files:**
- Create: `natives/SHA256SUMS`
- Modify: `fbjni-natives/build.gradle.kts` (add `nativeArchives`, `fetchNatives`, `prepareNatives`, resource wiring, after the `dependencies` block)
- Create: `fbjni-natives/src/main/java/org/measly/fbjni/Sha256.java`, `fbjni-natives/src/main/java/org/measly/fbjni/BundledLibrary.java`
- Test: `fbjni-natives/src/test/java/org/measly/fbjni/BundledLibraryTest.java`

**Interfaces:**
- Consumes: `Platform.fileName(String)`, `Platform.LINUX_X86_64`, `Platform.LINUX_AARCH64`, `Platform.MACOS` (Task 1).
- Produces: `Sha256.hex(byte[])`, `Sha256.hex(InputStream)`, `Sha256.hex(Path)` → lowercase 64-char hex `String` (the last two throw `IOException`).
- Produces: `record BundledLibrary(String version, String resourceDir, String fileName, String sha256, BundledLibrary.Source source)` with `interface Source { InputStream open() throws IOException; }`, `static BundledLibrary forPlatform(String resourceDir)` (throws `UnsatisfiedLinkError` if the manifest or entry is missing), `String resourcePath()` → `"/fbjni-natives/<dir>/<file>"`, `InputStream open() throws IOException`.
- Produces: generated resources `fbjni-natives/<dir>/libfbjni.*` and `fbjni-natives/fbjni-natives.properties` with keys `version`, `nativeRelease`, `sha256.<dir>`; Gradle task `prepareNatives` whose output directory is `fbjni-natives/build/generated/natives`.

- [ ] **Step 1: Commit the pinned checksums**

`natives/SHA256SUMS` (exactly the release's file; two spaces between hash and name):

```
815f387e3e5b4a214eb1ef9dff8fb696f1d8bafd53dfd4e44fbfb1923ab552c4  fbjni-0.8.1-r1-linux-aarch64.tar.gz
3d047206d74442f0df50fb5286d3bb5720f3a83f8b5c92491d3615cf863925be  fbjni-0.8.1-r1-linux-x86_64.tar.gz
fe40467417e10b70ba4e4c190df78f084e3e688b85930983d569247f3bedd8ed  fbjni-0.8.1-r1-macos-universal2.tar.gz
```

Cross-check against the release: `gh release download v0.8.1-r1 --repo measly-java-learning/fbjni-conan -p SHA256SUMS -O - | diff - natives/SHA256SUMS`
Expected: no output.

- [ ] **Step 2: Add the native tasks to the build**

In `fbjni-natives/build.gradle.kts`, insert after the `dependencies { ... }` block:

```kotlin
// Release archive platform suffix -> jar resource directory under fbjni-natives/
val nativeArchives = mapOf(
    "linux-x86_64" to "linux-x86_64",
    "linux-aarch64" to "linux-aarch64",
    "macos-universal2" to "macos",
)
val nativeArchivePrefix = "fbjni-${fbjniRelease.removePrefix("v")}"
// Persistent cache outside build/, so it survives `./gradlew clean`
val nativeArchiveDir = File(gradle.gradleUserHomeDir, "caches/fbjni-natives/$fbjniRelease")

val fetchNatives by tasks.registering {
    description = "Downloads the pinned fbjni-conan release archives and verifies them against natives/SHA256SUMS."
    // Local copies: task actions must not capture script-scope references (configuration cache)
    val release = fbjniRelease
    val archiveDir = nativeArchiveDir
    val archives = nativeArchives.keys.map { "$nativeArchivePrefix-$it.tar.gz" }
    val sums = rootProject.file("natives/SHA256SUMS")
    inputs.file(sums)
    inputs.property("release", release)
    outputs.files(archives.map { File(archiveDir, it) })
    doLast {
        fun sha256(file: File): String = java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        val expected = sums.readLines().filter { it.isNotBlank() }.associate { line ->
            val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
            name to hash
        }
        archiveDir.mkdirs()
        for (name in archives) {
            val want = expected[name] ?: throw GradleException("natives/SHA256SUMS has no entry for $name")
            val file = File(archiveDir, name)
            if (file.exists() && sha256(file) == want) {
                continue
            }
            val url = "https://github.com/measly-java-learning/fbjni-conan/releases/download/$release/$name"
            logger.lifecycle("Downloading $url")
            val part = File(archiveDir, "$name.part")
            java.net.URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val actual = sha256(part)
            if (actual != want) {
                part.delete()
                throw GradleException("$name has SHA-256 $actual, but natives/SHA256SUMS says $want")
            }
            java.nio.file.Files.move(
                part.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}

val prepareNatives by tasks.registering(Sync::class) {
    description = "Extracts libfbjni from each release archive and writes fbjni-natives.properties."
    dependsOn(fetchNatives)
    for ((suffix, dir) in nativeArchives) {
        from(tarTree(File(nativeArchiveDir, "$nativeArchivePrefix-$suffix.tar.gz"))) {
            include("*/lib/libfbjni.*")
            eachFile { path = "fbjni-natives/$dir/$name" }
        }
    }
    into(layout.buildDirectory.dir("generated/natives"))
    includeEmptyDirs = false
    val outDir = layout.buildDirectory.dir("generated/natives/fbjni-natives").get().asFile
    val resourceDirs = nativeArchives.values.sorted()
    val manifestVersion = version.toString()
    val release = fbjniRelease
    inputs.property("version", manifestVersion)
    doLast {
        val lines = mutableListOf("version=$manifestVersion", "nativeRelease=$release")
        for (dir in resourceDirs) {
            val lib = File(outDir, dir).listFiles()?.singleOrNull()
                ?: throw GradleException("expected exactly one libfbjni in $outDir/$dir")
            val hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(lib.readBytes()))
            lines += "sha256.$dir=$hash"
        }
        File(outDir, "fbjni-natives.properties").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

sourceSets {
    main {
        resources.srcDir(prepareNatives)
    }
}
```

- [ ] **Step 3: Run the download and check the generated resources**

Run: `./gradlew :fbjni-natives:prepareNatives && find fbjni-natives/build/generated/natives -type f | sort && cat fbjni-natives/build/generated/natives/fbjni-natives/fbjni-natives.properties`
Expected: three `Downloading https://github.com/...` lines, then exactly these files:

```
fbjni-natives/build/generated/natives/fbjni-natives/fbjni-natives.properties
fbjni-natives/build/generated/natives/fbjni-natives/linux-aarch64/libfbjni.so
fbjni-natives/build/generated/natives/fbjni-natives/linux-x86_64/libfbjni.so
fbjni-natives/build/generated/natives/fbjni-natives/macos/libfbjni.dylib
```

and a properties file with `version=0.8.1-1`, `nativeRelease=v0.8.1-r1` and three `sha256.` lines. Run the same command again; expected: no `Downloading` lines (`fetchNatives` is `UP-TO-DATE`).

- [ ] **Step 4: Prove a checksum mismatch fails the build**

Run: `sed -i '1s/^8/9/' natives/SHA256SUMS && ./gradlew :fbjni-natives:fetchNatives; echo "exit=$?"; git checkout natives/SHA256SUMS`
Expected: `fbjni-0.8.1-r1-linux-aarch64.tar.gz has SHA-256 815f387e..., but natives/SHA256SUMS says 915f387e...`, `BUILD FAILED`, `exit=1`. (The cached archive no longer matches, so the task re-downloads it and rejects the download.) Afterwards run `./gradlew :fbjni-natives:fetchNatives` once more; expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write the failing test**

`fbjni-natives/src/test/java/org/measly/fbjni/BundledLibraryTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BundledLibraryTest {
    @ParameterizedTest
    @ValueSource(strings = {Platform.LINUX_X86_64, Platform.LINUX_AARCH64, Platform.MACOS})
    void manifestHashMatchesBundledBytes(String resourceDir) throws Exception {
        BundledLibrary library = BundledLibrary.forPlatform(resourceDir);
        assertEquals(Platform.fileName(resourceDir), library.fileName());
        assertTrue(library.version().matches("\\d+\\.\\d+\\.\\d+-\\d+"), library.version());
        try (InputStream in = library.open()) {
            assertEquals(library.sha256(), Sha256.hex(in));
        }
    }

    @Test
    void unknownPlatformIsALinkError() {
        assertThrows(UnsatisfiedLinkError.class, () -> BundledLibrary.forPlatform("windows-x86_64"));
    }

    @Test
    void sha256OfKnownInput() throws Exception {
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        byte[] bytes = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(abc, Sha256.hex(bytes));
        assertEquals(abc, Sha256.hex(new ByteArrayInputStream(bytes)));
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./gradlew :fbjni-natives:test --tests org.measly.fbjni.BundledLibraryTest`
Expected: FAIL at `compileTestJava` with `cannot find symbol ... BundledLibrary` and `Sha256`.

- [ ] **Step 7: Write the implementation**

`fbjni-natives/src/main/java/org/measly/fbjni/Sha256.java`:

```java
package org.measly.fbjni;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lowercase hex SHA-256, the form used in fbjni-natives.properties. */
final class Sha256 {
    private Sha256() {}

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String hex(InputStream in) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return hex(in);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("every JVM provides SHA-256", e);
        }
    }
}
```

`fbjni-natives/src/main/java/org/measly/fbjni/BundledLibrary.java`:

```java
package org.measly.fbjni;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The libfbjni this jar carries for one platform, as recorded in fbjni-natives.properties. */
record BundledLibrary(String version, String resourceDir, String fileName, String sha256, Source source) {
    static final String MANIFEST = "/fbjni-natives/fbjni-natives.properties";

    /** Supplies the library's bytes; tests pass their own. */
    interface Source {
        InputStream open() throws IOException;
    }

    static BundledLibrary forPlatform(String resourceDir) {
        Properties manifest = new Properties();
        try (InputStream in = BundledLibrary.class.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("fbjni-natives: " + MANIFEST + " is missing from the jar");
            }
            manifest.load(in);
        } catch (IOException e) {
            throw linkError("fbjni-natives: cannot read " + MANIFEST, e);
        }
        String sha256 = manifest.getProperty("sha256." + resourceDir);
        if (sha256 == null) {
            throw new UnsatisfiedLinkError("fbjni-natives: the jar has no libfbjni for " + resourceDir);
        }
        String fileName = Platform.fileName(resourceDir);
        String path = "/fbjni-natives/" + resourceDir + "/" + fileName;
        return new BundledLibrary(manifest.getProperty("version"), resourceDir, fileName, sha256, () -> {
            InputStream in = BundledLibrary.class.getResourceAsStream(path);
            if (in == null) {
                throw new FileNotFoundException(path + " is missing from the jar");
            }
            return in;
        });
    }

    String resourcePath() {
        return "/fbjni-natives/" + resourceDir + "/" + fileName;
    }

    InputStream open() throws IOException {
        return source.open();
    }

    static UnsatisfiedLinkError linkError(String message, Throwable cause) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
        error.initCause(cause);
        return error;
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :fbjni-natives:test`
Expected: `BUILD SUCCESSFUL`; `BundledLibraryTest` 5 tests and `PlatformTest` 10 tests pass.

- [ ] **Step 9: Commit**

```bash
git add natives/SHA256SUMS fbjni-natives/build.gradle.kts fbjni-natives/src/main/java/org/measly/fbjni/Sha256.java fbjni-natives/src/main/java/org/measly/fbjni/BundledLibrary.java fbjni-natives/src/test/java/org/measly/fbjni/BundledLibraryTest.java
git commit -m "Pack the pinned fbjni-conan v0.8.1-r1 libraries as jar resources

fetchNatives downloads the release archives and fails on any SHA-256
mismatch with natives/SHA256SUMS; prepareNatives extracts libfbjni per
platform and writes fbjni-natives.properties.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Library cache

**Files:**
- Create: `fbjni-natives/src/main/java/org/measly/fbjni/ShimEnvironment.java`, `fbjni-natives/src/main/java/org/measly/fbjni/LibraryCache.java`
- Test: `fbjni-natives/src/test/java/org/measly/fbjni/LibraryCacheTest.java`

**Interfaces:**
- Consumes: `BundledLibrary` record and `BundledLibrary.Source` (Task 2), `Sha256.hex(byte[])` and `Sha256.hex(Path)` (Task 2), `Platform.LINUX_X86_64` (Task 1, tests only).
- Produces: `record ShimEnvironment(Function<String, String> property, Function<String, String> env)` with `static ShimEnvironment system()` and `static ShimEnvironment of(Map<String, String> properties, Map<String, String> env)`.
- Produces: `LibraryCache.CACHE_DIR_PROPERTY` (`"fbjni.shim.cachedir"`), `LibraryCache.CACHE_DIR_ENV` (`"FBJNI_SHIM_CACHE_DIR"`), `record LibraryCache.CacheRoot(Path dir, boolean shared)`, `static List<CacheRoot> candidates(ShimEnvironment env)`, `static Path install(List<CacheRoot> roots, BundledLibrary library) throws IOException`, `static Path install(CacheRoot root, BundledLibrary library) throws IOException`, `static void requirePrivate(Path dir, UserPrincipal expectedOwner) throws IOException`.

- [ ] **Step 1: Write the failing tests**

`fbjni-natives/src/test/java/org/measly/fbjni/LibraryCacheTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryCacheTest {
    private static final byte[] BYTES = "not really a library".getBytes(StandardCharsets.US_ASCII);
    private static final String SHA = Sha256.hex(BYTES);

    @TempDir
    Path tmp;

    private final AtomicInteger opens = new AtomicInteger();

    private BundledLibrary library(byte[] bytes, String sha256) {
        return new BundledLibrary("0.8.1-1", Platform.LINUX_X86_64, "libfbjni.so", sha256, () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        });
    }

    private Path expectedDir(Path root) {
        return root.resolve("0.8.1-1").resolve("linux-x86_64").resolve(SHA.substring(0, 16));
    }

    private static Map<String, String> linuxProperties(String home) {
        return Map.of("os.name", "Linux", "user.home", home, "user.name", "alice", "java.io.tmpdir", "/tmp");
    }

    @Test
    void cacheDirPropertyBeatsEnvironment() {
        Map<String, String> props = new java.util.HashMap<>(linuxProperties("/home/alice"));
        props.put(LibraryCache.CACHE_DIR_PROPERTY, "/from/property");
        ShimEnvironment env = ShimEnvironment.of(props, Map.of(LibraryCache.CACHE_DIR_ENV, "/from/env"));
        assertEquals(List.of(new LibraryCache.CacheRoot(Path.of("/from/property"), false)), LibraryCache.candidates(env));
    }

    @Test
    void cacheDirEnvironmentUsedWithoutProperty() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("/home/alice"), Map.of(LibraryCache.CACHE_DIR_ENV, "/from/env"));
        assertEquals(List.of(new LibraryCache.CacheRoot(Path.of("/from/env"), false)), LibraryCache.candidates(env));
    }

    @Test
    void linuxUsesXdgCacheHomeThenTmp() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("/home/alice"), Map.of("XDG_CACHE_HOME", "/xdg"));
        assertEquals(List.of(
                new LibraryCache.CacheRoot(Path.of("/xdg/fbjni-shim"), false),
                new LibraryCache.CacheRoot(Path.of("/tmp/fbjni-shim-alice"), true)),
                LibraryCache.candidates(env));
    }

    @Test
    void linuxDefaultsToDotCache() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("/home/alice"), Map.of());
        assertEquals(Path.of("/home/alice/.cache/fbjni-shim"), LibraryCache.candidates(env).get(0).dir());
    }

    @Test
    void macosUsesLibraryCaches() {
        ShimEnvironment env = ShimEnvironment.of(
                Map.of("os.name", "Mac OS X", "user.home", "/Users/alice", "user.name", "alice", "java.io.tmpdir", "/tmp"),
                Map.of("XDG_CACHE_HOME", "/xdg"));
        assertEquals(Path.of("/Users/alice/Library/Caches/fbjni-shim"), LibraryCache.candidates(env).get(0).dir());
    }

    @Test
    void missingHomeUsesOnlyTmp() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("?"), Map.of());
        assertEquals(List.of(new LibraryCache.CacheRoot(Path.of("/tmp/fbjni-shim-alice"), true)), LibraryCache.candidates(env));
    }

    @Test
    void installExtractsAVerifiedCopy() throws IOException {
        Path installed = LibraryCache.install(new LibraryCache.CacheRoot(tmp, false), library(BYTES, SHA));
        assertEquals(expectedDir(tmp).resolve("libfbjni.so"), installed);
        assertArrayEquals(BYTES, Files.readAllBytes(installed));
        try (Stream<Path> files = Files.list(expectedDir(tmp))) {
            assertEquals(List.of(installed), files.toList(), "no temp files left behind");
        }
    }

    @Test
    void installReusesAGoodCopyWithoutReextracting() throws IOException {
        LibraryCache.CacheRoot root = new LibraryCache.CacheRoot(tmp, false);
        Path first = LibraryCache.install(root, library(BYTES, SHA));
        Path second = LibraryCache.install(root, library(BYTES, SHA));
        assertEquals(first, second);
        assertEquals(1, opens.get());
    }

    @Test
    void installLeavesACorruptCopyAndExtractsBesideIt() throws IOException {
        Path corrupt = expectedDir(tmp).resolve("libfbjni.so");
        Files.createDirectories(corrupt.getParent());
        Files.write(corrupt, new byte[] {1, 2, 3});
        Path installed = LibraryCache.install(new LibraryCache.CacheRoot(tmp, false), library(BYTES, SHA));
        assertEquals(expectedDir(tmp).resolve("copy-1").resolve("libfbjni.so"), installed);
        assertArrayEquals(BYTES, Files.readAllBytes(installed));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(corrupt), "never overwrite an existing file");
    }

    @Test
    void installRejectsBytesThatDoNotMatchTheManifest() throws IOException {
        byte[] other = "tampered".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> LibraryCache.install(new LibraryCache.CacheRoot(tmp, false), library(other, SHA)));
        try (Stream<Path> files = Files.list(expectedDir(tmp))) {
            assertEquals(0, files.count(), "a failed extraction leaves nothing behind");
        }
    }

    @Test
    void installFallsBackToTheNextRoot() throws IOException {
        Path notADirectory = Files.writeString(tmp.resolve("file"), "x");
        Path fallback = tmp.resolve("fallback");
        Path installed = LibraryCache.install(List.of(
                new LibraryCache.CacheRoot(notADirectory.resolve("cache"), false),
                new LibraryCache.CacheRoot(fallback, false)), library(BYTES, SHA));
        assertEquals(expectedDir(fallback).resolve("libfbjni.so"), installed);
    }

    @Test
    void sharedRootIsCreatedPrivate() throws IOException {
        Path shared = tmp.resolve("fbjni-shim-alice");
        LibraryCache.install(new LibraryCache.CacheRoot(shared, true), library(BYTES, SHA));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)));
    }

    @Test
    void requirePrivateRejectsAnotherOwner() throws IOException {
        UserPrincipal root = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("root");
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(tmp, root));
    }

    @Test
    void requirePrivateRejectsAGroupWritableDirectory() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("open"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(dir, Files.getOwner(dir)));
    }

    @Test
    void requirePrivateRejectsASymlink() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), target);
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(link, Files.getOwner(target)));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :fbjni-natives:test --tests org.measly.fbjni.LibraryCacheTest`
Expected: FAIL at `compileTestJava` with `cannot find symbol ... ShimEnvironment` and `LibraryCache`.

- [ ] **Step 3: Write the implementation**

`fbjni-natives/src/main/java/org/measly/fbjni/ShimEnvironment.java`:

```java
package org.measly.fbjni;

import java.util.Map;
import java.util.function.Function;

/** Where the shim reads system properties and environment variables; tests pass maps instead. */
record ShimEnvironment(Function<String, String> property, Function<String, String> env) {
    static ShimEnvironment system() {
        return new ShimEnvironment(System::getProperty, System::getenv);
    }

    static ShimEnvironment of(Map<String, String> properties, Map<String, String> env) {
        return new ShimEnvironment(properties::get, env::get);
    }
}
```

`fbjni-natives/src/main/java/org/measly/fbjni/LibraryCache.java`:

```java
package org.measly.fbjni;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts the bundled library to a persistent per-user cache. Paths are content-addressed, files
 * appear only by atomic rename, and an existing file is never overwritten, because another process
 * may have it mapped.
 */
final class LibraryCache {
    static final String CACHE_DIR_PROPERTY = "fbjni.shim.cachedir";
    static final String CACHE_DIR_ENV = "FBJNI_SHIM_CACHE_DIR";
    private static final int MAX_COPIES = 16;

    /** A cache directory; {@code shared} marks one under java.io.tmpdir, which must be private. */
    record CacheRoot(Path dir, boolean shared) {}

    private LibraryCache() {}

    /** Cache roots to try, in order. An explicit property or variable is the only candidate. */
    static List<CacheRoot> candidates(ShimEnvironment env) {
        String explicit = nonEmpty(env.property().apply(CACHE_DIR_PROPERTY));
        if (explicit == null) {
            explicit = nonEmpty(env.env().apply(CACHE_DIR_ENV));
        }
        if (explicit != null) {
            return List.of(new CacheRoot(Path.of(explicit), false));
        }
        List<CacheRoot> roots = new ArrayList<>();
        Path platformDir = platformCacheDir(env);
        if (platformDir != null) {
            roots.add(new CacheRoot(platformDir, false));
        }
        String user = nonEmpty(env.property().apply("user.name"));
        String name = user == null ? "fbjni-shim" : "fbjni-shim-" + user;
        roots.add(new CacheRoot(Path.of(env.property().apply("java.io.tmpdir"), name), true));
        return roots;
    }

    private static Path platformCacheDir(ShimEnvironment env) {
        String home = nonEmpty(env.property().apply("user.home"));
        if (home == null || home.equals("?")) {
            return null;
        }
        if (env.property().apply("os.name").toLowerCase(Locale.ROOT).startsWith("mac")) {
            return Path.of(home, "Library", "Caches", "fbjni-shim");
        }
        String xdg = nonEmpty(env.env().apply("XDG_CACHE_HOME"));
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "fbjni-shim");
        }
        return Path.of(home, ".cache", "fbjni-shim");
    }

    /** Installs into the first root that works; throws the first failure if none does. */
    static Path install(List<CacheRoot> roots, BundledLibrary library) throws IOException {
        IOException failure = null;
        for (CacheRoot root : roots) {
            try {
                return install(root, library);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        throw failure;
    }

    static Path install(CacheRoot root, BundledLibrary library) throws IOException {
        if (root.shared()) {
            try {
                Files.createDirectory(root.dir(),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException e) {
                // Someone made it first; requirePrivate decides whether we may use it
            }
            requirePrivate(root.dir(), currentUser(root.dir().getParent()));
        } else {
            Files.createDirectories(root.dir());
        }
        Path dir = root.dir()
                .resolve(library.version())
                .resolve(library.resourceDir())
                .resolve(library.sha256().substring(0, 16));
        for (int i = 0; i < MAX_COPIES; i++) {
            Path target = (i == 0 ? dir : dir.resolve("copy-" + i)).resolve(library.fileName());
            if (Files.exists(target)) {
                if (library.sha256().equals(Sha256.hex(target))) {
                    return target;
                }
                // Corrupt, but another process may have it mapped: leave it and extract beside it
                continue;
            }
            Files.createDirectories(target.getParent());
            return extract(library, target);
        }
        throw new IOException(dir + " holds " + MAX_COPIES + " copies of " + library.fileName()
                + " that fail verification");
    }

    private static Path extract(BundledLibrary library, Path target) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "." + library.fileName() + "-", ".tmp");
        try {
            try (InputStream in = library.open()) {
                Files.copy(in, temp, REPLACE_EXISTING);
            }
            String actual = Sha256.hex(temp);
            if (!actual.equals(library.sha256())) {
                throw new IOException(library.resourcePath() + " has SHA-256 " + actual
                        + ", but fbjni-natives.properties says " + library.sha256());
            }
            // A racing JVM may rename identical bytes onto the same name; rename replaces the
            // directory entry, so a copy already loaded elsewhere stays intact
            Files.move(temp, target, ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Refuses a directory that is a symlink, owned by someone else, or writable by others. */
    static void requirePrivate(Path dir, UserPrincipal expectedOwner) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException(dir + " is not a directory");
        }
        if (!attributes.owner().equals(expectedOwner)) {
            throw new IOException(dir + " is owned by " + attributes.owner().getName()
                    + ", not " + expectedOwner.getName());
        }
        if (attributes.permissions().contains(GROUP_WRITE) || attributes.permissions().contains(OTHERS_WRITE)) {
            throw new IOException(dir + " is writable by other users");
        }
    }

    /** The owner of a file this process creates, which works even without a passwd entry. */
    private static UserPrincipal currentUser(Path dir) throws IOException {
        Path probe = Files.createTempFile(dir, ".fbjni-shim-", ".probe");
        try {
            return Files.getOwner(probe);
        } finally {
            Files.delete(probe);
        }
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :fbjni-natives:test`
Expected: `BUILD SUCCESSFUL`; `LibraryCacheTest` 15 tests pass, plus the 15 from Tasks 1 and 2.

- [ ] **Step 5: Commit**

```bash
git add fbjni-natives/src/main/java/org/measly/fbjni/ShimEnvironment.java fbjni-natives/src/main/java/org/measly/fbjni/LibraryCache.java fbjni-natives/src/test/java/org/measly/fbjni/LibraryCacheTest.java
git commit -m "Add the content-addressed library cache

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Delegate, FbjniShim and JDK 17 smoke tests

**Files:**
- Create: `fbjni-natives/src/main/java/org/measly/fbjni/FbjniShimDelegate.java`, `fbjni-natives/src/main/java/org/measly/fbjni/FbjniShim.java`
- Modify: `fbjni-natives/build.gradle.kts` (the `testing` block, plus `tasks.check`)
- Test: `fbjni-natives/src/smokeTest/java/org/measly/fbjni/{BundledExtraction,LibraryProperty,SystemProperty,StaticProperty}SmokeTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `Platform.resourceDir`, `BundledLibrary.forPlatform`, `BundledLibrary.linkError(String, Throwable)`, `LibraryCache.candidates`, `LibraryCache.install(List<CacheRoot>, BundledLibrary)`, `ShimEnvironment.system()`.
- Produces: `public final class FbjniShim` with `public static void init()`; `final class FbjniShimDelegate implements com.facebook.soloader.nativeloader.NativeLoaderDelegate` with constants `LIBRARY_PROPERTY` (`"fbjni.shim.library"`), `SYSTEM_PROPERTY` (`"fbjni.shim.system"`), `STATIC_PROPERTY` (`"fbjni.shim.static"`). `NativeLoader.getLibraryPath("fbjni")` returns the absolute path the shim loaded, or `null` for the system and static modes.

How the smoke tests prove fbjni works: `new HybridData()` runs fbjni's static initializer, which calls `NativeLoader.loadLibrary("fbjni")`. `resetNative()` calls the native `HybridData$Destructor.deleteNative(0)`, which exists only because libfbjni's `JNI_OnLoad` registered it (`cxx/fbjni/detail/Hybrid.cpp`), so it throws `UnsatisfiedLinkError` unless the library loaded and initialized. `NativeLoader` is global, so each mode runs in its own JVM (`forkEvery = 1`).

- [ ] **Step 1: Add the smoke test suite to the build**

In `fbjni-natives/build.gradle.kts`, replace the `testing { ... }` block with:

```kotlin
// The host's resource directory, for the system-mode smoke test's java.library.path
val hostResourceDir = providers.systemProperty("os.name").get().lowercase().let { os ->
    when {
        os.startsWith("mac") -> "macos"
        providers.systemProperty("os.arch").get() in setOf("amd64", "x86_64") -> "linux-x86_64"
        else -> "linux-aarch64"
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
        }
        // Runs against the built jar, not the class files, on the oldest supported JDK
        register<JvmTestSuite>("smokeTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(files(tasks.jar))
                implementation(libs.fbjni.java.only)
                implementation(libs.soloader.nativeloader)
            }
            targets.all {
                testTask.configure {
                    javaLauncher = javaToolchains.launcherFor {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
                    // NativeLoader is global: one JVM per mode
                    forkEvery = 1
                    systemProperty(
                        "java.library.path",
                        layout.buildDirectory.dir("generated/natives/fbjni-natives/$hostResourceDir").get().asFile.path,
                    )
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("smokeTest"))
}
```

- [ ] **Step 2: Write the failing smoke tests**

`fbjni-natives/src/smokeTest/java/org/measly/fbjni/BundledExtractionSmokeTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.facebook.jni.HybridData;
import com.facebook.soloader.nativeloader.NativeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledExtractionSmokeTest {
    @TempDir
    static Path cacheDir;

    @Test
    void loadsTheBundledLibraryFromTheCache() throws Exception {
        assertEquals(17, Runtime.version().feature(), "smoke tests run on the oldest supported JDK");
        System.setProperty("fbjni.shim.cachedir", cacheDir.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        Path loaded = Path.of(NativeLoader.getLibraryPath("fbjni"));
        assertTrue(loaded.startsWith(cacheDir), loaded + " is not under " + cacheDir);
        assertTrue(Files.isRegularFile(loaded));
    }
}
```

`fbjni-natives/src/smokeTest/java/org/measly/fbjni/LibraryPropertySmokeTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.facebook.jni.HybridData;
import com.facebook.soloader.nativeloader.NativeLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryPropertySmokeTest {
    @TempDir
    static Path dir;

    @Test
    void loadsTheNamedFileWithoutExtracting() throws Exception {
        BundledLibrary bundled = BundledLibrary.forPlatform(
                Platform.resourceDir(System.getProperty("os.name"), System.getProperty("os.arch")));
        Path library = dir.resolve(bundled.fileName());
        try (InputStream in = bundled.open()) {
            Files.copy(in, library);
        }
        Path cache = dir.resolve("cache");
        System.setProperty("fbjni.shim.library", library.toString());
        System.setProperty("fbjni.shim.cachedir", cache.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        assertEquals(library.toAbsolutePath().toString(), NativeLoader.getLibraryPath("fbjni"));
        assertFalse(Files.exists(cache), "nothing should be extracted");
    }
}
```

`fbjni-natives/src/smokeTest/java/org/measly/fbjni/SystemPropertySmokeTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.facebook.jni.HybridData;
import com.facebook.soloader.nativeloader.NativeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build points java.library.path at the host's extracted library. */
class SystemPropertySmokeTest {
    @TempDir
    static Path dir;

    @Test
    void loadsFromJavaLibraryPath() throws Exception {
        Path cache = dir.resolve("cache");
        System.setProperty("fbjni.shim.system", "true");
        System.setProperty("fbjni.shim.cachedir", cache.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        assertNull(NativeLoader.getLibraryPath("fbjni"));
        assertFalse(Files.exists(cache), "nothing should be extracted");
    }
}
```

`fbjni-natives/src/smokeTest/java/org/measly/fbjni/StaticPropertySmokeTest.java`:

```java
package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.facebook.jni.HybridData;
import org.junit.jupiter.api.Test;

/** In static mode the app's own JNI library provides fbjni, so the shim must load nothing. */
class StaticPropertySmokeTest {
    @Test
    void loadsNothing() {
        System.setProperty("fbjni.shim.static", "true");
        FbjniShim.init();
        HybridData data = new HybridData();
        assertThrows(UnsatisfiedLinkError.class, data::resetNative);
    }
}
```

- [ ] **Step 3: Run the smoke tests to verify they fail**

Run: `./gradlew :fbjni-natives:smokeTest`
Expected: FAIL at `compileSmokeTestJava` with `cannot find symbol ... FbjniShim`.

- [ ] **Step 4: Write the implementation**

`fbjni-natives/src/main/java/org/measly/fbjni/FbjniShimDelegate.java`:

```java
package org.measly.fbjni;

import com.facebook.soloader.nativeloader.NativeLoaderDelegate;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Answers fbjni's {@code NativeLoader.loadLibrary("fbjni")}. In priority order: load the file
 * named by {@code fbjni.shim.library}; with {@code fbjni.shim.system=true}, use
 * {@code System.loadLibrary}; with {@code fbjni.shim.static=true}, load nothing; otherwise extract
 * the bundled library to the cache and load it. Other names go to {@code System.loadLibrary}.
 */
final class FbjniShimDelegate implements NativeLoaderDelegate {
    static final String LIBRARY_PROPERTY = "fbjni.shim.library";
    static final String SYSTEM_PROPERTY = "fbjni.shim.system";
    static final String STATIC_PROPERTY = "fbjni.shim.static";

    private final ShimEnvironment env;
    private boolean fbjniLoaded;
    private String fbjniPath;

    FbjniShimDelegate(ShimEnvironment env) {
        this.env = env;
    }

    @Override
    public boolean loadLibrary(String shortName, int flags) {
        if (!"fbjni".equals(shortName)) {
            System.loadLibrary(shortName);
            return true;
        }
        loadFbjni();
        return true;
    }

    private synchronized void loadFbjni() {
        if (fbjniLoaded) {
            return;
        }
        String library = env.property().apply(LIBRARY_PROPERTY);
        if (library != null && !library.isEmpty()) {
            String path = Path.of(library).toAbsolutePath().toString();
            System.load(path);
            fbjniPath = path;
        } else if (Boolean.parseBoolean(env.property().apply(SYSTEM_PROPERTY))) {
            System.loadLibrary("fbjni");
        } else if (Boolean.parseBoolean(env.property().apply(STATIC_PROPERTY))) {
            // The app linked fbjni into its own JNI library, whose JNI_OnLoad registers fbjni's natives
        } else {
            BundledLibrary bundled = BundledLibrary.forPlatform(
                    Platform.resourceDir(env.property().apply("os.name"), env.property().apply("os.arch")));
            Path path;
            try {
                path = LibraryCache.install(LibraryCache.candidates(env), bundled);
            } catch (IOException e) {
                throw BundledLibrary.linkError("fbjni-natives: cannot extract " + bundled.fileName()
                        + " to a cache directory; set -D" + LibraryCache.CACHE_DIR_PROPERTY + " or "
                        + LibraryCache.CACHE_DIR_ENV + " to a writable directory", e);
            }
            System.load(path.toString());
            fbjniPath = path.toString();
        }
        fbjniLoaded = true;
    }

    @Override
    public synchronized String getLibraryPath(String libName) {
        return "fbjni".equals(libName) ? fbjniPath : null;
    }

    @Override
    public int getSoSourcesVersion() {
        return 0;
    }
}
```

`fbjni-natives/src/main/java/org/measly/fbjni/FbjniShim.java`:

```java
package org.measly.fbjni;

import com.facebook.soloader.nativeloader.NativeLoader;

/**
 * Loads the libfbjni bundled in this jar for fbjni's Java classes. Call {@link #init()} once
 * before using any {@code com.facebook.jni} class.
 *
 * <p>The library is extracted once per version to a per-user cache: {@code -Dfbjni.shim.cachedir},
 * else {@code FBJNI_SHIM_CACHE_DIR}, else {@code $XDG_CACHE_HOME/fbjni-shim} or
 * {@code ~/.cache/fbjni-shim} on Linux and {@code ~/Library/Caches/fbjni-shim} on macOS, else
 * {@code java.io.tmpdir}. To skip extraction, set {@code -Dfbjni.shim.library=/path/to/libfbjni},
 * {@code -Dfbjni.shim.system=true} to use {@code java.library.path}, or
 * {@code -Dfbjni.shim.static=true} when fbjni is linked into the app's own JNI library.
 */
public final class FbjniShim {
    static {
        NativeLoader.initIfUninitialized(new FbjniShimDelegate(ShimEnvironment.system()));
    }

    private FbjniShim() {}

    /**
     * Installs the shim as SoLoader's {@code NativeLoader} delegate, unless the app already
     * installed one; such apps must load libfbjni themselves. Safe to call more than once.
     */
    public static void init() {
        // The static initializer does the work, exactly once
    }
}
```

- [ ] **Step 5: Run the smoke tests to verify they pass**

Run: `./gradlew :fbjni-natives:smokeTest`
Expected: `BUILD SUCCESSFUL`, 4 tests passed, each class in its own JVM. Check the launcher: `grep -h 'java.version' fbjni-natives/build/test-results/smokeTest/*.xml | head -1`; expected: a `17.0.` value.

- [ ] **Step 6: Run the whole build and inspect the jar**

Run: `./gradlew build && unzip -l fbjni-natives/build/libs/fbjni-natives-0.8.1-1.jar | grep -E 'fbjni-natives/|MANIFEST' && unzip -p fbjni-natives/build/libs/fbjni-natives-0.8.1-1.jar META-INF/MANIFEST.MF | grep Automatic`
Expected: `BUILD SUCCESSFUL` (unit tests on 25, smoke tests on 17); the jar lists `fbjni-natives/fbjni-natives.properties`, `fbjni-natives/linux-aarch64/libfbjni.so`, `fbjni-natives/linux-x86_64/libfbjni.so`, `fbjni-natives/macos/libfbjni.dylib`; and `Automatic-Module-Name: org.measly.fbjni.natives`.

Run: `unzip -o -q fbjni-natives/build/libs/fbjni-natives-0.8.1-1.jar 'org/*' -d /tmp/fbjni-jar-check && javap -v /tmp/fbjni-jar-check/org/measly/fbjni/FbjniShim.class | grep major; rm -rf /tmp/fbjni-jar-check`
Expected: `major version: 61`.

- [ ] **Step 7: Update the README**

Replace the body of `README.md` between the title and `## Status` with:

~~~markdown
Desktop builds of [fbjni](https://github.com/facebookincubator/fbjni) for use with JNI, published to Maven Central as `org.measly:fbjni-natives`.

The native libraries come from a pinned [fbjni-conan](https://github.com/measly-java-learning/fbjni-conan) GitHub Release, built there by the same Conan recipe that C++ projects use. This repo only downloads, verifies and packages them, so its build needs no C++ toolchain.

## What the jar contains

- `libfbjni` for Linux x86_64, Linux aarch64 and macOS (one universal2 dylib, minimum macOS 11.0), built on manylinux_2_28 (glibc 2.28 or later)
- `FbjniShim`, a SoLoader `NativeLoaderDelegate` that extracts the right library to a per-user cache and loads it

It depends on upstream's `com.facebook.fbjni:fbjni-java-only` for the Java classes, and needs Java 17 or later.

## Usage

Call `FbjniShim.init()` once before using any `com.facebook.jni` class:

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
~~~

- [ ] **Step 8: Commit**

```bash
git add fbjni-natives/build.gradle.kts fbjni-natives/src/main/java/org/measly/fbjni/FbjniShimDelegate.java fbjni-natives/src/main/java/org/measly/fbjni/FbjniShim.java fbjni-natives/src/smokeTest README.md
git commit -m "Add FbjniShim and JDK 17 smoke tests against the built jar

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
