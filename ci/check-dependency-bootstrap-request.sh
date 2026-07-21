#!/usr/bin/env bash
set -euo pipefail

marker="ci/dependency-bootstrap-request"
output_file="${1:?GitHub output file is required}"
expected='request=gradle-dependency-bootstrap-v1'

fail() {
  echo "dependency bootstrap request rejected: $*" >&2
  exit 1
}

if [[ ! -e "${marker}" && ! -L "${marker}" ]]; then
  echo "requested=false" >> "${output_file}"
  exit 0
fi

[[ "${GITHUB_EVENT_NAME:-}" == "pull_request" ]] || fail "marker is accepted only for a pull_request event"
[[ -f "${marker}" && ! -L "${marker}" ]] || fail "marker must be a regular non-symlink file"
[[ "$(git ls-files -s -- "${marker}" | awk '{print $1}')" == "100644" ]] || fail "marker Git mode must be 100644"
cmp -s "${marker}" <(printf '%s\n' "${expected}") || fail "marker content must match the fixed v1 request"
[[ "$(wc -l < "${marker}")" -eq 1 ]] || fail "marker must contain one LF-terminated line"
if grep -q $'\r' "${marker}"; then
  fail "marker must use LF line endings"
fi
echo 'requested=true' >> "${output_file}"
