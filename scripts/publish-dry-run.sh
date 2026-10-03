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
