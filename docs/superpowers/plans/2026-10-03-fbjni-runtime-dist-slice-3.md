# fbjni-runtime-dist Slice 3 (First Release) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish `org.measly:fbjni-natives:0.8.1-1` to Maven Central, after fixing what the slice 2 review found.

**Architecture:** Task 0 lands the slice 2 review fixes. Task 1 adds a tag-triggered `release.yml`: it runs the whole CI workflow (made reusable), checks the tag matches the build's version, publishes with vanniktech's `publishAndReleaseToMavenCentral` (auto-release, waiting until the Portal validates the deployment), and records a GitHub Release. Task 2 cuts `v0.8.1-1`, after the user's go-ahead.

**Tech Stack:** As slice 2: Gradle 9.6.1 (Kotlin DSL), JDK 25 toolchain with `--release 17`, GitHub Actions with actions pinned by commit SHA.

**Spec:** "fbjni-runtime-dist build" and "Build and publish pipeline" sections of the design doc, https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77; slice 2 plan, `docs/superpowers/plans/2026-10-03-fbjni-runtime-dist-slice-2.md`

## Global Constraints

- Every constraint in the slice 1 and slice 2 plans' Global Constraints still applies.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Releases auto-release to Maven Central, like the org's other repos. Central releases are permanent and cannot be unpublished: a fix is a new `releaseAttempt` (`0.8.1-2`), never a re-upload of a version.
- A release tag is `v<Maven version>` (`v0.8.1-1`) and must equal the version Gradle computes from `fbjniRelease` and `releaseAttempt`.
- Publishing uses the org secrets `ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME`, `ORG_GRADLE_PROJECT_MAVENCENTRALPASSWORD`, `ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEY` and `ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEYPASSWORD` (already visible to this repo), as djl-iree-engine's `publish.yml` does.
- Pushing the release tag publishes permanently: the executor stops and asks the user before that step.
- No post-release download or resolution check: a successful release job is the end of a release.

---

### Task 0 (prerequisite): Slice 2 review fixes

