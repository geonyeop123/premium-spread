#!/usr/bin/env bash
# Phase 0 DoD 검증 러너.
#
#   bash docs/work/private-live-autotrader-phase-0/verify.sh            # 전체 (T1+T2 실행, T4 는 수동 표시)
#   bash docs/work/private-live-autotrader-phase-0/verify.sh --static   # T1 만 (빠른 확인)
#   bash docs/work/private-live-autotrader-phase-0/verify.sh AC1 AC23   # 지정한 것만
#
# 설계 원칙 — **모든 AC 를 빠짐없이 계상한다.**
#   초안은 "#### ACn command" 블록이 있는 AC 만 발견해, 표에만 명령이 있는 AC10~AC14 와 T4 인 AC20~AC22·AC27
#   총 9개를 조용히 건너뛰고 GREEN 을 보고했다. 검사기가 누락으로 거짓말하는 부류다. 이제 DoD 표의 AC 를
#   전수 열거하고, 실행할 수 없는 AC 는 MISSING 으로 실패시킨다.
#
# 명령 출처
#   - 표의 검증 명령 칸이 "아래 `ACn command`" 면 그 코드 블록을 추출해 실행한다
#   - 그 밖이면 칸 안의 백틱 명령을 그대로 실행한다
#   - 티어가 T4 면 사람 확인이므로 실행하지 않고 MANUAL 로 표시한다 (GREEN 으로 세지 않는다)
#
# 도구 제약: grep/awk/sed/find/git 만 쓴다 (dod.md "도구 제약" 참조).

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel) || exit 2
DOD="$ROOT/docs/work/private-live-autotrader-phase-0/dod.md"
cd "$ROOT" || exit 2
[ -f "$DOD" ] || { echo "dod.md 없음: $DOD"; exit 2; }

STATIC=0
targets=()
for a in "$@"; do
  case "$a" in
    --static) STATIC=1 ;;
    *) targets+=("$a") ;;
  esac
done

# DoD 표에서 "id|tier|명령칸" 을 뽑는다. 중복 id 는 첫 행만 쓴다.
table_rows() {
  grep -E '^\| AC[0-9]+ \|' "$DOD" \
  | awk -F'|' '{
      id=$2; tier=$5; cmd=$6;
      gsub(/^[ \t]+|[ \t]+$/, "", id);
      gsub(/^[ \t]+|[ \t]+$/, "", tier);
      gsub(/^[ \t]+|[ \t]+$/, "", cmd);
      if (!(id in seen)) { seen[id]=1; print id "\t" tier "\t" cmd }
    }'
}

extract_block() {
  awk -v want="#### $1 command" '
    $0 == want { insec = 1; next }
    insec && /^#### / { exit }
    insec && /^```bash$/ { inblk = 1; next }
    insec && inblk && /^```$/ { exit }
    insec && inblk { print }
  ' "$DOD"
}

# 표 칸에서 실행 가능한 명령을 뽑는다 (백틱 안의 내용).
inline_cmd() {
  printf '%s' "$1" | sed -E 's/^[^`]*`//; s/`[^`]*$//'
}

pass=0; fail=0; missing=0; manual=0; skipped=0
declare -a MISSING_IDS=() FAIL_IDS=() MANUAL_IDS=()

while IFS=$'\t' read -r id tier cmdcell; do
  [ -n "$id" ] || continue
  if [ ${#targets[@]} -gt 0 ]; then
    hit=0; for x in "${targets[@]}"; do [ "$x" = "$id" ] && hit=1; done
    [ "$hit" -eq 1 ] || continue
  fi

  if [ "$tier" = "T4" ]; then
    printf '%-6s %-7s %s\n' "$id" "MANUAL" "사람 확인 — 증거 로그로 판정 (자동 GREEN 아님)"
    manual=$((manual + 1)); MANUAL_IDS+=("$id"); continue
  fi

  if [ "$STATIC" -eq 1 ] && [ "$tier" != "T1" ]; then
    printf '%-6s %-7s %s\n' "$id" "SKIP" "--static 모드에서 $tier 미실행"
    skipped=$((skipped + 1)); continue
  fi

  body=""
  case "$cmdcell" in
    *"아래 \`$id command\`"*) body=$(extract_block "$id") ;;
    *'`'*) body=$(inline_cmd "$cmdcell") ;;
  esac

  if [ -z "$body" ]; then
    printf '%-6s %-7s %s\n' "$id" "MISSING" "실행 가능한 명령을 찾지 못함 (표 칸: ${cmdcell:0:40})"
    missing=$((missing + 1)); MISSING_IDS+=("$id"); continue
  fi

  out=$(bash -c "$body" 2>&1); rc=$?
  summary=$(printf '%s' "$out" | tr '\n' ' ' | sed 's/  */ /g' | cut -c1-130)
  if [ $rc -eq 0 ]; then
    printf '%-6s %-7s %s\n' "$id" "GREEN" "$summary"; pass=$((pass + 1))
  else
    printf '%-6s %-7s %s\n' "$id" "RED" "$summary"; fail=$((fail + 1)); FAIL_IDS+=("$id")
  fi
done < <(table_rows)

total=$(table_rows | grep -c .)
accounted=$((pass + fail + missing + manual + skipped))

echo
echo "GREEN=$pass RED=$fail MISSING=$missing MANUAL=$manual SKIP=$skipped"
if [ ${#targets[@]} -eq 0 ]; then
  echo "표의 AC 총 $total개 중 $accounted개 계상"
  [ "$accounted" -eq "$total" ] || { echo "계상 누락 — 러너가 표의 AC 를 전부 다루지 못했다"; exit 3; }
fi
[ ${#MISSING_IDS[@]} -eq 0 ] || echo "MISSING: ${MISSING_IDS[*]}"
[ ${#FAIL_IDS[@]}    -eq 0 ] || echo "RED: ${FAIL_IDS[*]}"
[ ${#MANUAL_IDS[@]}  -eq 0 ] || echo "MANUAL(증거 로그 확인 필요): ${MANUAL_IDS[*]}"

[ "$fail" -eq 0 ] && [ "$missing" -eq 0 ]
