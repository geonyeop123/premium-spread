#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="${DELETE_CACHES_SCRIPT:-${root_dir}/ci/delete-actions-caches.sh}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

fail() {
  echo "delete-actions-caches behavioral contract failed: $*" >&2
  exit 1
}

bin_dir="${tmp_dir}/bin"
mkdir -p "${bin_dir}"
cat > "${bin_dir}/gh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
argv="$*"
[[ -n "${FAKE_GH_LOG:-}" ]] && printf '%s\n' "${argv}" >> "${FAKE_GH_LOG}"
unexpected() {
  echo "fake gh: unexpected invocation: ${argv}" >&2
  exit 99
}
if [[ "${1:-}" == api && "${2:-}" == --method && "${3:-}" == GET ]]; then
  [[ $# -eq 10 && "$4" == --paginate && "$5" == --slurp && "$6" == "repos/${GH_REPO}/actions/caches" &&
    "$7" == -f && "$8" == ref=* && "$9" == -F && "${10}" == per_page=100 ]] || unexpected
  if [[ "${FAKE_GH_LIST_FAIL:-}" == 1 ]]; then
    echo "gh: Server Error (HTTP 500)" >&2
    exit 1
  fi
  cat "${FAKE_GH_LIST_FIXTURE}"
  exit 0
