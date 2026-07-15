#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
input_dir="${root_dir}/build/dependency-check-input"

"${root_dir}/gradlew" \
  prepareDependencyCheckInput \
  --dependency-verification strict \
  --no-daemon

[[ -s "${input_dir}/manifest.txt" ]] || {
  echo "dependency-check runtime artifact manifest is missing" >&2
  exit 1
}
[[ "$(find "${input_dir}" -maxdepth 1 -type f -name '*.jar' | wc -l)" -gt 0 ]] || {
  echo "dependency-check input must contain production runtime dependency JARs" >&2
  exit 1
}

echo "staged strict-verified production runtime dependency JARs in ${input_dir}"
