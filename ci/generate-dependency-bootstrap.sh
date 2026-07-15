#!/usr/bin/env bash
set -euo pipefail

target_sha="${1:?dependency target SHA is required}"
review_dir="${2:?review output directory is required}"

fail() {
  echo "dependency bootstrap generation failed: $*" >&2
  exit 1
}

[[ "${target_sha}" =~ ^[0-9a-fA-F]{40}$ ]] || fail "target SHA must be 40-hex"
[[ "$(git rev-parse HEAD^)" == "${target_sha}" ]] || fail "target SHA must be the marker commit parent"
[[ -z "$(git status --porcelain)" ]] || fail "checkout must be clean before generation"

rm -f build-logic/gradle/verification-metadata.xml
./gradlew -p build-logic \
  resolveAndLockAll resolveVerificationArtifacts \
  --write-locks \
  --write-verification-metadata sha256 \
  --refresh-dependencies \
  --no-daemon

rm -f gradle/verification-metadata.xml
./gradlew \
  resolveAndLockAll resolveVerificationArtifacts \
  compileKotlin architectureTest \
  verifyTestIsolationPolicy verifyCoverageExclusions verifySecurityDependencyVersions \
  :build-logic:test \
  --write-locks \
  --write-verification-metadata sha256 \
  --refresh-dependencies \
  --no-daemon

bash ci/validate-dependency-bootstrap-output.sh "${review_dir}"
