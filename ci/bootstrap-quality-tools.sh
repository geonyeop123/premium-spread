#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="${root_dir}/ci/quality-tools.lock"
tool_dir="${root_dir}/.ci-tools"
dependency_data_dir="${tool_dir}/dependency-check-data"

fail() {
  echo "quality tool bootstrap failed: $*" >&2
  exit 1
}

[[ "${1:-}" == "--verify-checksums" && "$#" -eq 1 ]] ||
  fail "usage: ci/bootstrap-quality-tools.sh --verify-checksums"
[[ "${CI:-}" == "true" || "${QUALITY_TOOLS_ALLOW_NETWORK:-}" == "true" ]] ||
  fail "network bootstrap is CI-only (set QUALITY_TOOLS_ALLOW_NETWORK=true only in an isolated CI-equivalent runner)"
command -v curl >/dev/null || fail "curl is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"
command -v unzip >/dev/null || fail "unzip is required"
[[ -f "${lock_file}" ]] || fail "missing checksum lock ${lock_file}"

mkdir -p "${tool_dir}/downloads" "${dependency_data_dir}"

download_and_verify() {
  local name="$1"
  local version="$2"
  local url="$3"
  local expected_sha="$4"
  local extension="${url##*.}"
  local destination="${tool_dir}/downloads/${name}-${version}.${extension}"
  local temporary="${destination}.part"
  local actual_sha

  [[ "${expected_sha}" =~ ^[0-9a-f]{64}$ ]] || fail "invalid SHA-256 for ${name}"
  if [[ ! -f "${destination}" ]]; then
    rm -f "${temporary}"
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
      --output "${temporary}" "${url}"
    actual_sha="$(sha256sum "${temporary}" | awk '{print $1}')"
    [[ "${actual_sha}" == "${expected_sha}" ]] || {
      rm -f "${temporary}"
      fail "checksum mismatch for ${name}-${version}: expected ${expected_sha}, got ${actual_sha}"
    }
    mv "${temporary}" "${destination}"
  fi

  actual_sha="$(sha256sum "${destination}" | awk '{print $1}')"
  [[ "${actual_sha}" == "${expected_sha}" ]] ||
    fail "cached checksum mismatch for ${name}-${version}: expected ${expected_sha}, got ${actual_sha}"
  printf '%s\n' "${destination}"
}

while IFS='|' read -r name version url expected_sha; do
  [[ -z "${name}" || "${name}" == \#* ]] && continue
  [[ -n "${version}" && "${url}" == https://* && -n "${expected_sha}" ]] ||
    fail "malformed lock entry for ${name}"
  artifact="$(download_and_verify "${name}" "${version}" "${url}" "${expected_sha}")"
  case "${name}" in
    ktlint)
      cp "${artifact}" "${tool_dir}/ktlint.jar"
      ;;
    detekt)
      cp "${artifact}" "${tool_dir}/detekt-cli.jar"
      ;;
    dependency-check)
      rm -rf "${tool_dir}/dependency-check"
      unzip -q "${artifact}" -d "${tool_dir}"
      chmod +x "${tool_dir}/dependency-check/bin/dependency-check.sh"
      ;;
    *)
      fail "unsupported tool ${name}"
      ;;
  esac
done < "${lock_file}"

[[ -f "${tool_dir}/ktlint.jar" ]] || fail "ktlint was not installed"
[[ -f "${tool_dir}/detekt-cli.jar" ]] || fail "detekt was not installed"
[[ -x "${tool_dir}/dependency-check/bin/dependency-check.sh" ]] ||
  fail "dependency-check was not installed"
[[ -d "${dependency_data_dir}" ]] || fail "dependency-check data directory was not preserved"

echo "CI quality tools downloaded and SHA-256 verified"
