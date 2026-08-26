#!/usr/bin/env bash
# Phase 0 DoD 검증 러너.
#
#   bash .../verify.sh                  # 최종 판정. T4 증거가 없으면 실패한다
#   bash .../verify.sh --pre-approval   # 승인 전 확인. T4 미해결을 허용한다 (최종 판정으로 쓸 수 없다)
#   bash .../verify.sh --static         # T1 만 (빠른 확인)
#   bash .../verify.sh AC1 AC23         # 지정한 것만. 없는 ID 를 주면 실패한다
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

STATIC=0; PRE=0
targets=()
for a in "$@"; do
  case "$a" in
    --static) STATIC=1 ;;
    --pre-approval) PRE=1 ;;
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

# 요청한 ID 가 표에 없으면 즉시 실패한다. 오타·이름 변경·행 삭제로 "아무것도 안 돌리고 성공" 하는 것을 막는다.
if [ ${#targets[@]} -gt 0 ]; then
  known=$(grep -oE '^\| AC[0-9]+ \|' "$DOD" | awk '{print $2}' | sort -u)
  unknown=""
  for x in "${targets[@]}"; do printf '%s\n' "$known" | grep -qx "$x" || unknown="$unknown $x"; done
  if [ -n "$unknown" ]; then
    echo "요청한 AC 가 dod.md 표에 없다:$unknown"
    exit 2
  fi
fi

# T4 의 증거 로그 GREEN 칸이 채워졌는지 본다.
# **증거 로그 구간으로 한정한다** — 수용기준 표에도 "| ACn |" 행이 있고 그 4번째 칸은
# 근거 원문이라 항상 비어 있지 않다. 구간을 한정하지 않으면 모든 T4 가 "기록됨" 으로 오판된다.
manual_resolved() {
  awk -F'|' -v id="$1" '
    /^## 증거 로그/ { inlog=1; next }
    inlog && /^## / { exit }
    inlog && $0 ~ /^\| AC[0-9]+ \|/ {
      a=$2; g=$4;
      gsub(/^[ \t]+|[ \t]+$/, "", a); gsub(/^[ \t]+|[ \t]+$/, "", g);
      if (a == id && g != "") { found=1 }
    }
    END { exit(found ? 0 : 1) }
  ' "$DOD"
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
    if manual_resolved "$id"; then
      printf '%-6s %-7s %s\n' "$id" "MANUAL" "증거 로그 기록됨"
      manual=$((manual + 1)); MANUAL_IDS+=("$id")
    elif [ "$PRE" -eq 1 ]; then
      printf '%-6s %-7s %s\n' "$id" "PENDING" "증거 미기록 — --pre-approval 이라 허용"
      manual=$((manual + 1)); MANUAL_IDS+=("$id")
    else
      printf '%-6s %-7s %s\n' "$id" "RED" "사람 확인 증거가 증거 로그에 없다"
      fail=$((fail + 1)); FAIL_IDS+=("$id")
    fi
    continue
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
mode="최종 판정"
[ "$PRE" -eq 1 ] && mode="승인 전 확인 (최종 판정으로 쓸 수 없다)"
[ "$STATIC" -eq 1 ] && mode="$mode / --static"
echo "모드: $mode"
echo "GREEN=$pass RED=$fail MISSING=$missing MANUAL=$manual SKIP=$skipped"
if [ ${#targets[@]} -eq 0 ]; then
  echo "표의 AC 총 $total개 중 $accounted개 계상"
  [ "$accounted" -eq "$total" ] || { echo "계상 누락 — 러너가 표의 AC 를 전부 다루지 못했다"; exit 3; }
fi
[ ${#MISSING_IDS[@]} -eq 0 ] || echo "MISSING: ${MISSING_IDS[*]}"
[ ${#FAIL_IDS[@]}    -eq 0 ] || echo "RED: ${FAIL_IDS[*]}"
[ ${#MANUAL_IDS[@]}  -eq 0 ] || echo "MANUAL(증거 로그 확인 필요): ${MANUAL_IDS[*]}"

[ "$fail" -eq 0 ] && [ "$missing" -eq 0 ]
