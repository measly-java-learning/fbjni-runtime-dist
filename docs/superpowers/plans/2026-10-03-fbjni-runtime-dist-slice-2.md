# fbjni-runtime-dist Slice 2 (CI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the smoke tests on every supported platform in GitHub Actions and dry-run the Maven Central publish, after fixing what the slice 1 review found.

**Architecture:** Task 0 lands the slice 1 review fixes. Task 1 lets the smoke tests run on a chosen JVM (version or exact JDK home) and assert it. Task 2 adds `scripts/publish-dry-run.sh`, which signs with a throwaway GPG key, publishes to a local staging repository and checks what Maven Central requires, and trims the javadoc and sources jars. Task 3 creates the public GitHub repo and a CI workflow: verify the native archives' attestations, run the build on five platform/JVM combinations, then the publish dry run.

**Tech Stack:** As slice 1: Gradle 9.6.1 (Kotlin DSL), JDK 25 toolchain with `--release 17`, JUnit Jupiter 6.0.1; GitHub Actions with `actions/checkout` v7.0.1, `actions/setup-java` v6.0.1, `gradle/actions` v6.4.0, `actions/upload-artifact` v7.0.1, all pinned by commit SHA; GnuPG for the dry-run key.

**Spec:** "fbjni-runtime-dist build" section of the design doc, https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77; slice 1 plan, `docs/superpowers/plans/2026-10-03-fbjni-runtime-dist-slice-1.md`

## Global Constraints

- Every constraint in the slice 1 plan's Global Constraints still applies.
- Smoke tests: Linux x86_64 and aarch64, macOS arm64, and macOS x86_64 under Rosetta (an x64 JDK on the arm64 `macos-15` runner); each on JDK 17, and Linux x86_64 also on JDK 25.
- CI uses the runner images' preinstalled JDKs (`JAVA_HOME_17_*`, `JAVA_HOME_25_*`), handed to Gradle explicitly with auto-detect and auto-download off. The only `setup-java` is Zulu 17 x64 for the Rosetta entry, because the arm64 macOS image has no x86_64 JDK.
- The publish dry run needs no secrets and never contacts the Central Portal; Portal validation is slice 3.
- A release is exactly 30 files: jar, sources jar, javadoc jar, POM and Gradle module file, each with `.asc`, `.md5`, `.sha1`, `.asc.md5` and `.asc.sha1`, and no `.sha256` or `.sha512`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.

---

### Task 0 (prerequisite): Slice 1 review fixes

Review of slice 1 (commits `6038af6`..`30bf272`) found no functional defects: `./gradlew clean build --rerun-tasks` passes 30 unit and 4 smoke tests, the jar holds the three libraries, the manifest and nine major-version-61 classes, the smoke tests' classpath holds the jar and no class directories, and a tampered `natives/SHA256SUMS` fails the build. Three fixes remain:

1. Eclipse/Buildship files (`.project`, `.classpath`, `.settings/`, `fbjni-natives/bin/` with compiled classes) show as untracked, so a `git add .` would commit them.
2. `LibraryCacheTest.requirePrivateRejectsAnotherOwner` fails when the tests run as root, as in a container: the temp directory is then owned by root, which is the "other" owner the test uses.
3. `2026-10-01-fbjni-desktop-packaging-design.md` and `docs/architecture.png` are the 1 Oct snapshot of the design. They contradict the current design: fbjni-runtime-dist building natives with Conan, versions `0.8.1`/`0.8.1.1`, `shared` defaulting to True.

**Files:**
- Modify: `.gitignore`
- Modify: `fbjni-natives/src/test/java/org/measly/fbjni/LibraryCacheTest.java` (imports; `requirePrivateRejectsAnotherOwner`)
- Delete: `2026-10-01-fbjni-desktop-packaging-design.md`, `docs/architecture.png`
- Modify: `README.md` (add a Design section above `## Status`)

**Interfaces:**
- Consumes: `LibraryCache.requirePrivate(Path, UserPrincipal)` (slice 1, Task 3). No production code changes.
- Produces: nothing new for later tasks.

- [ ] **Step 1: Ignore IDE files**

Append to `.gitignore`:

```
# Eclipse / Buildship
.project
.classpath
.settings/
bin/
```

