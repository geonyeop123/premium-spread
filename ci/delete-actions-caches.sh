#!/usr/bin/env bash
# Deletes GitHub Actions caches. See docs/work/docker-build-time/design.md D4 and D6.
#   delete-actions-caches.sh pr-ref <refs/pull/N/merge>   every cache of that ref (rank 0-4)
#   delete-actions-caches.sh stale refs/heads/dev <days>  gradle-* caches (rank 2-3) not accessed for <days>
#                                                         before the most recent access on that ref
# env: GH_REPO (owner/repo), DRY_RUN (true|false), GH_TOKEN (read by gh only, never by this script)
# exit: 0 ok, 1 API failure or unparsable last_accessed_at, 2 invalid arguments or environment
set -euo pipefail

usage_error() {
  echo "cache-cleanup error: $*" >&2
  exit 2
}

mode="${1:-}"
ref="${2:-}"
days=""
case "${mode}" in
  pr-ref)
    [[ $# -eq 2 ]] || usage_error "usage: pr-ref <ref>"
    [[ "${ref}" =~ ^refs/pull/[1-9][0-9]*/merge$ ]] || usage_error "ref not allowed for pr-ref"
    ;;
  stale)
    [[ $# -eq 3 ]] || usage_error "usage: stale <ref> <days>"
    days="$3"
    [[ "${ref}" == "refs/heads/dev" ]] || usage_error "ref not allowed for stale"
    [[ "${days}" =~ ^[1-6]$ ]] || usage_error "days must be an integer from 1 to 6"
    ;;
  *)
    usage_error "mode must be pr-ref or stale"
    ;;
esac
[[ "${GH_REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || usage_error "GH_REPO must be owner/repo"
[[ "${DRY_RUN:-}" == "true" || "${DRY_RUN:-}" == "false" ]] || usage_error "DRY_RUN must be true or false"

# Selection and ordering in one python run. Output: one header line, then one tab-separated line per selected entry.
select_program='
import json, re, sys
from datetime import datetime, timedelta, timezone

mode, ref, days = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    pages = json.load(sys.stdin)
    entries = [e for page in pages for e in page["actions_caches"]]
    entries = [e for e in entries if e["ref"] == ref]
    for e in entries:
        int(e["id"])
        str(e["key"])
except Exception:
    sys.exit(3)

TIME = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?Z$")


def parse(value):
    match = TIME.match(value) if isinstance(value, str) else None
    if not match:
        return None
    try:
        return datetime.strptime(match.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def rank(key):
    if key.startswith("index-"):
        return 0
    if key.startswith("buildkit-blob-"):
        return 1
    if key.startswith("gradle-home-"):
        return 2
    if key.startswith("gradle-"):
        return 3
    return 4


def clean(value):
    return re.sub(r"[^\x20-\x7e]", "?", str(value))


cutoff = None
if mode == "stale" and entries:
    times = {}
    for e in sorted(entries, key=lambda x: int(x["id"])):
        parsed = parse(e.get("last_accessed_at"))
        if parsed is None:
            sys.stderr.write("cache-cleanup error: unparsable last_accessed_at id=%d\n" % int(e["id"]))
            sys.exit(4)
        times[int(e["id"])] = parsed
    cutoff = max(times.values()) - timedelta(days=int(days))

selected = []
for e in entries:
    r = rank(e["key"])
    if mode == "stale":
        if r not in (2, 3) or not times[int(e["id"])] < cutoff:
            continue
    selected.append((r, int(e["id"]), e))
selected.sort(key=lambda item: (item[0], item[1]))

print("H\t%d\t%d\t%s" % (len(entries), len(selected), cutoff.strftime("%Y-%m-%dT%H:%M:%SZ") if cutoff else "none"))
for r, i, e in selected:
    last = e.get("last_accessed_at")
    size = e.get("size_in_bytes")
    size = size if isinstance(size, int) and not isinstance(size, bool) and size >= 0 else 0
    print("E\t%d\t%d\t%d\t%s\t%s" % (i, r, size, clean("null" if last is None else last) or "-", clean(e["key"])))
'

list_failed() {
  echo "cache-cleanup error: list failed ref=${ref}" >&2
  exit 1
}

response="$(gh api --method GET --paginate --slurp "repos/${GH_REPO}/actions/caches" -f "ref=${ref}" -F per_page=100)" ||
  list_failed
status=0
plan="$(python3 -I -c "${select_program}" "${mode}" "${ref}" "${days:-0}" <<< "${response}")" || status=$?
case "${status}" in
  0) ;;
  4) exit 1 ;;
  *) list_failed ;;
esac

listed=0
selected=0
cutoff="none"
deleted=0
would_delete=0
already_gone=0
freed_bytes=0
exit_code=0

print_summary() {
  echo "cache-cleanup summary mode=${mode} ref=${ref} listed=${listed} selected=${selected} deleted=${deleted}" \
    "would_delete=${would_delete} already_gone=${already_gone} freed_bytes=${freed_bytes} cutoff=${cutoff}"
}

while IFS=$'\t' read -r tag a b c d e; do
  case "${tag}" in
    H)
      listed="${a}"
      selected="${b}"
      cutoff="${c}"
      ;;
    E)
      id="${a}"
      rank="${b}"
      size="${c}"
      last="${d}"
      key="${e}"
      line="id=${id} rank=${rank} key=${key} size_bytes=${size} last_accessed_at=${last}"
      if [[ "${DRY_RUN}" == "true" ]]; then
        would_delete=$((would_delete + 1))
        echo "cache action=would-delete ${line}"
        continue
      fi
      if err="$(gh api --method DELETE "repos/${GH_REPO}/actions/caches/${id}" 2>&1 >/dev/null)"; then
        deleted=$((deleted + 1))
        freed_bytes=$((freed_bytes + size))
        echo "cache action=delete ${line}"
      elif [[ "${err}" == *"HTTP 404"* ]]; then
        already_gone=$((already_gone + 1))
        echo "cache action=already-gone ${line}"
      else
        http_status="unknown"
        if [[ "${err}" =~ HTTP\ ([0-9]{3}) ]]; then
          http_status="${BASH_REMATCH[1]}"
        fi
        echo "cache-cleanup error: delete failed id=${id} status=${http_status}" >&2
        exit_code=1
        break
      fi
      ;;
  esac
done <<< "${plan}"

print_summary
exit "${exit_code}"