Review of slice 2 (commits `abcfadf`..`1c583e1`) found no functional defects. CI run 37162917331 passed all 7 jobs; each smoke entry ran the intended JVM (Temurin 17 amd64, Temurin 25 amd64, Temurin 17 aarch64 on ubuntu-24.04-arm, Temurin 17 aarch64 on macos-15, Zulu 17 x86_64 under Rosetta); the dry run reported 30 files; `./gradlew clean build` and `scripts/publish-dry-run.sh` pass locally on the same commit. The code matches the plan except one line of `ci.yml` the implementer correctly quoted (the plan's unquoted `run: grep -ho 'smoke JVM: ...'` was invalid YAML). Two CI gaps remain:

1. The "Verify build provenance" step prints nothing on success (`gh attestation verify` is silent without a terminal), so the log holds no evidence that the three archives were checked.
2. No job sets `timeout-minutes`, so a hung job holds a runner for GitHub's default of 6 hours. The slowest job in the first run took 76 seconds.

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the `CI` workflow from slice 2, Task 3.
- Produces: one `verified <archive>` log line per archive in the `natives` job; a 15-minute limit on every job.

- [ ] **Step 1: Log each verified archive**

In `.github/workflows/ci.yml`, in the "Verify build provenance" step, replace

```yaml
            gh attestation verify "$archive" --repo measly-java-learning/fbjni-conan \
              --signer-workflow measly-java-learning/fbjni-conan/.github/workflows/release.yml \
              --source-ref "refs/tags/$release"
          done
```

with

```yaml
            gh attestation verify "$archive" --repo measly-java-learning/fbjni-conan \
              --signer-workflow measly-java-learning/fbjni-conan/.github/workflows/release.yml \
              --source-ref "refs/tags/$release"
            # Reached only on success: the step runs with bash -e
            echo "verified $(basename "$archive")"
          done
```

- [ ] **Step 2: Limit every job to 15 minutes**

Add `timeout-minutes: 15` to each of the three jobs, directly under its `runs-on:` line:

```yaml
  natives:
    name: Verify native archives
    runs-on: ubuntu-24.04
    timeout-minutes: 15
```

```yaml
    runs-on: ${{ matrix.runner }}
    timeout-minutes: 15
```

```yaml
  publish-dry-run:
    name: Publish dry run
    needs: smoke
    runs-on: ubuntu-24.04
    timeout-minutes: 15
```

(The limit counts running time only; time queued for a macOS runner does not count.)

- [ ] **Step 3: Check the workflow locally**

Run: `python3 -c 'import yaml; w = yaml.safe_load(open(".github/workflows/ci.yml")); print({name: job.get("timeout-minutes") for name, job in w["jobs"].items()})'`
Expected: `{'natives': 15, 'smoke': 15, 'publish-dry-run': 15}`

- [ ] **Step 4: Commit, push and confirm in CI**

```bash
git add .github/workflows/ci.yml
git commit -m "CI: log each verified attestation, limit jobs to 15 minutes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push origin main
# The run can take a few seconds to appear after the push
head="$(git rev-parse HEAD)"
until run_id="$(gh run list --repo measly-java-learning/fbjni-runtime-dist --workflow ci.yml --commit "$head" --limit 1 --json databaseId -q '.[0].databaseId')" && [ -n "$run_id" ]; do sleep 5; done
gh run watch "$run_id" --repo measly-java-learning/fbjni-runtime-dist --exit-status
gh run view "$run_id" --repo measly-java-learning/fbjni-runtime-dist --log | grep -E '\sverified fbjni-'
```

Expected: all 7 jobs pass, and three lines ending `verified fbjni-0.8.1-r1-linux-aarch64.tar.gz`, `verified fbjni-0.8.1-r1-linux-x86_64.tar.gz` and `verified fbjni-0.8.1-r1-macos-universal2.tar.gz`. If `gh run watch` drops with an HTTP 503, re-run it; the run itself is unaffected.

---

### Task 1: Release workflow

**Files:**
- Modify: `fbjni-natives/build.gradle.kts` (top of file; first line of `mavenPublishing`)
- Modify: `.github/workflows/ci.yml` (`on:`)
- Create: `.github/workflows/release.yml`
- Modify: `README.md` (Usage; new Releasing section)

**Interfaces:**
- Consumes: the `CI` workflow (Task 0's version), vanniktech's `publishAndReleaseToMavenCentral`, the org secrets in Global Constraints.
- Produces: workflow `Release` on tags `v*`, with jobs `ci` (the whole CI workflow) and `publish`; a GitHub Release per published version, titled `fbjni-natives <version>`. Task 2 uses both.

- [ ] **Step 1: Auto-release, waiting for Portal validation**

In `fbjni-natives/build.gradle.kts`, add as the first line of the file:

```kotlin
import com.vanniktech.maven.publish.DeploymentValidation
```

and in `mavenPublishing { ... }` replace `publishToMavenCentral()` with:

```kotlin
    // Tagged releases upload and release in one step, like the org's other repos. The job waits
    // until the Portal has validated the deployment, so a rejected bundle fails the release job.
    publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.VALIDATED)
```

Run: `./gradlew -q :fbjni-natives:properties --property version | sed -n 's/^version: //p' && scripts/publish-dry-run.sh -q | tail -1`
Expected: `0.8.1-1`, then `publish dry run OK: 30 files for org.measly:fbjni-natives:0.8.1-1`.

- [ ] **Step 2: Make CI callable from the release workflow**

In `.github/workflows/ci.yml`, change the `on:` block to:

```yaml
on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:
  # release.yml runs the whole of CI before publishing
  workflow_call:
```

- [ ] **Step 3: Write the release workflow**

Create `.github/workflows/release.yml`:

```yaml
name: Release

# Pushing a tag v<Maven version> (v0.8.1-1) runs all of CI, then publishes that version to Maven
# Central and records it as a GitHub Release. Central releases are permanent: a fix is a new
# releaseAttempt in gradle.properties (0.8.1-2) and a new tag, never a re-upload.
on:
  push:
    tags: ["v*"]

permissions:
  contents: read

jobs:
  ci:
    uses: ./.github/workflows/ci.yml

  publish:
    name: Publish to Maven Central
    needs: ci
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0

      - name: Check the tag matches the build's version
        id: version
        run: |
          version="$(./gradlew -q :fbjni-natives:properties --property version | sed -n 's/^version: //p')"
          if [ "$GITHUB_REF_NAME" != "v$version" ]; then
            echo "::error::tag $GITHUB_REF_NAME does not match the build's version $version (fbjniRelease and releaseAttempt in gradle.properties)"
            exit 1
          fi
          echo "version=$version" >> "$GITHUB_OUTPUT"
          echo "native=$(sed -n 's/^fbjniRelease=//p' gradle.properties)" >> "$GITHUB_OUTPUT"

      # Keeps the signing key out of the configuration cache, as the org's publish workflows do
      - name: Publish and release
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.ORG_GRADLE_PROJECT_MAVENCENTRALPASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEYPASSWORD }}
        run: |
          jdks="$(env | grep -E '^JAVA_HOME_(17|25)_' | cut -d= -f2- | paste -sd, -)"
          ./gradlew --no-configuration-cache publishAndReleaseToMavenCentral \
            -Porg.gradle.java.installations.auto-detect=false \
            -Porg.gradle.java.installations.auto-download=false \
            "-Porg.gradle.java.installations.paths=$jdks"

      - name: Record the GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
          VERSION: ${{ steps.version.outputs.version }}
          NATIVE: ${{ steps.version.outputs.native }}
        run: |
          cat > notes.md <<EOF
          Maven Central: \`org.measly:fbjni-natives:$VERSION\` ([Central](https://central.sonatype.com/artifact/org.measly/fbjni-natives/$VERSION); it can take up to 30 minutes to appear).

          Packs the libfbjni builds from fbjni-conan [$NATIVE](https://github.com/measly-java-learning/fbjni-conan/releases/tag/$NATIVE): Linux x86_64 and aarch64 (glibc 2.28 or later) and macOS universal2 (macOS 11.0 or later). Needs Java 17 or later.

          Built, tested and published by [this run]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID).
          EOF
          gh release create "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" --verify-tag \
            --title "fbjni-natives $VERSION" --notes-file notes.md
```

- [ ] **Step 4: Check both workflows locally**

Run:

```bash
python3 - <<'PY'
import yaml
ci = yaml.safe_load(open(".github/workflows/ci.yml"))
rel = yaml.safe_load(open(".github/workflows/release.yml"))
# PyYAML reads the key `on` as True
print(sorted(ci[True]), rel["jobs"]["ci"]["uses"], rel["jobs"]["publish"]["needs"], rel["jobs"]["publish"]["permissions"])
PY
```

Expected: `['pull_request', 'push', 'workflow_call', 'workflow_dispatch'] ./.github/workflows/ci.yml ci {'contents': 'write'}`

Run the tag check the way the publish job does, once matching and once not:

```bash
check() { version="$(./gradlew -q :fbjni-natives:properties --property version | sed -n 's/^version: //p')"; [ "$1" = "v$version" ] && echo "match $1" || echo "mismatch $1 (build is $version)"; }
check v0.8.1-1
check v0.8.1-2
```

Expected: `match v0.8.1-1`, then `mismatch v0.8.1-2 (build is 0.8.1-1)`.

- [ ] **Step 5: Document usage and releasing**

In `README.md`, replace the first paragraph of the Usage section (`Call \`FbjniShim.init()\` once before using any \`com.facebook.jni\` class:`) with:

~~~markdown
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
~~~

and insert above `## Design`:

```markdown
## Releasing

The Maven version is the fbjni version plus `releaseAttempt` from `gradle.properties` (`0.8.1-1`). Maven Central releases are permanent, so any change to a published version, a packaging fix or a new native build (`fbjniRelease` and `natives/SHA256SUMS`), bumps `releaseAttempt`. Merge that to `main`, wait for CI to pass, then push the tag `v<version>`: `release.yml` runs all of CI again, publishes to Maven Central and creates the GitHub Release.

```

- [ ] **Step 6: Run the full build and commit**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

```bash
git add fbjni-natives/build.gradle.kts .github/workflows/ci.yml .github/workflows/release.yml README.md
git commit -m "Add the tag-triggered release workflow

Runs all of CI, checks the tag matches the build's version, publishes and
releases to Maven Central once the Portal validates the deployment, and
records a GitHub Release.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: First release, 0.8.1-1

**Files:**
- Modify: `README.md` (Status)

**Interfaces:**
- Consumes: Task 1's `Release` workflow; Task 0's CI fixes.
- Produces: `org.measly:fbjni-natives:0.8.1-1` on Maven Central and the GitHub Release `v0.8.1-1`.

- [ ] **Step 1: Push main and wait for CI**

```bash
git push origin main
head="$(git rev-parse HEAD)"
until run_id="$(gh run list --repo measly-java-learning/fbjni-runtime-dist --workflow ci.yml --commit "$head" --limit 1 --json databaseId -q '.[0].databaseId')" && [ -n "$run_id" ]; do sleep 5; done
gh run watch "$run_id" --repo measly-java-learning/fbjni-runtime-dist --exit-status
```

Expected: all 7 jobs pass. (If Task 0 Step 4 already pushed, this covers Task 1's commit.)

- [ ] **Step 2: Ask the user before tagging**

Stop and ask the user to confirm the release: pushing `v0.8.1-1` publishes `org.measly:fbjni-natives:0.8.1-1` to Maven Central permanently. Continue only on an explicit yes.

- [ ] **Step 3: Tag and push**

```bash
# HEAD is the commit Step 1 pushed and CI passed
git tag -a v0.8.1-1 -m "fbjni-natives 0.8.1-1" HEAD
git push origin v0.8.1-1
until run_id="$(gh run list --repo measly-java-learning/fbjni-runtime-dist --workflow release.yml --limit 1 --json databaseId,headBranch -q '.[] | select(.headBranch == "v0.8.1-1") | .databaseId')" && [ -n "$run_id" ]; do sleep 5; done
gh run watch "$run_id" --repo measly-java-learning/fbjni-runtime-dist --exit-status
gh release view v0.8.1-1 --repo measly-java-learning/fbjni-runtime-dist --json name,url -q '"\(.name) \(.url)"'
```

Expected: the run passes all 8 jobs (the 7 CI jobs, then `Publish to Maven Central`), and `fbjni-natives 0.8.1-1 https://github.com/measly-java-learning/fbjni-runtime-dist/releases/tag/v0.8.1-1`.

If the run fails before the "Publish and release" step (CI or the tag check), nothing was uploaded: fix the cause on `main`, then move the tag (`git push origin :v0.8.1-1`, re-tag the new commit, push). If it fails in or after "Publish and release", do not reuse the version: the Portal may hold a deployment record for it. Read the log, fix the cause, bump `releaseAttempt` to 2 and release `v0.8.1-2`. If only "Record the GitHub Release" failed, the Maven release stands: create the GitHub Release by hand with the same `gh release create` command.

- [ ] **Step 4: Mark the README released**

In `README.md`, replace the Status section's `Work in progress.` with:

```markdown
Released: `0.8.1-1` (fbjni 0.8.1, native build `v0.8.1-r1`). Next: a test JNI library proving that a consumer's own library resolves against the libfbjni the shim loaded.
```

```bash
git add README.md
git commit -m "README: 0.8.1-1 is released

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push origin main
```