Run: `git status --short`
Expected: no `.project`, `.classpath`, `.settings/` or `fbjni-natives/bin/` entries (only the `.gitignore` modification).

- [ ] **Step 2: Skip the other-owner test as root**

In `fbjni-natives/src/test/java/org/measly/fbjni/LibraryCacheTest.java`, add the import:

```java
import static org.junit.jupiter.api.Assumptions.assumeFalse;
```

and replace `requirePrivateRejectsAnotherOwner` with:

```java
    @Test
    void requirePrivateRejectsAnotherOwner() throws IOException {
        UserPrincipal root = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("root");
        // As root (for example in a container) the temp dir is root's own, so root is not another owner
        assumeFalse(root.equals(Files.getOwner(tmp)), "running as root");
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(tmp, root));
    }
```

- [ ] **Step 3: Verify as a normal user, then as root**

Run: `./gradlew :fbjni-natives:test --tests org.measly.fbjni.LibraryCacheTest`
Expected: `BUILD SUCCESSFUL`; `fbjni-natives/build/test-results/test/TEST-org.measly.fbjni.LibraryCacheTest.xml` shows `tests="15" skipped="0" failures="0"`.

Run (as root in a JDK 25 container; Gradle runs on 25 and finds it as the toolchain):

```bash
docker run --rm -v "$PWD":/src:ro eclipse-temurin:25-jdk bash -c \
  'cp -r /src /w && cd /w && ./gradlew --no-daemon -q :fbjni-natives:test --tests org.measly.fbjni.LibraryCacheTest && grep -o "tests=\"[0-9]*\" skipped=\"[0-9]*\" failures=\"[0-9]*\"" fbjni-natives/build/test-results/test/TEST-org.measly.fbjni.LibraryCacheTest.xml'
```

Expected: `tests="15" skipped="1" failures="0"`. Before Step 2's change the same command reports `failures="1"`.

- [ ] **Step 4: Remove the stale design snapshot and link the living design**

Run: `git rm 2026-10-01-fbjni-desktop-packaging-design.md docs/architecture.png`

In `README.md`, insert above `## Status`:

```markdown
## Design

The design, including the loader's cache rules and the build and publish pipeline, is kept in the [fbjni Desktop Packaging design doc](https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77).

```

Run: `grep -rn "architecture.png\|2026-10-01-fbjni" --exclude-dir=.git --exclude-dir=build . | grep -v docs/superpowers`
Expected: no output.

- [ ] **Step 5: Run the full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, 30 unit tests and 4 smoke tests passing.

- [ ] **Step 6: Commit**

```bash
git add .gitignore README.md fbjni-natives/src/test/java/org/measly/fbjni/LibraryCacheTest.java
git commit -m "Slice 1 review fixes: ignore IDE files, root-safe owner test, drop stale design copy

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`git rm` in Step 4 already staged the deletions.)

---

### Task 1: Choosable smoke-test JVM

`smokeTest` always asks for a JDK 17 toolchain. CI also needs JDK 25, and an x86_64 JDK on an arm64 Mac, which a toolchain request cannot express (it has no architecture). Three Gradle properties fix this; the extraction smoke test asserts the JVM it got, so a run on the wrong JVM fails instead of passing quietly.

**Files:**
- Modify: `fbjni-natives/build.gradle.kts` (above `hostResourceDir`; the smoke suite's `testTask.configure` block)
- Modify: `fbjni-natives/src/smokeTest/java/org/measly/fbjni/BundledExtractionSmokeTest.java`
- Modify: `README.md` (Building section)

**Interfaces:**
- Consumes: the `smokeTest` suite from slice 1, Task 4.
- Produces: Gradle properties `smokeJavaVersion` (integer, default `17`), `smokeJavaHome` (path to a JDK; overrides the toolchain), `smokeExpectArch` (expected `os.arch`, optional); smoke-test system properties `fbjni.smoke.javaVersion` and `fbjni.smoke.osArch`; a `smoke JVM: <vendor> <version> <os.arch>` line in the smoke test's captured stdout. Task 3 uses all three properties and greps that line.

- [ ] **Step 1: Make the extraction smoke test assert the requested JVM**

Replace `fbjni-natives/src/smokeTest/java/org/measly/fbjni/BundledExtractionSmokeTest.java` with:

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
        System.out.println("smoke JVM: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version")
                + " " + System.getProperty("os.arch"));
        assertEquals(Integer.getInteger("fbjni.smoke.javaVersion"), Runtime.version().feature(), "smoke-test JVM version");
        String expectedArch = System.getProperty("fbjni.smoke.osArch");
        if (expectedArch != null) {
            assertEquals(expectedArch, System.getProperty("os.arch"), "smoke-test JVM architecture");
        }
        System.setProperty("fbjni.shim.cachedir", cacheDir.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        Path loaded = Path.of(NativeLoader.getLibraryPath("fbjni"));
        assertTrue(loaded.startsWith(cacheDir), loaded + " is not under " + cacheDir);
        assertTrue(Files.isRegularFile(loaded));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :fbjni-natives:smokeTest --tests org.measly.fbjni.BundledExtractionSmokeTest`
