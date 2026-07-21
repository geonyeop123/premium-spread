#!/usr/bin/env bash
set -euo pipefail

review_dir="${1:?review output directory is required}"

fail() {
  echo "dependency bootstrap generation failed: $*" >&2
  exit 1
}

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
