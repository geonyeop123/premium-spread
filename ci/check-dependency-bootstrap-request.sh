#!/usr/bin/env bash
set -euo pipefail

marker="ci/dependency-bootstrap-request"
output_file="${1:?GitHub output file is required}"

fail() {
  echo "dependency bootstrap request rejected: $*" >&2
  exit 1
}

if [[ ! -e "${marker}" && ! -L "${marker}" ]]; then
  echo "requested=false" >> "${output_file}"
  exit 0
fi

[[ -f "${marker}" && ! -L "${marker}" ]] || fail "marker must be a regular non-symlink file"
[[ "$(git ls-files -s -- "${marker}" | awk '{print $1}')" == "100644" ]] || fail "marker Git mode must be 100644"
[[ "${GITHUB_EVENT_NAME:-}" == "push" ]] || fail "marker is accepted only for a push event"
[[ "${GITHUB_REF:-}" == "refs/heads/refactor/infrastructure-boundary" ]] || fail "marker is accepted only on the refactor branch"
if grep -q $'\r' "${marker}"; then
  fail "marker must use LF line endings"
fi

mapfile -t lines < "${marker}"
[[ "${#lines[@]}" -eq 6 ]] || fail "marker must contain exactly six lines"

declare -A values=()
for line in "${lines[@]}"; do
  [[ "${line}" =~ ^([a-z_]+)=(.+)$ ]] || fail "marker contains an invalid line"
  key="${BASH_REMATCH[1]}"
  value="${BASH_REMATCH[2]}"
  [[ -z "${values[${key}]+x}" ]] || fail "marker contains a duplicate key: ${key}"
  case "${key}" in
    schema|request|branch|target_sha|dependency_fingerprint|expires_at) ;;
    *) fail "marker contains an unsupported key: ${key}" ;;
  esac
  values["${key}"]="${value}"
done

for key in schema request branch target_sha dependency_fingerprint expires_at; do
  [[ -n "${values[${key}]:-}" ]] || fail "marker is missing ${key}"
done
[[ "${values[schema]}" == "1" ]] || fail "unsupported marker schema"
[[ "${values[request]}" == "GENERATE_LOCKS_AND_SHA256" ]] || fail "unsupported request"
[[ "${values[branch]}" == "refactor/infrastructure-boundary" ]] || fail "marker branch does not match"
[[ "${values[target_sha]}" =~ ^[0-9a-fA-F]{40}$ ]] || fail "target_sha must be 40-hex"
[[ "${values[dependency_fingerprint]}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "dependency_fingerprint must be lowercase SHA-256"

head_sha="$(git rev-parse HEAD)"
[[ "${GITHUB_SHA:-}" == "${head_sha}" ]] || fail "GITHUB_SHA does not match checkout HEAD"
[[ "${TARGET_SHA:-}" == "${head_sha}" ]] || fail "TARGET_SHA does not match checkout HEAD"

read -r -a revision_line <<< "$(git rev-list --parents -n 1 HEAD)"
[[ "${#revision_line[@]}" -eq 2 ]] || fail "marker commit must have exactly one parent"
[[ "${values[target_sha],,}" == "${revision_line[1],,}" ]] || fail "target_sha must be the marker commit parent"

mapfile -t changed_paths < <(git diff-tree --no-commit-id --name-only -r HEAD)
[[ "${#changed_paths[@]}" -eq 1 && "${changed_paths[0]}" == "${marker}" ]] ||
  fail "marker commit may change only ${marker}"

actual_fingerprint="$(bash ci/dependency-fingerprint.sh "${values[target_sha]}")"
[[ "${values[dependency_fingerprint]}" == "${actual_fingerprint}" ]] || fail "dependency fingerprint does not match target_sha"

python3 - "${values[expires_at]}" <<'PY' || fail "expires_at must be a future UTC timestamp no more than 48 hours away"
from datetime import datetime, timezone, timedelta
import sys

value = sys.argv[1]
if not value.endswith("Z"):
    raise SystemExit(1)
expiry = datetime.fromisoformat(value[:-1] + "+00:00")
now = datetime.now(timezone.utc)
if not now < expiry <= now + timedelta(hours=48):
    raise SystemExit(1)
PY

{
  echo "requested=true"
  echo "target_sha=${values[target_sha],,}"
  echo "dependency_fingerprint=${values[dependency_fingerprint]}"
} >> "${output_file}"