Expected: FAIL, `smoke-test JVM version ==> expected: <null> but was: <17>` (the build does not pass `fbjni.smoke.javaVersion` yet).

- [ ] **Step 3: Add the properties to the build**

In `fbjni-natives/build.gradle.kts`, insert directly above the line `// The host's resource directory, for the system-mode smoke test's java.library.path`:

```kotlin
// Smoke-test JVM: a toolchain of smokeJavaVersion (default 17), or the JDK at smokeJavaHome. CI uses
// smokeJavaHome for an x86_64 JDK on an arm64 Mac, since a toolchain request cannot name an architecture.
val smokeJavaVersion = providers.gradleProperty("smokeJavaVersion").getOrElse("17").toInt()
val smokeJavaHome: String? = providers.gradleProperty("smokeJavaHome").orNull
val smokeExpectArch: String? = providers.gradleProperty("smokeExpectArch").orNull

```

In the `smokeTest` suite's `testTask.configure { ... }`, replace

```kotlin
                    javaLauncher = javaToolchains.launcherFor {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
```

with

```kotlin
                    if (smokeJavaHome != null) {
                        executable = File(smokeJavaHome, "bin/java").path
                    } else {
                        javaLauncher = javaToolchains.launcherFor {
                            languageVersion = JavaLanguageVersion.of(smokeJavaVersion)
                        }
                    }
                    // Checked by BundledExtractionSmokeTest, so a run on the wrong JVM fails
                    systemProperty("fbjni.smoke.javaVersion", smokeJavaVersion)
                    if (smokeExpectArch != null) {
                        systemProperty("fbjni.smoke.osArch", smokeExpectArch)
                    }
```

and change the comment above the `register<JvmTestSuite>("smokeTest")` line to:

```kotlin
        // Runs against the built jar, not the class files, on JDK 17 unless smokeJavaVersion or smokeJavaHome says otherwise
```

- [ ] **Step 4: Verify each way of choosing the JVM, including the failures**

Each command below re-runs the smoke tests, then prints the line the extraction test logged. (`os.arch` is `amd64` for x86_64 Linux JVMs, `x86_64` for x86_64 macOS JVMs.)

```bash
smoke() { ./gradlew -q :fbjni-natives:smokeTest --rerun "$@" >/dev/null 2>&1; echo "exit=$? $(grep -ho 'smoke JVM: [^<]*' fbjni-natives/build/test-results/smokeTest/*.xml)"; }
smoke
smoke -PsmokeJavaVersion=25
smoke -PsmokeJavaHome=/usr/lib/jvm/zulu-25-amd64 -PsmokeJavaVersion=25 -PsmokeExpectArch=amd64
smoke -PsmokeJavaHome=/usr/lib/jvm/zulu-25-amd64
smoke -PsmokeExpectArch=x86_64
```

Expected, in order:

```
exit=0 smoke JVM: Azul Systems, Inc. 17.0.19 amd64
exit=0 smoke JVM: Azul Systems, Inc. 25.0.4 amd64
exit=0 smoke JVM: Azul Systems, Inc. 25.0.4 amd64
exit=1 smoke JVM: Azul Systems, Inc. 25.0.4 amd64
exit=1 smoke JVM: Azul Systems, Inc. 17.0.19 amd64
```

(The last two fail on purpose: a JDK 25 home while expecting 17, and the wrong architecture. Patch versions may differ.)

- [ ] **Step 5: Document the properties**

In `README.md`, append to the Building section, after its last paragraph:

