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

assert_exact_output() {
  local output_file="$1"
  local expected="$2"
  [[ "$(cat "${output_file}")" == "${expected}" ]] ||
    fail "unexpected validator output in ${output_file}"
  [[ "$(wc -l < "${output_file}")" -eq 1 ]] ||
    fail "validator output must contain exactly one LF-terminated line"
}

assert_review_layout() {
  local review_dir="$1"
  local expected_entries=(
    build-logic-resolved-artifacts.txt
    files
    review.patch
    root-resolved-artifacts.txt
  )
  local actual_entries=()

  [[ -s "${review_dir}/files/gradle.lockfile" ]] || fail "review files must include gradle.lockfile"
  [[ -s "${review_dir}/review.patch" ]] || fail "review.patch is missing or empty"
  [[ -s "${review_dir}/root-resolved-artifacts.txt" ]] || fail "root resolved-artifact manifest is missing"
  [[ -s "${review_dir}/build-logic-resolved-artifacts.txt" ]] ||
    fail "build-logic resolved-artifact manifest is missing"
  [[ ! -e "${review_dir}/SHA256SUMS" && ! -L "${review_dir}/SHA256SUMS" ]] ||
    fail "review output must not contain SHA256SUMS"
  [[ ! -e "${review_dir}/request-marker.txt" && ! -L "${review_dir}/request-marker.txt" ]] ||
    fail "review output must not contain request-marker.txt"

  mapfile -t actual_entries < <(
    find "${review_dir}" -mindepth 1 -maxdepth 1 -printf '%f\n' | LC_ALL=C sort
  )
  [[ "${actual_entries[*]}" == "${expected_entries[*]}" ]] ||
    fail "review output contains unexpected top-level entries: ${actual_entries[*]}"
  [[ "$(find "${review_dir}/files" -type f | wc -l)" -eq 16 ]] ||
    fail "review files must contain exactly the 16 allowlisted generated files"
}

request_repo="${tmp_dir}/request-repo"
mkdir -p "${request_repo}/ci"
cp "${root_dir}/ci/check-dependency-bootstrap-request.sh" "${request_repo}/ci/"
(
  cd "${request_repo}"
  git init --quiet
  git_identity
  git add ci/check-dependency-bootstrap-request.sh
  git commit --quiet -m "request validator baseline"

  absent_output="${tmp_dir}/absent-output"
  GITHUB_EVENT_NAME=push bash ci/check-dependency-bootstrap-request.sh "${absent_output}"
  assert_exact_output "${absent_output}" 'requested=false'

  expected_marker='request=gradle-dependency-bootstrap-v1'
  printf '%s\n' "${expected_marker}" > ci/dependency-bootstrap-request
  git add ci/dependency-bootstrap-request
  git commit --quiet -m "fixed dependency bootstrap marker"

  valid_output="${tmp_dir}/valid-output"
  GITHUB_EVENT_NAME=pull_request bash ci/check-dependency-bootstrap-request.sh "${valid_output}"
  assert_exact_output "${valid_output}" 'requested=true'

  expect_failure "push event" env GITHUB_EVENT_NAME=push \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/push-output"

  rm ci/dependency-bootstrap-request
  ln -s check-dependency-bootstrap-request.sh ci/dependency-bootstrap-request
  expect_failure "symlink marker" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/symlink-output"
  git restore ci/dependency-bootstrap-request

  rm ci/dependency-bootstrap-request
  ln -s check-dependency-bootstrap-request.sh ci/dependency-bootstrap-request
  git add ci/dependency-bootstrap-request
  rm ci/dependency-bootstrap-request
  printf '%s\n' "${expected_marker}" > ci/dependency-bootstrap-request
  expect_failure "Git mode 120000" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/mode-output"
  git restore --staged ci/dependency-bootstrap-request
  git restore ci/dependency-bootstrap-request

  printf '%s\r\n' "${expected_marker}" > ci/dependency-bootstrap-request
  expect_failure "CRLF marker" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/crlf-output"

  printf '%s\nunexpected=true\n' "${expected_marker}" > ci/dependency-bootstrap-request
  expect_failure "extra marker line" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/extra-line-output"

  printf 'request=another-value\n' > ci/dependency-bootstrap-request
  expect_failure "different marker value" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/different-value-output"

  [[ "$(git ls-files -s -- ci/dependency-bootstrap-request | awk '{print $1}')" == "100644" ]] ||
    fail "binary marker fixture must retain Git mode 100644"
  printf '%s\0\n' "${expected_marker}" > ci/dependency-bootstrap-request
  expect_failure "binary extra NUL byte marker" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/binary-extra-byte-output"

  printf '%s' "${expected_marker}" > ci/dependency-bootstrap-request
  expect_failure "marker without final LF" env GITHUB_EVENT_NAME=pull_request \
    bash ci/check-dependency-bootstrap-request.sh "${tmp_dir}/missing-lf-output"
)

valid_xml='<?xml version="1.0" encoding="UTF-8"?><verification-metadata><configuration><verify-metadata>true</verify-metadata></configuration><components><component group="example" name="artifact" version="1"><artifact name="artifact.jar"><sha256 value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/></artifact></component></components></verification-metadata>'
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
  gradle/verification-metadata.xml
  build-logic/gradle/verification-metadata.xml
)