fi
if [[ "${1:-}" == api && "${2:-}" == --method && "${3:-}" == DELETE && $# -eq 4 ]]; then
  [[ "$4" =~ ^repos/${GH_REPO}/actions/caches/([0-9]+)$ ]] || unexpected
  id="${BASH_REMATCH[1]}"
  for candidate in ${FAKE_GH_DELETE_404:-}; do
    if [[ "${candidate}" == "${id}" ]]; then
      echo "gh: Not Found (HTTP 404)" >&2
      exit 1
    fi
  done
  for candidate in ${FAKE_GH_DELETE_500:-}; do
    if [[ "${candidate}" == "${id}" ]]; then
      echo "gh: Server Error (HTTP 500)" >&2
      exit 1
    fi
  done
  exit 0
fi
unexpected
FAKE
chmod +x "${bin_dir}/gh"

fixtures="${tmp_dir}/fixtures"
mkdir -p "${fixtures}"
cat > "${fixtures}/stale.json" <<'JSON'
[{"total_count":10,"actions_caches":[
{"id":101,"ref":"refs/heads/dev","key":"gradle-dependencies-v1-aaa","version":"v","last_accessed_at":"2026-10-25T01:00:00.1234567Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":1000},
{"id":102,"ref":"refs/heads/dev","key":"buildkit-blob-1-sha256:old1","version":"v","last_accessed_at":"2026-10-30T03:18:00.103452Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":200},
{"id":103,"ref":"refs/heads/dev","key":"index-buildkit-api-old","version":"v","last_accessed_at":"2026-10-29T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":10},
{"id":104,"ref":"refs/heads/dev","key":"gradle-home-v1|Linux-X64|unit-coverage[h]-old","version":"v","last_accessed_at":"2026-10-28T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":30},
{"id":105,"ref":"refs/heads/dev","key":"index-buildkit-api-new","version":"v","last_accessed_at":"2026-11-03T05:48:00.5Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":10},
{"id":106,"ref":"refs/heads/dev","key":"gradle-transforms-v1-boundary","version":"v","last_accessed_at":"2026-10-31T05:48:00.999Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":200},
{"id":107,"ref":"refs/heads/dev","key":"quality-tools-Linux-x","version":"v","last_accessed_at":"2026-10-20T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":50},
{"id":108,"ref":"refs/pull/9/merge","key":"gradle-dependencies-v1-pr","version":"v","last_accessed_at":"2026-11-10T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":200},
{"id":109,"ref":"refs/heads/dev","key":"buildkit-blob-1-sha256:old2","version":"v","last_accessed_at":"2026-10-30T23:59:59Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":300},
{"id":110,"ref":"refs/heads/dev","key":"gradle-transforms-v1-bbb","version":"v","last_accessed_at":"2026-11-02T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":400}
]}]
JSON
cat > "${fixtures}/pr.json" <<'JSON'
[{"total_count":6,"actions_caches":[
{"id":201,"ref":"refs/pull/42/merge","key":"node-cache-Linux-x64-npm-h","version":"v","last_accessed_at":null,"created_at":"2026-11-01T00:00:00Z","size_in_bytes":1},
{"id":202,"ref":"refs/pull/42/merge","key":"buildkit-blob-1-sha256:a","version":"v","last_accessed_at":"2026-11-01T00:00:00Z","created_at":"2026-11-01T00:00:00Z","size_in_bytes":2},
{"id":203,"ref":"refs/pull/42/merge","key":"index-buildkit-web","version":"v","last_accessed_at":"2026-11-01T00:00:00Z","created_at":"2026-11-01T00:00:00Z","size_in_bytes":4},
{"id":204,"ref":"refs/heads/dev","key":"index-buildkit-api","version":"v","last_accessed_at":"2026-11-01T00:00:00Z","created_at":"2026-11-01T00:00:00Z","size_in_bytes":8},
{"id":205,"ref":"refs/pull/42/merge","key":"gradle-home-v1|Linux-X64|x[h]-h","version":"v","last_accessed_at":"2026-11-01T00:00:00Z","created_at":"2026-11-01T00:00:00Z","size_in_bytes":16},
{"id":206,"ref":"refs/pull/42/merge","key":"evil\n::warning::injected","version":"v","last_accessed_at":"2026-11-01T00:00:00Z","created_at":"2026-11-01T00:00:00Z","size_in_bytes":32}
]}]
JSON
cat > "${fixtures}/paged.json" <<'JSON'
[{"total_count":2,"actions_caches":[
{"id":301,"ref":"refs/heads/dev","key":"index-buildkit-batch","version":"v","last_accessed_at":"2026-11-03T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":5}
]},{"total_count":2,"actions_caches":[
{"id":302,"ref":"refs/heads/dev","key":"gradle-dependencies-v1-old","version":"v","last_accessed_at":"2026-10-20T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":7}
]}]
JSON
cat > "${fixtures}/empty.json" <<'JSON'
[{"total_count":1,"actions_caches":[
{"id":401,"ref":"refs/pull/9/merge","key":"gradle-dependencies-v1-x","version":"v","last_accessed_at":"2026-10-01T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":9}
]}]
JSON
cat > "${fixtures}/badtime.json" <<'JSON'
[{"total_count":3,"actions_caches":[
{"id":501,"ref":"refs/heads/dev","key":"index-buildkit-api","version":"v","last_accessed_at":"not-a-date","created_at":"2026-10-01T00:00:00Z","size_in_bytes":1},
{"id":502,"ref":"refs/heads/dev","key":"gradle-dependencies-v1-old","version":"v","last_accessed_at":"2026-10-01T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":2},
{"id":503,"ref":"refs/heads/dev","key":"gradle-transforms-v1-new","version":"v","last_accessed_at":"2026-11-03T00:00:00Z","created_at":"2026-10-01T00:00:00Z","size_in_bytes":3}
]}]
JSON

# run_case <name> <fixture> <argv...>; env knobs come from the caller's environment.
# Results: ${tmp_dir}/<name>.{out,err,log}; exit status in run_status.
run_status=0
run_case() {
  local name="$1" fixture="$2"
  shift 2
  : > "${tmp_dir}/${name}.log"
  run_status=0
  PATH="${bin_dir}:${PATH}" GH_REPO="${GH_REPO-premium/spread}" GH_TOKEN="token-sentinel-6f1c" \
    FAKE_GH_LOG="${tmp_dir}/${name}.log" \
    FAKE_GH_LIST_FIXTURE="${fixtures}/${fixture}.json" \
    "$script" "$@" > "${tmp_dir}/${name}.out" 2> "${tmp_dir}/${name}.err" || run_status=$?
}

delete_ids() {
  { grep '^api --method DELETE ' "${tmp_dir}/$1.log" || true; } | sed 's#.*/##' | paste -sd' ' -
}

delete_count() {
  { grep -c '^api --method DELETE ' "${tmp_dir}/$1.log" || true; }
}

assert_status() {
  [[ "${run_status}" -eq "$2" ]] || fail "$1: expected exit $2, got ${run_status}: $(cat "${tmp_dir}/$1.err")"
}

assert_ids() {
  local actual
  actual="$(delete_ids "$1")"
  [[ "${actual}" == "$2" ]] || fail "$1: DELETE id column expected '$2', got '${actual}'"
}

assert_no_gh_calls() {
  [[ ! -s "${tmp_dir}/$1.log" ]] || fail "$1: gh must not be called: $(cat "${tmp_dir}/$1.log")"
}

assert_summary() {
  local actual
  actual="$(grep '^cache-cleanup summary ' "${tmp_dir}/$1.out" || true)"
  [[ "${actual}" == "$2" ]] || fail "$1: summary expected '$2', got '${actual}'"
}

assert_stderr_has() {
  grep -qF -- "$2" "${tmp_dir}/$1.err" || fail "$1: stderr must contain '$2', got: $(cat "${tmp_dir}/$1.err")"
}

# Case 1: stale selection and order. dev buildkit entries must survive (F5).
DRY_RUN=false run_case stale stale stale refs/heads/dev 3
assert_status stale 0
assert_ids stale "104 101"
assert_summary stale "cache-cleanup summary mode=stale ref=refs/heads/dev listed=9 selected=2 deleted=2 would_delete=0 \
already_gone=0 freed_bytes=1030 cutoff=2026-10-31T05:48:00Z"

# Case 2: stale dry-run.
DRY_RUN=true run_case dry stale stale refs/heads/dev 3
assert_status dry 0
[[ "$(delete_count dry)" -eq 0 ]] || fail "dry-run: DELETE must not be called"
[[ "$(grep -c 'action=would-delete ' "${tmp_dir}/dry.out")" -eq 2 ]] || fail "dry-run: expected 2 would-delete lines"
grep -q 'action=would-delete id=104 ' "${tmp_dir}/dry.out" || fail "dry-run: id 104 missing"
grep -q 'action=would-delete id=101 ' "${tmp_dir}/dry.out" || fail "dry-run: id 101 missing"
assert_summary dry "cache-cleanup summary mode=stale ref=refs/heads/dev listed=9 selected=2 deleted=0 would_delete=2 \
already_gone=0 freed_bytes=0 cutoff=2026-10-31T05:48:00Z"

# Case 3: pr-ref deletes everything of that ref without parsing times.
DRY_RUN=false run_case pr pr pr-ref refs/pull/42/merge
assert_status pr 0
assert_ids pr "203 202 205 201 206"
assert_summary pr "cache-cleanup summary mode=pr-ref ref=refs/pull/42/merge listed=5 selected=5 deleted=5 would_delete=0 \
already_gone=0 freed_bytes=55 cutoff=none"
grep -q 'action=delete id=201 rank=4 .*last_accessed_at=null' "${tmp_dir}/pr.out" || fail "pr-ref: id 201 line missing"

# Case 4: pr-ref rejection.
case_no=0
for bad_ref in refs/heads/dev refs/pull/42/head refs/pull/0/merge 'refs/pull/42/merge;x' ''; do
  case_no=$((case_no + 1))
  DRY_RUN=false run_case "rej4_${case_no}" pr pr-ref "${bad_ref}"
  assert_status "rej4_${case_no}" 2
  assert_no_gh_calls "rej4_${case_no}"
done

# Case 5: stale rejection.
DRY_RUN=false run_case rej5_ref stale stale refs/heads/main 3
assert_status rej5_ref 2
assert_no_gh_calls rej5_ref
for bad_days in 0 7 3x; do
  DRY_RUN=false run_case "rej5_${bad_days}" stale stale refs/heads/dev "${bad_days}"
  assert_status "rej5_${bad_days}" 2
  assert_no_gh_calls "rej5_${bad_days}"
done
DRY_RUN=false run_case rej5_none stale stale refs/heads/dev
assert_status rej5_none 2
assert_no_gh_calls rej5_none

# Case 6: env rejection.
unset DRY_RUN
run_case rej6_unset pr pr-ref refs/pull/42/merge
assert_status rej6_unset 2
assert_no_gh_calls rej6_unset
DRY_RUN=yes run_case rej6_yes pr pr-ref refs/pull/42/merge
assert_status rej6_yes 2
assert_no_gh_calls rej6_yes
DRY_RUN=false GH_REPO=premium run_case rej6_repo pr pr-ref refs/pull/42/merge
assert_status rej6_repo 2
assert_no_gh_calls rej6_repo

# Case 7: 404 is already-gone and does not stop.
DRY_RUN=false FAKE_GH_DELETE_404="202" run_case gone pr pr-ref refs/pull/42/merge
assert_status gone 0
assert_ids gone "203 202 205 201 206"
[[ "$(grep -c 'action=already-gone ' "${tmp_dir}/gone.out")" -eq 1 ]] || fail "404: expected one already-gone line"
grep -q 'action=already-gone id=202 ' "${tmp_dir}/gone.out" || fail "404: id 202 must be already-gone"
assert_summary gone "cache-cleanup summary mode=pr-ref ref=refs/pull/42/merge listed=5 selected=5 deleted=4 would_delete=0 \
already_gone=1 freed_bytes=53 cutoff=none"

# Case 8: 500 stops immediately.
DRY_RUN=false FAKE_GH_DELETE_500="202" run_case fail500 pr pr-ref refs/pull/42/merge
assert_status fail500 1
assert_ids fail500 "203 202"
assert_stderr_has fail500 "cache-cleanup error: delete failed id=202 status=500"

# Case 9: list failure.
DRY_RUN=false FAKE_GH_LIST_FAIL=1 run_case listfail stale stale refs/heads/dev 3
assert_status listfail 1
[[ "$(delete_count listfail)" -eq 0 ]] || fail "list failure: DELETE must not be called"
assert_stderr_has listfail "cache-cleanup error: list failed ref=refs/heads/dev"

# Case 10: log hygiene (reuses cases 1, 3, 7 and 8).
for name in pr fail500 stale gone; do
  if grep -q 'token-sentinel-6f1c' "${tmp_dir}/${name}.out" "${tmp_dir}/${name}.err"; then
    fail "log hygiene: token leaked in ${name}"
  fi
  if grep -q '^::' "${tmp_dir}/${name}.out" "${tmp_dir}/${name}.err"; then
    fail "log hygiene: line starting with :: in ${name}"
  fi
done
grep -q 'key=evil?::warning::injected' "${tmp_dir}/pr.out" || fail "log hygiene: sanitized key missing"

# Case 11: pagination.
DRY_RUN=false run_case paged paged stale refs/heads/dev 3
assert_status paged 0
assert_ids paged "302"
grep -q 'listed=2 ' "${tmp_dir}/paged.out" || fail "pagination: expected listed=2"

# Case 12: no dev entries.
DRY_RUN=false run_case empty empty stale refs/heads/dev 3
assert_status empty 0
[[ "$(delete_count empty)" -eq 0 ]] || fail "empty: DELETE must not be called"
assert_summary empty "cache-cleanup summary mode=stale ref=refs/heads/dev listed=0 selected=0 deleted=0 would_delete=0 \
already_gone=0 freed_bytes=0 cutoff=none"

# Case 13: unparsable time aborts before any deletion.
DRY_RUN=false run_case badtime badtime stale refs/heads/dev 3
assert_status badtime 1
[[ "$(delete_count badtime)" -eq 0 ]] || fail "badtime: DELETE must not be called"
assert_stderr_has badtime "cache-cleanup error: unparsable last_accessed_at id=501"
if grep -q '^cache-cleanup summary ' "${tmp_dir}/badtime.out"; then fail "badtime: summary must not be printed"; fi

echo "delete-actions-caches behavioral contract verified (cases: 13)"
