#!/usr/bin/env bash
set -euo pipefail

target_sha="${TARGET_SHA:-}"
[[ "${target_sha}" =~ ^[0-9a-fA-F]{40}$ ]] || {
  echo "TARGET_SHA must be an explicit 40-hex commit SHA" >&2
  exit 1
}

actual_sha="$(git rev-parse HEAD)"
[[ "${actual_sha,,}" == "${target_sha,,}" ]] || {
  echo "checked out HEAD ${actual_sha} does not match requested ${target_sha}" >&2
  exit 1
}

echo "verified exact candidate SHA: ${actual_sha}"
