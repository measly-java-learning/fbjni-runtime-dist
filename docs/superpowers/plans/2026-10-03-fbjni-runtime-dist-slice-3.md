# fbjni-runtime-dist Slice 3 (First Release) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish `org.measly:fbjni-natives:0.8.1-1` to Maven Central, after fixing what the slice 2 review found.

**Architecture:** Slice 3's release tasks are added after its design is approved. This file starts with the prerequisite task from the slice 2 review, which must land before any release work.

**Tech Stack:** As slice 2: Gradle 9.6.1 (Kotlin DSL), JDK 25 toolchain with `--release 17`, GitHub Actions with actions pinned by commit SHA.

**Spec:** "fbjni-runtime-dist build" and "Build and publish pipeline" sections of the design doc, https://claude.ai/code/artifact/23b1789d-040e-4483-b481-8d19456cfc77; slice 2 plan, `docs/superpowers/plans/2026-10-03-fbjni-runtime-dist-slice-2.md`

## Global Constraints

- Every constraint in the slice 1 and slice 2 plans' Global Constraints still applies.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

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
