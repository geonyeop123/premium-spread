#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[[ ! -e "${root_dir}/.ci-tools/ktlint.jar" ]] || {
  echo "local offline contract requires CI quality artifacts to be absent" >&2
  exit 1
}

"${root_dir}/gradlew" help --offline --dependency-verification strict --no-daemon

[[ ! -e "${root_dir}/.ci-tools/ktlint.jar" ]] || {
  echo "Gradle configuration unexpectedly bootstrapped a CI-only tool" >&2
  exit 1
}

echo "offline Gradle configuration is independent of CI-only tools"
