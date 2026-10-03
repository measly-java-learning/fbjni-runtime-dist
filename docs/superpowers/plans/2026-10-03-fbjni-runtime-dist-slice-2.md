# fbjni-runtime-dist Slice 2 (CI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the smoke tests on every supported platform in GitHub Actions and dry-run the Maven Central publish, after fixing what the slice 1 review found.

**Architecture:** Slice 2's CI tasks are added after its design is approved. This file starts with the prerequisite task from the slice 1 review, which must land before any CI work.

**Tech Stack:** As slice 1: Gradle 9.6.1 (Kotlin DSL), JDK 25 toolchain with `--release 17`, JUnit Jupiter 6.0.1.

**Spec:** "fbjni-runtime-dist build" section of the design doc, https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77; slice 1 plan, `docs/superpowers/plans/2026-10-03-fbjni-runtime-dist-slice-1.md`

## Global Constraints

- Every constraint in the slice 1 plan's Global Constraints still applies.
- Smoke tests: Linux x86_64 and aarch64, macOS arm64, and macOS x86_64 under Rosetta (an x64 JDK on the arm64 `macos-15` runner); each on JDK 17, and Linux x86_64 also on JDK 25.
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