```markdown
`-PsmokeJavaVersion=25` runs the smoke tests on another JDK version, and `-PsmokeJavaHome=/path/to/jdk` on a specific JDK; `-PsmokeExpectArch=x86_64` makes them check the JVM's `os.arch`.
```

- [ ] **Step 6: Run the full build and commit**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, 30 unit and 4 smoke tests passing.

```bash
git add fbjni-natives/build.gradle.kts fbjni-natives/src/smokeTest/java/org/measly/fbjni/BundledExtractionSmokeTest.java README.md
git commit -m "Let the smoke tests run on a chosen JVM and assert it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Publish dry run and leaner javadoc and sources jars

Checked while planning: with the build as slice 1 left it, a signed publish also writes `.sha256` and `.sha512` files, the javadoc jar is 4.4 MB because JDK 25's javadoc bundles DejaVu web fonts, and the sources jar carries all three native libraries (they are generated resources). Fixed, a release is 30 files and about 310 KB of jars (main 204 KB, javadoc 101 KB, sources 8 KB), against Central's monthly organization budget of 1,167 files and 78 MB.

**Files:**
- Modify: `gradle.properties`
- Modify: `fbjni-natives/build.gradle.kts` (append after the `mavenPublishing { ... }` block)
- Create: `scripts/publish-dry-run.sh`
- Modify: `README.md` (Building section)

**Interfaces:**
- Consumes: vanniktech's `mavenPublishing` configuration (publication `maven`, task `sourcesJar`, in-memory signing from Gradle properties `signingInMemoryKey` and `signingInMemoryKeyPassword`).
- Produces: Maven repository `staging` at `fbjni-natives/build/staging-repo` (task `publishAllPublicationsToStagingRepository`); `scripts/publish-dry-run.sh [gradle args...]`, exit 0 and a final line `publish dry run OK: 30 files for org.measly:fbjni-natives:<version>`. Task 3 runs the script in CI.

- [ ] **Step 1: Write the dry-run script (the failing check)**

Create `scripts/publish-dry-run.sh` and make it executable (`chmod +x scripts/publish-dry-run.sh`):

```bash
#!/usr/bin/env bash
# Publish dry run: signs fbjni-natives with a throwaway GPG key, publishes it to a local
# staging repository and checks the files Maven Central requires. Needs no secrets.
# Linux only (md5sum, sha1sum). Extra arguments are passed to Gradle.
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."

fail() { echo "publish dry run: $*" >&2; exit 1; }

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill all >/dev/null 2>&1 || true; rm -rf "$GNUPGHOME"' EXIT
passphrase=dry-run
gpg --batch --quiet --pinentry-mode loopback --passphrase "$passphrase" \
    --quick-gen-key "fbjni-runtime-dist dry run <dry-run@invalid>" rsa3072 sign never
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --batch --pinentry-mode loopback --passphrase "$passphrase" --armor --export-secret-keys)"
export ORG_GRADLE_PROJECT_signingInMemoryKey
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$passphrase"

repo=fbjni-natives/build/staging-repo
rm -rf "$repo"
# Keeps the signing key out of the configuration cache, as the org's publish workflows do
./gradlew --no-configuration-cache :fbjni-natives:publishAllPublicationsToStagingRepository "$@"

