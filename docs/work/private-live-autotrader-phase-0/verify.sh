#!/usr/bin/env bash
# Phase 0 DoD 검증 러너.
#
# dod.md 의 "#### ACn command" 절에 있는 bash 코드 블록을 그대로 추출해 실행한다.
# 러너가 명령을 따로 갖지 않으므로 문서와 실행이 어긋날 수 없다.
#
#   bash docs/work/private-live-autotrader-phase-0/verify.sh          # 문서에 있는 T1 전부
#   bash docs/work/private-live-autotrader-phase-0/verify.sh AC1 AC2  # 지정한 것만
#
# 도구 제약: grep/awk/sed/find/git 만 쓴다 (dod.md "도구 제약" 참조).

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel) || exit 2
DOD="$ROOT/docs/work/private-live-autotrader-phase-0/dod.md"
cd "$ROOT" || exit 2

[ -f "$DOD" ] || { echo "dod.md 없음: $DOD"; exit 2; }

extract() {
  awk -v want="#### $1 command" '
    $0 == want { insec = 1; next }
    insec && /^#### / { exit }
    insec && /^```bash$/ { inblk = 1; next }
    insec && inblk && /^```$/ { exit }
    insec && inblk { print }
  ' "$DOD"
}

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  mapfile -t targets < <(grep -oE '^#### AC[0-9]+ command' "$DOD" | awk '{print $2}')
fi

pass=0; fail=0; missing=0
for ac in "${targets[@]}"; do
  body=$(extract "$ac")
  if [ -z "$body" ]; then
    printf '%-6s %-7s %s\n' "$ac" "MISSING" "dod.md 에 '#### $ac command' 블록 없음"
    missing=$((missing + 1))
    continue
  fi
  out=$(bash -c "$body" 2>&1); rc=$?
  summary=$(printf '%s' "$out" | tr '\n' ' ' | sed 's/  */ /g' | cut -c1-140)
  if [ $rc -eq 0 ]; then
    printf '%-6s %-7s %s\n' "$ac" "GREEN" "$summary"
    pass=$((pass + 1))
  else
    printf '%-6s %-7s %s\n' "$ac" "RED" "$summary"
    fail=$((fail + 1))
  fi
done

echo
echo "GREEN=$pass RED=$fail MISSING=$missing"
[ "$fail" -eq 0 ] && [ "$missing" -eq 0 ]
