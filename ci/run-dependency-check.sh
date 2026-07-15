#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suppression_file="${root_dir}/ci/dependency-check-suppressions.xml"
scanner="${root_dir}/.ci-tools/dependency-check/bin/dependency-check.sh"
report_dir="${root_dir}/build/reports/dependency-check"
input_dir="${root_dir}/build/dependency-check-input"
data_dir="${root_dir}/.ci-tools/dependency-check-data"

python3 - "${suppression_file}" <<'PY'
import datetime
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
today = datetime.date.today()
for suppression in root.findall('{*}suppress'):
    notes = ' '.join((element.text or '') for element in suppression.findall('{*}notes'))
    for field in ('reason', 'owner', 'expires'):
        if not re.search(rf'\b{field}=\S+', notes):
            raise SystemExit(f'OWASP suppression missing {field}= metadata')
    match = re.search(r'\bexpires=(\d{4}-\d{2}-\d{2})\b', notes)
    if match is None or datetime.date.fromisoformat(match.group(1)) < today:
        raise SystemExit('OWASP suppression expiry is invalid or has passed')
PY

[[ -x "${scanner}" ]] || {
  echo "dependency-check is missing; run ci/bootstrap-quality-tools.sh --verify-checksums in CI" >&2
  exit 1
}
[[ -d "${input_dir}" ]] || {
  echo "dependency-check input is missing; run ci/stage-dependency-check-input.sh first" >&2
  exit 1
}
[[ -s "${input_dir}/manifest.txt" ]] || {
  echo "dependency-check runtime artifact manifest is missing" >&2
  exit 1
}
[[ "$(find "${input_dir}" -maxdepth 1 -type f -name '*.jar' | wc -l)" -gt 0 ]] || {
  echo "dependency-check input must contain production runtime dependency JARs" >&2
  exit 1
}

mkdir -p "${report_dir}" "${data_dir}"
args=(
  --project premium-spread
  --scan "${input_dir}"
  --data "${data_dir}"
  --out "${report_dir}"
  --format ALL
  --failOnCVSS 7
  --suppression "${suppression_file}"
  --nvdDatafeed "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz"
)

"${scanner}" "${args[@]}"
