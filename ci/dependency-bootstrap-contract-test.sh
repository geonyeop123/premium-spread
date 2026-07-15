#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

fail() {
  echo "dependency bootstrap behavioral contract failed: $*" >&2
  exit 1
}

expect_failure() {
  local description="$1"
  shift
  if "$@" >"${tmp_dir}/expected-failure.log" 2>&1; then
    fail "expected rejection: ${description}"
  fi
}

git_identity() {
  git config user.name "Dependency Bootstrap Contract"
  git config user.email "dependency-bootstrap-contract@example.invalid"
}

request_repo="${tmp_dir}/request-repo"
mkdir -p "${request_repo}/ci"
cp "${root_dir}/ci/dependency-fingerprint.sh" "${request_repo}/ci/"
cp "${root_dir}/ci/check-dependency-bootstrap-request.sh" "${request_repo}/ci/"
(
  cd "${request_repo}"
  git init --quiet
  git_identity
  printf 'springBootVersion=3.5.16\n' > gradle.properties
  git add gradle.properties ci
  git commit --quiet -m "dependency declarations"
  dependency_sha="$(git rev-parse HEAD)"

  absent_output="${tmp_dir}/absent-output"
  GITHUB_EVENT_NAME=push \
  GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
  GITHUB_SHA="${dependency_sha}" \
  TARGET_SHA="${dependency_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${absent_output}"
  grep -Fxq 'requested=false' "${absent_output}" || fail "missing marker must select the normal strict gate"

  fingerprint="$(bash ci/dependency-fingerprint.sh "${dependency_sha}")"
  expires_at="$(date -u -d '+2 hours' '+%Y-%m-%dT%H:%M:%SZ')"
  printf '%s\n' \
    'schema=1' \
    'request=GENERATE_LOCKS_AND_SHA256' \
    'branch=refactor/infrastructure-boundary' \
    "target_sha=${dependency_sha}" \
    "dependency_fingerprint=${fingerprint}" \
    "expires_at=${expires_at}" > ci/dependency-bootstrap-request
  git add ci/dependency-bootstrap-request
  git commit --quiet -m "dependency bootstrap marker"
  marker_sha="$(git rev-parse HEAD)"
  cp ci/dependency-bootstrap-request "${tmp_dir}/valid-marker"

  valid_output="${tmp_dir}/valid-output"
  GITHUB_EVENT_NAME=push \
  GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
  GITHUB_SHA="${marker_sha}" \
  TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${valid_output}"
  grep -Fxq 'requested=true' "${valid_output}" || fail "valid marker must request generation"
  grep -Fxq "target_sha=${dependency_sha}" "${valid_output}" || fail "valid marker target output is missing"

  expect_failure "pull request event" env \
    GITHUB_EVENT_NAME=pull_request GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/pr-output"
  expect_failure "wrong branch" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/main \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/branch-output"
  expect_failure "candidate SHA mismatch" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${dependency_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/sha-output"

  cp "${tmp_dir}/valid-marker" ci/dependency-bootstrap-request
  sed -i 's/^dependency_fingerprint=.*/dependency_fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/' \
    ci/dependency-bootstrap-request
  expect_failure "fingerprint mismatch" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/fingerprint-output"

  cp "${tmp_dir}/valid-marker" ci/dependency-bootstrap-request
  sed -i 's/^branch=.*/schema=1/' ci/dependency-bootstrap-request
  expect_failure "duplicate key" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/duplicate-output"

  cp "${tmp_dir}/valid-marker" ci/dependency-bootstrap-request
  sed -i 's/^branch=.*/unknown=value/' ci/dependency-bootstrap-request
  expect_failure "unknown key" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/unknown-output"

  cp "${tmp_dir}/valid-marker" ci/dependency-bootstrap-request
  sed -i 's/^expires_at=.*/expires_at=2000-01-01T00:00:00Z/' ci/dependency-bootstrap-request
  expect_failure "expired marker" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/expiry-output"

  cp "${tmp_dir}/valid-marker" ci/dependency-bootstrap-request
  printf 'unexpected=true\n' >> ci/dependency-bootstrap-request
  expect_failure "extra marker line" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${marker_sha}" TARGET_SHA="${marker_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/extra-line-output"

  cp "${tmp_dir}/valid-marker" ci/dependency-bootstrap-request
  printf 'not marker-only\n' > unrelated.txt
  git add ci/dependency-bootstrap-request unrelated.txt
  git commit --quiet --amend --no-edit
  mixed_sha="$(git rev-parse HEAD)"
  expect_failure "marker commit with another path" env \
    GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/refactor/infrastructure-boundary \
    GITHUB_SHA="${mixed_sha}" TARGET_SHA="${mixed_sha}" \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/marker-only-output"
)

output_repo="${tmp_dir}/output-repo"
mkdir -p "${output_repo}/ci"
cp "${root_dir}/ci/validate-dependency-bootstrap-output.sh" "${output_repo}/ci/"
(
  cd "${output_repo}"
  git init --quiet
  git_identity
  generated_files=(
    gradle.lockfile
    apps/api/gradle.lockfile
    apps/batch/gradle.lockfile
    architecture-tests/gradle.lockfile
    build-logic/gradle.lockfile
    domain/gradle.lockfile
    infrastructure/api/gradle.lockfile
    infrastructure/batch/gradle.lockfile
    infrastructure/common/gradle.lockfile
    modules/jpa/gradle.lockfile
    modules/redis/gradle.lockfile
    supports/email/gradle.lockfile
    supports/logging/gradle.lockfile
    supports/monitoring/gradle.lockfile
  )
  for path in "${generated_files[@]}"; do
    mkdir -p "$(dirname "${path}")"
    printf 'locked=1\n' > "${path}"
  done
  valid_xml='<?xml version="1.0" encoding="UTF-8"?><verification-metadata><configuration><verify-metadata>true</verify-metadata></configuration><components><component group="example" name="artifact" version="1"><artifact name="artifact.jar"><sha256 value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/></artifact></component></components></verification-metadata>'
  mkdir -p gradle build-logic/gradle build/reports/dependency-verification build-logic/build/reports/dependency-verification
  printf '%s\n' "${valid_xml}" > gradle/verification-metadata.xml
  printf '%s\n' "${valid_xml}" > build-logic/gradle/verification-metadata.xml
  printf 'root:artifact:1\n' > build/reports/dependency-verification/resolved-artifacts.txt
  printf 'build-logic:artifact:1\n' > build-logic/build/reports/dependency-verification/resolved-artifacts.txt
  printf 'schema=1\n' > ci/dependency-bootstrap-request
  git add .
  git commit --quiet -m "baseline generated files"

  printf 'locked=2\n' > gradle.lockfile
  bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/valid-review"
  [[ -s "${tmp_dir}/valid-review/SHA256SUMS" ]] || fail "valid output must create a review bundle"

  printf 'forbidden\n' > unexpected.txt
  git add unexpected.txt
  expect_failure "staged non-allowlisted generated path" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/forbidden-review"
  git rm --cached --quiet unexpected.txt
  rm unexpected.txt

  printf '%s\n' "${valid_xml/<sha256 value=/<sha512 value=}" > gradle/verification-metadata.xml
  printf '%s\n' "${valid_xml/<\/artifact>/<\/artifact><trusted-keys\/>}" > build-logic/gradle/verification-metadata.xml
  expect_failure "non-SHA256 or trusted-key verification metadata" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/weak-metadata-review"

  printf '%s\n' "${valid_xml}" > build-logic/gradle/verification-metadata.xml
  printf '%s\n' "${valid_xml/<sha256 value=\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\/>/}" \
    > gradle/verification-metadata.xml
  expect_failure "artifact without SHA-256" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/missing-checksum-review"
)

echo "dependency bootstrap behavioral contracts verified"