output_repo="${tmp_dir}/output-repo"
mkdir -p "${output_repo}/ci"
cp "${root_dir}/ci/validate-dependency-bootstrap-output.sh" "${output_repo}/ci/"
(
  cd "${output_repo}"
  git init --quiet
  git_identity
  printf '/build/\n/build-logic/build/\n' > .gitignore
  for path in "${generated_files[@]}"; do
    mkdir -p "$(dirname "${path}")"
    if [[ "${path}" == */verification-metadata.xml ]]; then
      printf '%s\n' "${valid_xml}" > "${path}"
    else
      printf 'locked=1\n' > "${path}"
    fi
  done
  mkdir -p build/reports/dependency-verification build-logic/build/reports/dependency-verification
  printf 'root:artifact:1\n' > build/reports/dependency-verification/resolved-artifacts.txt
  printf 'build-logic:artifact:1\n' > build-logic/build/reports/dependency-verification/resolved-artifacts.txt
  git add .
  git commit --quiet -m "baseline generated files"

  printf 'locked=2\n' > gradle.lockfile
  bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/valid-review"
  assert_review_layout "${tmp_dir}/valid-review"

  printf 'forbidden\n' > unexpected.txt
  git add unexpected.txt
  expect_failure "staged non-allowlisted generated path" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/forbidden-review"
  git rm --cached --quiet unexpected.txt
  rm unexpected.txt

  printf '%s\n' "${valid_xml/<sha256 value=/<sha512 value=}" > gradle/verification-metadata.xml
  printf '%s\n' "${valid_xml/<\/artifact>/<\/artifact><trusted-keys\/>}" \
    > build-logic/gradle/verification-metadata.xml
  expect_failure "non-SHA256 or trusted-key verification metadata" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/weak-metadata-review"

  printf '%s\n' "${valid_xml}" > build-logic/gradle/verification-metadata.xml
  printf '%s\n' "${valid_xml/<sha256 value=\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\/>/}" \
    > gradle/verification-metadata.xml
  expect_failure "artifact without SHA-256" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/missing-checksum-review"

  printf '%s\n' "${valid_xml}" > gradle/verification-metadata.xml
  rm build/reports/dependency-verification/resolved-artifacts.txt
  expect_failure "missing resolved-artifact manifest" \
    bash ci/validate-dependency-bootstrap-output.sh "${tmp_dir}/missing-manifest-review"
)

generator_repo="${tmp_dir}/generator-repo"
mkdir -p "${generator_repo}/ci"
cp "${root_dir}/ci/generate-dependency-bootstrap.sh" "${generator_repo}/ci/"
cp "${root_dir}/ci/validate-dependency-bootstrap-output.sh" "${generator_repo}/ci/"
(
  cd "${generator_repo}"
  git init --quiet
  git_identity
  printf '/build/\n/build-logic/build/\n' > .gitignore

  for path in "${generated_files[@]}"; do
    mkdir -p "$(dirname "${path}")"
    if [[ "${path}" == */verification-metadata.xml ]]; then
      printf '%s\n' "${valid_xml}" > "${path}"
    else
      printf 'locked=baseline\n' > "${path}"
    fi
  done

  cat > gradlew <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail

: "${GRADLE_INVOCATION_LOG:?GRADLE_INVOCATION_LOG is required}"
printf '%s\n' "$*" >> "${GRADLE_INVOCATION_LOG}"

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
  printf 'locked=generated\n' > "${path}"
done

valid_xml='<?xml version="1.0" encoding="UTF-8"?><verification-metadata><configuration><verify-metadata>true</verify-metadata></configuration><components><component group="example" name="artifact" version="1"><artifact name="artifact.jar"><sha256 value="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"/></artifact></component></components></verification-metadata>'
mkdir -p gradle build-logic/gradle
printf '%s\n' "${valid_xml}" > gradle/verification-metadata.xml
printf '%s\n' "${valid_xml}" > build-logic/gradle/verification-metadata.xml
mkdir -p build/reports/dependency-verification build-logic/build/reports/dependency-verification
printf 'root:artifact:generated\n' > build/reports/dependency-verification/resolved-artifacts.txt
printf 'build-logic:artifact:generated\n' > build-logic/build/reports/dependency-verification/resolved-artifacts.txt
GRADLEW
  chmod +x gradlew
  git add .
  git commit --quiet -m "generator fixture baseline"

  GRADLE_INVOCATION_LOG="${tmp_dir}/gradle-invocations" \
    bash ci/generate-dependency-bootstrap.sh "${tmp_dir}/generator-review"

  mapfile -t gradle_invocations < "${tmp_dir}/gradle-invocations"
  [[ "${#gradle_invocations[@]}" -eq 2 ]] || fail "generator must invoke Gradle exactly twice"
  [[ "${gradle_invocations[0]}" == '-p build-logic '* ]] ||
    fail "first Gradle invocation must target build-logic"
  [[ "${gradle_invocations[1]}" != *'-p build-logic'* ]] ||
    fail "second Gradle invocation must target the root build"
  for invocation in "${gradle_invocations[@]}"; do
    [[ " ${invocation} " == *' resolveAndLockAll resolveVerificationArtifacts '* ]] ||
      fail "Gradle invocation must resolve and lock verification artifacts"
    [[ " ${invocation} " == *' --write-locks '* ]] || fail "Gradle invocation is missing --write-locks"
    [[ " ${invocation} " == *' --write-verification-metadata sha256 '* ]] ||
      fail "Gradle invocation is missing SHA-256 verification metadata generation"
    [[ " ${invocation} " == *' --refresh-dependencies '* ]] ||
      fail "Gradle invocation is missing --refresh-dependencies"
    [[ " ${invocation} " == *' --no-daemon '* ]] || fail "Gradle invocation is missing --no-daemon"
  done
  assert_review_layout "${tmp_dir}/generator-review"
)

[[ ! -e "${root_dir}/ci/dependency-fingerprint.sh" && ! -L "${root_dir}/ci/dependency-fingerprint.sh" ]] ||
  fail "legacy dependency fingerprint script must be deleted"

echo "dependency bootstrap behavioral contracts verified"
