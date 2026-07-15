#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scanner="${root_dir}/.ci-tools/dependency-check/bin/dependency-check.sh"
data_dir="${root_dir}/.ci-tools/dependency-check-data"

[[ -x "${scanner}" ]] || {
  echo "dependency-check is missing; run ci/bootstrap-quality-tools.sh --verify-checksums in CI" >&2
  exit 1
}

mkdir -p "${data_dir}"
"${scanner}" \
  --data "${data_dir}" \
  --updateonly \
  --nvdDatafeed "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz"