dirs=("$repo"/org/measly/fbjni-natives/*/)
[ "${#dirs[@]}" -eq 1 ] || fail "expected one version directory under $repo/org/measly/fbjni-natives"
dir="${dirs[0]%/}"
version="$(basename "$dir")"
base="fbjni-natives-$version"

# Five artifacts, each signed; every file and signature has MD5 and SHA-1 and nothing stronger
expected="$(for artifact in "$base.jar" "$base-sources.jar" "$base-javadoc.jar" "$base.pom" "$base.module"; do
    for suffix in "" .md5 .sha1 .asc .asc.md5 .asc.sha1; do echo "$artifact$suffix"; done
done | sort)"
actual="$(ls "$dir" | sort)"
if [ "$actual" != "$expected" ]; then
    diff <(echo "$expected") <(echo "$actual") >&2 || true
    fail "unexpected files in $dir (< expected, > actual)"
fi

for f in "$dir"/*; do
    case "$f" in *.md5 | *.sha1) continue ;; esac
    [ "$(md5sum < "$f" | cut -d' ' -f1)" = "$(cat "$f.md5")" ] || fail "MD5 mismatch for $f"
    [ "$(sha1sum < "$f" | cut -d' ' -f1)" = "$(cat "$f.sha1")" ] || fail "SHA-1 mismatch for $f"
done
for f in "$dir"/*.asc; do
    gpg --batch --quiet --verify "$f" "${f%.asc}" 2>/dev/null || fail "bad signature $f"
done

pom="$dir/$base.pom"
for element in "<groupId>org.measly</groupId>" "<artifactId>fbjni-natives</artifactId>" \
    "<version>$version</version>" "<name>" "<description>" "<url>" "<license>" "<developer>" "<scm>"; do
    grep -qF "$element" "$pom" || fail "$pom lacks $element"
done

# Listing first: under pipefail, `unzip | grep -q` fails when grep exits before unzip finishes
jar_has() {
    local listing
    listing="$(unzip -Z1 "$1")"
    grep -qxF "$2" <<<"$listing" || fail "$(basename "$1") lacks $2"
}
jar_has "$dir/$base.jar" fbjni-natives/macos/libfbjni.dylib
jar_has "$dir/$base-sources.jar" org/measly/fbjni/FbjniShim.java
jar_has "$dir/$base-javadoc.jar" index.html
# Sources only: the libraries are generated resources, not source
if grep -q '^fbjni-natives/' <<<"$(unzip -Z1 "$dir/$base-sources.jar")"; then
    fail "sources jar contains the native libraries"
fi
if grep -q 'resource-files/fonts/' <<<"$(unzip -Z1 "$dir/$base-javadoc.jar")"; then
    fail "javadoc jar bundles fonts (javadoc --no-fonts)"
fi

echo "publish dry run OK: $(echo "$actual" | wc -l | tr -d ' ') files for org.measly:fbjni-natives:$version"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/publish-dry-run.sh -q`
Expected: FAIL; Gradle reports that task `publishAllPublicationsToStagingRepository` is not found in project `:fbjni-natives`.

- [ ] **Step 3: Add the staging repository**

Append to `fbjni-natives/build.gradle.kts`:

```kotlin

// Local repository for scripts/publish-dry-run.sh; Central uploads go through mavenPublishing
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}
```

Run: `scripts/publish-dry-run.sh -q`
Expected: FAIL with `unexpected files`, listing `> ...sha256` and `> ...sha512` lines for every artifact.

- [ ] **Step 4: Drop SHA-256 and SHA-512 checksums**

Append to `gradle.properties`:

```properties

# Central needs only MD5 and SHA-1 beside each file; Gradle's SHA-256/512 files would count
# against the monthly file limit
systemProp.org.gradle.internal.publish.checksums.insecure=true
```

Run: `scripts/publish-dry-run.sh -q`
Expected: FAIL, `publish dry run: sources jar contains the native libraries` (the file set, checksums, signatures and POM now pass).

- [ ] **Step 5: Keep the libraries out of the sources jar and the fonts out of the javadoc jar**

Append to `fbjni-natives/build.gradle.kts`:

```kotlin

// The native libraries are generated resources, not source. vanniktech registers sourcesJar after
// this script runs, so match it lazily by name.
tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
    exclude("fbjni-natives/**")
}

// JDK 23+ javadoc bundles about 4 MB of web fonts; leave them out of the javadoc jar
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("-no-fonts", true)
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `scripts/publish-dry-run.sh -q && stat -c '%s %n' fbjni-natives/build/staging-repo/org/measly/fbjni-natives/*/*.jar`
Expected: `publish dry run OK: 30 files for org.measly:fbjni-natives:0.8.1-1`, then jar sizes of roughly 204 KB (main), 101 KB (javadoc) and 8 KB (sources).

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Document the dry run**

In `README.md`, append to the Building section:

```markdown
`scripts/publish-dry-run.sh` (Linux) signs a release with a throwaway GPG key, publishes it to `fbjni-natives/build/staging-repo` and checks it holds exactly what Maven Central needs. It uses no real keys or credentials and uploads nothing.
```

- [ ] **Step 8: Commit**

```bash
git add gradle.properties fbjni-natives/build.gradle.kts scripts/publish-dry-run.sh README.md
git commit -m "Add a signed publish dry run; trim the javadoc and sources jars

Drops SHA-256/512 checksums, javadoc web fonts and the native libraries
from the sources jar: a release is 30 files and about 310 KB of jars.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: GitHub repo and CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: the GitHub repository `measly-java-learning/fbjni-runtime-dist` (public), remote `origin`

**Interfaces:**
- Consumes: Task 1's `-PsmokeJavaVersion`, `-PsmokeJavaHome`, `-PsmokeExpectArch` and the `smoke JVM:` line; Task 2's `scripts/publish-dry-run.sh [gradle args...]`; slice 1's `fetchNatives` and its cache directory `~/.gradle/caches/fbjni-natives/<fbjniRelease>/`.
- Produces: workflow `CI` with jobs `natives`, `smoke` (5 matrix entries) and `publish-dry-run`, on pushes to `main`, pull requests and manual runs.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  natives:
    name: Verify native archives
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: gradle/actions/wrapper-validation@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0

      - name: Download the pinned archives
        run: ./gradlew :fbjni-natives:fetchNatives

      # Each archive must have been built by fbjni-conan's release workflow at exactly the pinned tag
      - name: Verify build provenance
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          release="$(sed -n 's/^fbjniRelease=//p' gradle.properties)"
          archives=(~/.gradle/caches/fbjni-natives/"$release"/*.tar.gz)
          test "${#archives[@]}" -eq 3
          for archive in "${archives[@]}"; do
            gh attestation verify "$archive" --repo measly-java-learning/fbjni-conan \
              --signer-workflow measly-java-learning/fbjni-conan/.github/workflows/release.yml \
              --source-ref "refs/tags/$release"
          done

  smoke:
    name: smoke ${{ matrix.name }}
    needs: natives
    strategy:
      fail-fast: false
      matrix:
        include:
          - { name: linux-x86_64, runner: ubuntu-24.04, java: "17" }
          - { name: linux-x86_64-jdk25, runner: ubuntu-24.04, java: "25" }
          - { name: linux-aarch64, runner: ubuntu-24.04-arm, java: "17" }
          - { name: macos-arm64, runner: macos-15, java: "17" }
          - { name: macos-x86_64, runner: macos-15, java: "17", rosetta: true, arch: x86_64 }
    runs-on: ${{ matrix.runner }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1

      # Gradle itself runs on the image's default JDK; setup-java below would replace JAVA_HOME
      - name: Remember the default JDK
        run: echo "DEFAULT_JAVA_HOME=$JAVA_HOME" >> "$GITHUB_ENV"

      # The arm64 macOS image has no x86_64 JDK; this one runs the smoke tests under Rosetta
      - name: Install an x86_64 JDK
        id: x64-jdk
        if: matrix.rosetta
        uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: zulu
          java-version: ${{ matrix.java }}
          architecture: x64

      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0

      # The images' JDKs live in the tool cache, where Gradle does not look: hand it every
      # JAVA_HOME_17_* and JAVA_HOME_25_*, and let it find or download nothing else
      - name: Build, unit tests and smoke tests
        env:
          SMOKE_JAVA_VERSION: ${{ matrix.java }}
          SMOKE_JAVA_HOME: ${{ steps.x64-jdk.outputs.path }}
          SMOKE_EXPECT_ARCH: ${{ matrix.arch }}
        run: |
          export JAVA_HOME="$DEFAULT_JAVA_HOME"
          jdks="$(env | grep -E '^JAVA_HOME_(17|25)_' | cut -d= -f2- | paste -sd, -)"
          echo "JDKs for Gradle: $jdks"
          args=(
            -Porg.gradle.java.installations.auto-detect=false
            -Porg.gradle.java.installations.auto-download=false
            "-Porg.gradle.java.installations.paths=$jdks"
            "-PsmokeJavaVersion=$SMOKE_JAVA_VERSION"
          )
          if [ -n "$SMOKE_JAVA_HOME" ]; then
            args+=("-PsmokeJavaHome=$SMOKE_JAVA_HOME")
          fi
          if [ -n "$SMOKE_EXPECT_ARCH" ]; then
            args+=("-PsmokeExpectArch=$SMOKE_EXPECT_ARCH")
          fi
          ./gradlew build "${args[@]}"

      - name: Report the smoke-test JVM
        if: always()
        run: grep -ho 'smoke JVM: [^<]*' fbjni-natives/build/test-results/smokeTest/*.xml || true

      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: reports-${{ matrix.name }}
          path: fbjni-natives/build/reports/

  publish-dry-run:
    name: Publish dry run
    needs: smoke
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0

      - name: Sign, stage and check the Central bundle
        run: |
          jdks="$(env | grep -E '^JAVA_HOME_(17|25)_' | cut -d= -f2- | paste -sd, -)"
          scripts/publish-dry-run.sh \
            -Porg.gradle.java.installations.auto-detect=false \
            -Porg.gradle.java.installations.auto-download=false \
            "-Porg.gradle.java.installations.paths=$jdks"
```

- [ ] **Step 2: Check the workflow locally**

Run: `python3 -c 'import yaml; w = yaml.safe_load(open(".github/workflows/ci.yml")); print(sorted(w["jobs"]), len(w["jobs"]["smoke"]["strategy"]["matrix"]["include"]))'`
Expected: `['natives', 'publish-dry-run', 'smoke'] 5`

Run the provenance check locally against the cached archives (the same command the `natives` job runs):

```bash
release="$(sed -n 's/^fbjniRelease=//p' gradle.properties)"
for archive in ~/.gradle/caches/fbjni-natives/"$release"/*.tar.gz; do
  gh attestation verify "$archive" --repo measly-java-learning/fbjni-conan \
    --signer-workflow measly-java-learning/fbjni-conan/.github/workflows/release.yml \
    --source-ref "refs/tags/$release" >/dev/null && echo "verified $(basename "$archive")"
done
```

Expected: three `verified fbjni-0.8.1-r1-....tar.gz` lines.

Run the CI build command locally with explicit JDKs and nothing else allowed:

```bash
./gradlew build -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.auto-download=false \
  -Porg.gradle.java.installations.paths=/usr/lib/jvm/zulu-17-amd64,/usr/lib/jvm/zulu-25-amd64 -PsmokeJavaVersion=17
```

Expected: `BUILD SUCCESSFUL`. (With only the 17 path, compilation fails with `Cannot find a Java installation ... matching: {languageVersion=25`; that is the loud failure CI relies on.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Add CI: attestation check, five-way smoke matrix, publish dry run

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Create the GitHub repository and push**

The user approved a public repository in `measly-java-learning`, matching fbjni-conan.

```bash
gh repo create measly-java-learning/fbjni-runtime-dist --public --source . --remote origin \
  --description "Desktop builds of fbjni for JVMs, published to Maven Central as org.measly:fbjni-natives"
git push -u origin main
```

Expected: the repository exists and `main` is pushed (`git status -sb` shows `## main...origin/main`).

- [ ] **Step 5: Watch the first CI run until it passes**

```bash
# The run can take a few seconds to appear after the push
until run_id="$(gh run list --repo measly-java-learning/fbjni-runtime-dist --workflow ci.yml --branch main --limit 1 --json databaseId -q '.[0].databaseId')" && [ -n "$run_id" ]; do sleep 5; done
gh run watch "$run_id" --repo measly-java-learning/fbjni-runtime-dist --exit-status
gh run view "$run_id" --repo measly-java-learning/fbjni-runtime-dist --log | grep -E 'smoke JVM:|publish dry run OK|JDKs for Gradle'
```

Expected: all 7 jobs pass (`natives`, five `smoke` entries, `publish-dry-run`). The `smoke JVM:` lines show JDK 17 for four entries and 25 for `linux-x86_64-jdk25`; `os.arch` is `amd64` on linux-x86_64, `aarch64` on linux-aarch64, `aarch64` on macos-arm64 and `x86_64` on macos-x86_64. The last line is `publish dry run OK: 30 files for org.measly:fbjni-natives:0.8.1-1`.

If `gh run watch` loses its connection (GitHub's API sometimes returns 503), re-run it; the run itself is unaffected. If a job fails, debug it from its log and the uploaded `reports-<name>` artifact, fix the cause in a new commit, and push again. Do not weaken a check to make it pass. One known unknown: the ubuntu-24.04-arm image documents its JDK variables as `JAVA_HOME_*_X64`; the step greps `JAVA_HOME_(17|25)_*` with any suffix, and the `JDKs for Gradle:` line shows what it found.
