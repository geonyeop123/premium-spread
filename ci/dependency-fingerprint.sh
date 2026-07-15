#!/usr/bin/env bash
set -euo pipefail

target_sha="${1:-HEAD}"

if ! [[ "${target_sha}" =~ ^[0-9a-fA-F]{40}$|^HEAD$ ]]; then
  echo "dependency fingerprint target must be HEAD or a 40-hex commit SHA" >&2
  exit 1
fi
git cat-file -e "${target_sha}^{commit}"

entries="$({
  git ls-tree -r "${target_sha}" | awk -F '\t' '
    function selected(path) {
      return path ~ /(^|\/)build\.gradle(\.kts)?$/ ||
        path ~ /(^|\/)settings\.gradle(\.kts)?$/ ||
        path ~ /(^|\/)gradle\.properties$/ ||
        path == "gradle/libs.versions.toml" ||
        path == "gradle/wrapper/gradle-wrapper.properties" ||
        path ~ /^build-logic\/src\// ||
        path ~ /^buildSrc\/src\//
    }
    selected($2) { print }
  '
} | LC_ALL=C sort)"

if [[ -z "${entries}" ]]; then
  echo "dependency fingerprint input is empty" >&2
  exit 1
fi

printf 'sha256:%s\n' "$(printf '%s\n' "${entries}" | sha256sum | awk '{print $1}')"
