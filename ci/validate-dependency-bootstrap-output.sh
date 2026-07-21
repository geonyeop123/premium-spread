#!/usr/bin/env bash
set -euo pipefail

review_dir="${1:?review output directory is required}"

fail() {
  echo "dependency bootstrap output rejected: $*" >&2
  exit 1
}

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

for path in "${generated_files[@]}"; do
  [[ -s "${path}" ]] || fail "expected generated file is missing or empty: ${path}"
done

declare -A allowed=()
for path in "${generated_files[@]}"; do
  allowed["${path}"]=1
done
mapfile -t changed_paths < <({ git diff --name-only HEAD; git ls-files --others --exclude-standard; } | LC_ALL=C sort -u)
[[ "${#changed_paths[@]}" -gt 0 ]] || fail "generation produced no reviewable changes"
for path in "${changed_paths[@]}"; do
  [[ -n "${allowed[${path}]:-}" ]] || fail "generation changed a non-allowlisted path: ${path}"
done

python3 - gradle/verification-metadata.xml build-logic/gradle/verification-metadata.xml <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

for path in sys.argv[1:]:
    root = ET.parse(path).getroot()
    local = lambda tag: tag.rsplit("}", 1)[-1]
    verify_metadata = [e for e in root.iter() if local(e.tag) == "verify-metadata"]
    if len(verify_metadata) != 1 or (verify_metadata[0].text or "").strip() != "true":
        raise SystemExit(f"{path}: verify-metadata must be true")
    forbidden = {"md5", "sha1", "sha512", "trusted-artifacts", "trusted-keys", "ignored-keys"}
    present = {local(e.tag) for e in root.iter()}
    if forbidden & present:
        raise SystemExit(f"{path}: forbidden verification metadata: {sorted(forbidden & present)}")
    artifacts = [e for e in root.iter() if local(e.tag) == "artifact"]
    if not artifacts:
        raise SystemExit(f"{path}: verification metadata contains no artifacts")
    for artifact in artifacts:
        children = list(artifact)
        if not children or any(local(child.tag) != "sha256" for child in children):
            raise SystemExit(f"{path}: each artifact must use SHA-256 and no other trust mechanism")
        for checksum in children:
            if not re.fullmatch(r"[0-9a-f]{64}", checksum.attrib.get("value", "")):
                raise SystemExit(f"{path}: every artifact checksum must be lowercase SHA-256")
PY

root_manifest="build/reports/dependency-verification/resolved-artifacts.txt"
build_logic_manifest="build-logic/build/reports/dependency-verification/resolved-artifacts.txt"
[[ -s "${root_manifest}" ]] || fail "root resolver manifest is missing"
[[ -s "${build_logic_manifest}" ]] || fail "build-logic resolver manifest is missing"

rm -rf "${review_dir}"
mkdir -p "${review_dir}/files"
for path in "${generated_files[@]}"; do
  install -D -m 0644 "${path}" "${review_dir}/files/${path}"
done
install -m 0644 "${root_manifest}" "${review_dir}/root-resolved-artifacts.txt"
install -m 0644 "${build_logic_manifest}" "${review_dir}/build-logic-resolved-artifacts.txt"
git diff --binary HEAD -- "${generated_files[@]}" > "${review_dir}/review.patch"
