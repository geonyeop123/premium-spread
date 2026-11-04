#!/usr/bin/env bash
# Static contract of the two Actions cache cleanup workflows (design D5, D6, D7).
# It also proves itself: ten mutated copies of the workflows must each be rejected with the expected message.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

pr_file="actions-cache-pr-cleanup.yml"
stale_file="actions-cache-stale-cleanup.yml"

block() {
  local file="$1" key="$2"
  awk -v key="${key}" '
    $0 == key ":" { inside = 1; next }
    inside && $0 ~ /^[^[:space:]]/ { exit }
    inside { print }
  ' "${file}"
}

check_workflows() {
  local dir="$1"
  (
    set -euo pipefail
    local pr="${dir}/${pr_file}" stale="${dir}/${stale_file}" f
    violation() {
      echo "actions cache cleanup contract failed: $*" >&2
      exit 1
    }
    has_line() { grep -Fxq -- "$1" "$2"; }
    [[ -f "${pr}" && -f "${stale}" ]] || violation "cleanup workflow files must exist"

    # The cleanup checkout must be the exact pinned line Quality Gate uses (design D7), not a copy kept here.
    local checkout_line
    checkout_line="$(grep -E '^      - uses: actions/checkout@' "${dir}/quality-gate.yml" | sort -u || true)"
    [[ "${checkout_line}" =~ ^'      - uses: actions/checkout@'[0-9a-f]{40}' # v'[0-9.]+$ ]] ||
      violation "quality gate must pin exactly one actions/checkout line"

    [[ "$(block "${pr}" on)" == $'  pull_request_target:\n    types:\n      - closed' ]] ||
      violation "pr cleanup trigger must be exactly pull_request_target closed"

    local stale_on
    stale_on="$(block "${stale}" on)"
    grep -Fxq '        default: true' <<< "${stale_on}" ||
      violation "stale cleanup manual runs must default to dry_run true"

    local expected_on
    expected_on="$(cat <<'ON'
  schedule:
    - cron: '17 18 * * *'
  workflow_dispatch:
    inputs:
      dry_run:
        description: Report stale dev Gradle caches without deleting them
        type: boolean
        default: true
ON
)"
    [[ "${stale_on}" == "${expected_on}" ]] ||
      violation "stale cleanup trigger must be exactly the daily schedule and manual dispatch"

    for f in "${pr}" "${stale}"; do
      [[ "$(block "${f}" permissions)" == $'  actions: write\n  contents: read' &&
        "$(grep -c '^permissions:$' "${f}" || true)" == 1 &&
        "$(grep -Ec '^[[:space:]]+permissions:' "${f}" || true)" == 0 ]] ||
        violation "cleanup workflow permissions must be exactly actions: write and contents: read"
    done

    for f in "${pr}" "${stale}"; do
      [[ "$(grep -Ec '^[[:space:]]*-?[[:space:]]*uses:' "${f}" || true)" == 1 ]] && has_line "${checkout_line}" "${f}" ||
        violation "cleanup workflows may only use the pinned checkout action"
    done

    for f in "${pr}" "${stale}"; do
      [[ "$(grep -Fxc '          persist-credentials: false' "${f}" || true)" == 1 ]] ||
        violation "cleanup checkout must not persist credentials"
    done

    for f in "${pr}" "${stale}"; do
      ! grep -Eq '^[[:space:]]+ref:' "${f}" && ! grep -Eq 'pull_request\.head|head_ref|head\.sha' "${f}" ||
        violation "cleanup workflows must not override the checkout ref"
    done

    for f in "${pr}" "${stale}"; do
      ! grep -Fq 'secrets.' "${f}" || violation "cleanup workflows may read only the pull request number"
    done
    local ref
    while IFS= read -r ref; do
      [[ "${ref}" == github.event.pull_request.number ]] ||
        violation "cleanup workflows may read only the pull request number"
    done < <(grep -o 'github\.event\.pull_request\.[A-Za-z_.]*' "${pr}" || true)

    for f in "${pr}" "${stale}"; do
      ! grep -Eq '^[[:space:]]*(-[[:space:]]+)?run:.*\$\{\{' "${f}" ||
        violation "cleanup run steps must not interpolate expressions"
    done

    has_line '        run: bash ci/delete-actions-caches.sh pr-ref "${PR_MERGE_REF}"' "${pr}" &&
      has_line '          PR_MERGE_REF: refs/pull/${{ github.event.pull_request.number }}/merge' "${pr}" &&
      has_line "          DRY_RUN: 'false'" "${pr}" ||
      violation "pr cleanup must delete exactly the closed PR merge ref"

    has_line '        run: bash ci/delete-actions-caches.sh stale refs/heads/dev 3' "${stale}" &&
      has_line "          DRY_RUN: \${{ inputs.dry_run && 'true' || 'false' }}" "${stale}" ||
      violation "stale cleanup must invoke the script with refs/heads/dev and 3 days"

    for f in "${pr}" "${stale}"; do
      has_line '          GH_TOKEN: ${{ github.token }}' "${f}" &&
        has_line '          GH_REPO: ${{ github.repository }}' "${f}" ||
        violation "cleanup workflows must pass only the job token and repository"
    done

    has_line '  group: actions-cache-pr-cleanup-${{ github.event.pull_request.number }}' "${pr}" &&
      has_line '  group: actions-cache-stale-cleanup' "${stale}" &&
      has_line '  cancel-in-progress: false' "${pr}" && has_line '  cancel-in-progress: false' "${stale}" ||
      violation "cleanup workflows must not cancel an in-flight deletion"

    local writers wf workflow_files
    shopt -s nullglob
    workflow_files=("${dir}"/*.yml "${dir}"/*.yaml)
    shopt -u nullglob
    writers="$(
      for wf in "${workflow_files[@]}"; do
        if grep -Eq "actions[[:space:]]*:[[:space:]]*[\"']?write|write-all" "${wf}"; then basename "${wf}"; fi
      done | LC_ALL=C sort | tr '\n' ' '
    )"
    [[ "${writers}" == "${pr_file} ${stale_file} " ]] ||
      violation "actions: write is allowed only in the two cleanup workflows"

    # Whole-block compare: no step, key or expression syntax (for example github.event['pull_request'][...])
    # can be added under jobs without review. __CHECKOUT__ is the Quality Gate checkout line read above.
    local expected_pr_jobs expected_stale_jobs
    expected_pr_jobs="$(cat <<'JOBS'
  delete-pr-caches:
    name: Delete closed PR caches
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
__CHECKOUT__
        with:
          persist-credentials: false
      - name: Delete every Actions cache of the closed PR merge ref
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          DRY_RUN: 'false'
          PR_MERGE_REF: refs/pull/${{ github.event.pull_request.number }}/merge
        run: bash ci/delete-actions-caches.sh pr-ref "${PR_MERGE_REF}"
JOBS
)"
    expected_stale_jobs="$(cat <<'JOBS'
  delete-stale-dev-caches:
    name: Delete stale dev Gradle caches
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
__CHECKOUT__
        with:
          persist-credentials: false
      - name: Delete dev Gradle caches idle for three days of dev activity
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          DRY_RUN: ${{ inputs.dry_run && 'true' || 'false' }}
        run: bash ci/delete-actions-caches.sh stale refs/heads/dev 3
JOBS
)"
    [[ "$(block "${pr}" jobs)" == "${expected_pr_jobs/__CHECKOUT__/${checkout_line}}" &&
      "$(block "${stale}" jobs)" == "${expected_stale_jobs/__CHECKOUT__/${checkout_line}}" ]] ||
      violation "cleanup jobs must be exactly the reviewed jobs block"
  )
}

# replace_line <file> <exact old line> <new line>; insert_after <file> <exact line> <new line>; delete_line <file> <line>
replace_line() {
  local file="$1" old="$2" new="$3" line
  : > "${file}.new"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == "${old}" ]]; then printf '%s\n' "${new}"; else printf '%s\n' "${line}"; fi
  done < "${file}" >> "${file}.new"
  mv "${file}.new" "${file}"
}
insert_after() {
  local file="$1" anchor="$2" added="$3" line
  : > "${file}.new"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    printf '%s\n' "${line}"
    [[ "${line}" == "${anchor}" ]] && printf '%s\n' "${added}"
  done < "${file}" >> "${file}.new"
  mv "${file}.new" "${file}"
}
delete_line() {
  local file="$1" target="$2" line
  : > "${file}.new"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ "${line}" == "${target}" ]] || printf '%s\n' "${line}"
  done < "${file}" >> "${file}.new"
  mv "${file}.new" "${file}"
}

check_workflows "${root_dir}/.github/workflows"

mutate() {
  case "$1" in
    M1) replace_line "$2/${pr_file}" '  pull_request_target:' '  pull_request:' ;;
    M2) insert_after "$2/${pr_file}" '          persist-credentials: false' \
      '          ref: ${{ github.event.pull_request.head.sha }}' ;;
    M3) insert_after "$2/${pr_file}" '  contents: read' '  pull-requests: write' ;;
    M4) delete_line "$2/${pr_file}" '          persist-credentials: false' ;;
    M5) replace_line "$2/${pr_file}" '        run: bash ci/delete-actions-caches.sh pr-ref "${PR_MERGE_REF}"' \
      '        run: bash ci/delete-actions-caches.sh pr-ref "refs/pull/${{ github.event.pull_request.number }}/merge"' ;;
    M6) replace_line "$2/${stale_file}" '        run: bash ci/delete-actions-caches.sh stale refs/heads/dev 3' \
      '        run: bash ci/delete-actions-caches.sh stale refs/heads/dev 7' ;;
    M7) printf '%s\n' '      - uses: example/third-party@0123456789abcdef0123456789abcdef01234567' >> "$2/${stale_file}" ;;
    M8) insert_after "$2/quality-gate.yml" '  contents: read' '  actions: write' ;;
    M9) replace_line "$2/${stale_file}" '        default: true' '        default: false' ;;
    M10) printf '%s\n' '      - name: Echo' '        run: |' \
      "          echo \"\${{ github.event['pull_request']['head']['ref'] }}\"" >> "$2/${pr_file}" ;;
  esac
}
expected_message() {
  case "$1" in
    M1) echo "pr cleanup trigger must be exactly pull_request_target closed" ;;
    M2) echo "cleanup workflows must not override the checkout ref" ;;
    M3) echo "cleanup workflow permissions must be exactly actions: write and contents: read" ;;
    M4) echo "cleanup checkout must not persist credentials" ;;
    M5) echo "cleanup run steps must not interpolate expressions" ;;
    M6) echo "stale cleanup must invoke the script with refs/heads/dev and 3 days" ;;
    M7) echo "cleanup workflows may only use the pinned checkout action" ;;
    M8) echo "actions: write is allowed only in the two cleanup workflows" ;;
    M9) echo "stale cleanup manual runs must default to dry_run true" ;;
    M10) echo "cleanup jobs must be exactly the reviewed jobs block" ;;
  esac
}

rejected=0
for id in M1 M2 M3 M4 M5 M6 M7 M8 M9 M10; do
  mutant_dir="${tmp_dir}/${id}"
  mkdir -p "${mutant_dir}"
  cp "${root_dir}"/.github/workflows/*.yml "${mutant_dir}/"
  mutate "${id}" "${mutant_dir}"
  expected="$(expected_message "${id}")"
  if stderr="$( ( check_workflows "${mutant_dir}" ) 2>&1 >/dev/null )"; then
    echo "actions cache cleanup contract failed: mutation ${id} was not rejected" >&2
    exit 1
  fi
  [[ "${stderr}" == "actions cache cleanup contract failed: ${expected}" ]] || {
    echo "actions cache cleanup contract failed: mutation ${id} was not rejected (got: ${stderr})" >&2
    exit 1
  }
  echo "mutation ${id} rejected: ${expected}"
  rejected=$((rejected + 1))
done

echo "actions cache cleanup contracts verified (mutations rejected: ${rejected})"
