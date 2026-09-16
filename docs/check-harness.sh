#!/usr/bin/env bash
# check-harness.sh — 하네스 정합성 검사기. 읽기 전용, 판단 없음.
# 사용법: check-harness.sh [--root <dir>]
# 출력: 위반을 "파일:라인: 사유" 로 stdout 에 나열하고, 위반이 1건 이상이면 exit 1.
#
# set -e 를 쓰지 않는다. 위반을 누적해 한 번에 보고해야 하기 때문이다.
# 한 건씩 고치고 다시 돌리게 만들면 사람이 게이트를 우회한다.
#
# 검사 축
#   1. 참조 무결성 — 본문(펜스 밖)의 백틱 경로와 @import 대상이 실재하는가
#   2. frontmatter — name 이 파일명/디렉터리명과 같은가, description 이 비지 않았는가, name 이 전역 유일한가
#   3. 계약 단정   — 필수 절·단계 순서·스킬 디렉터리 형태
#
# docs/check-documentation.sh 는 정본 문서(AGENTS.md·.ai/*·docs/runbooks/*)를 보고,
# 이 스크립트는 하네스 파일(CLAUDE.md·.claude/**)을 본다. 둘은 대상이 겹치지 않는다.
set -uo pipefail
shopt -s nullglob

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)
      [[ $# -ge 2 ]] || { echo "ERROR: --root 인자 누락" >&2; exit 2; }
      ROOT="$(cd "$2" && pwd)" || exit 2
      shift 2
      ;;
    *) echo "ERROR: 알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
cd "$ROOT" || { echo "ERROR: root 디렉터리 없음: $ROOT" >&2; exit 2; }

# ── 데이터 ──────────────────────────────────────────────────────────────

# 벤더링 스킬. 원본과 byte-identical 유지 계약이라 이 저장소에서 수정할 수 없다
# (.claude/skills/orchestrator/SKILL.md "저장소 포함" 절).
# 참조 축에서만 제외한다 — 구조 축은 검사한다. frontmatter 파손은 재벤더링으로 고칠 수 있고,
# 죽은 문서 참조는 upstream 의 서술이라 이 저장소에서 고칠 수 없다.
VENDORED=(definition-of-done explain-pr)

# 이 저장소가 소유하는 경로 접두. 여기에 없는 접두는 외부 참조로 보고 검사하지 않는다.
# apps/·modules/·infrastructure/ 를 처음부터 넣는다 — 자매 저장소 aic-api 는 이것들을 빼고
# 시작했다가 앱 rename 때 하네스 참조 4곳이 9일간 죽은 채 게이트를 통과했다.
OWNED_PREFIX=(
  docs/ .ai/ .claude/ ci/ config/ http/ gradle/ docker/
  apps/ modules/ infrastructure/ supports/ architecture-tests/ build-logic/
)
# 접두가 아니라 파일명 그 자체인 것들.
OWNED_FILE=(build.gradle.kts settings.gradle.kts gradle.properties gradlew AGENTS.md CLAUDE.md)

# 부재가 하네스 문서 본문에 명시된 경로. 정확히 같거나 그 경로 하위(슬래시로 경계를 고정)일 때만
# 허용한다 — 문자열 접두 일치가 아니다. 새 항목은 그 부재가 하네스 문서에 적혀 있을 때만 추가한다.
ALLOW_MISSING=(
  ".ai/diagrams"   # .claude/skills/orchestrator/SKILL.md ⑪-a "없는 경로를 갱신했다고 보고하지 않는다"
)

violations=0
files_checked=0
declare -A SEEN_NAMES=()
declare -A MASKED_CACHE=()
MASKED_TMP_FILES=()
trap 'rm -f "${MASKED_TMP_FILES[@]}"' EXIT

report() {  # <위치> <사유>
  printf '%s: %s\n' "$1" "$2"
  violations=$((violations + 1))
}

is_vendored() {
  local v
  for v in "${VENDORED[@]}"; do [[ "$1" == "$v" ]] && return 0; done
  return 1
}

is_allowed_missing() {
  local a
  for a in "${ALLOW_MISSING[@]}"; do
    [[ "$1" == "$a" || "$1" == "$a"/* ]] && return 0
  done
  return 1
}

is_owned() {
  local p f
  for p in "${OWNED_PREFIX[@]}"; do [[ "$1" == "$p"* ]] && return 0; done
  for f in "${OWNED_FILE[@]}"; do [[ "$1" == "$f" ]] && return 0; done
  return 1
}

# 펜스 코드블록을 빈 줄로 치환한다. 원본 줄 번호를 유지하므로 호출자는 마스킹된 파일에
# grep -n 을 그대로 쓸 수 있다. 여는 마커와 같은 문자·같은 길이 이상으로만 닫는다 —
# 4-백틱 펜스 안의 3-백틱 줄이 조기에 펜스를 닫는 중첩 오탐을 막는다.
# 결과를 파일별로 캐시한다. 캐시가 없으면 "닫히지 않은 코드펜스" 가 파일당 두 번 보고된다.
mask_fenced() {  # <파일> — 결과는 MASKED_CACHE[$file]. stdout 으로 반환하지 않는다:
                 # command substitution 으로 호출하면 내부 report() 가 stdout 을 오염시키고
                 # violations 증가가 부모 셸에 반영되지 않는다.
  local file="$1" out lineno=0 fence=0 fence_char="" fence_len=0 open_lineno=0
  local line trimmed first rest n

  [[ -n "${MASKED_CACHE[$file]:-}" ]] && return

  out="$(mktemp)"
  MASKED_TMP_FILES+=("$out")

  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    line="${line%$'\r'}"
    trimmed="${line#"${line%%[![:space:]]*}"}"
    first="${trimmed:0:1}"

    if [[ $fence -eq 0 ]]; then
      if [[ "$first" == '`' || "$first" == '~' ]]; then
        rest="$trimmed"; n=0
        while [[ "${rest:0:1}" == "$first" ]]; do n=$((n + 1)); rest="${rest:1}"; done
        if [[ $n -ge 3 ]]; then
          fence=1; fence_char="$first"; fence_len="$n"; open_lineno="$lineno"
          printf '\n' >> "$out"; continue
        fi
      fi
      printf '%s\n' "$line" >> "$out"
    else
      if [[ "$first" == "$fence_char" ]]; then
        rest="$trimmed"; n=0
        while [[ "${rest:0:1}" == "$first" ]]; do n=$((n + 1)); rest="${rest:1}"; done
        if [[ $n -ge $fence_len ]]; then
          fence=0; printf '\n' >> "$out"; continue
        fi
      fi
      printf '\n' >> "$out"
    fi
  done < "$file"

  if [[ $fence -eq 1 ]]; then
    report "$file:$open_lineno" "닫히지 않은 코드펜스 — 이 줄에서 연 펜스가 파일 끝까지 닫히지 않아 그 아래 전체가 스캔에서 빠진다"
  fi

  MASKED_CACHE[$file]="$out"
}

# ── 축 1: 참조 무결성 ───────────────────────────────────────────────────

check_path() {  # <파일> <라인번호> <토큰>
  local file="$1" lineno="$2" tok="$3"
  tok="${tok%%[[:space:]]*}"
  # 꼬리 문장부호 제거. `.ai/rules/architecture.md §2` 같은 절 표기 뒤에 남는 것들.
  while [[ -n "$tok" ]]; do
    case "$tok" in
      *. | *, | *\) | *\; | *: | *\* | *\" | *\' | *\?) tok="${tok%?}" ;;
      *) break ;;
    esac
  done
  [[ -n "$tok" ]] || return 0
  # 생략 표기. apps/api/.../db/migration 처럼 중간을 줄인 것은 실재하는 경로가 아니다.
  case "$tok" in */.../*) return 0 ;; esac
  # 템플릿 플레이스홀더는 실체가 없는 것이 정상이다.
  case "$tok" in
    *'{'* | *'}'* | *'<'* | *'>'* | *'*'* | *'$'*) return 0 ;;
  esac
  is_owned "$tok" || return 0
  is_allowed_missing "$tok" && return 0
  [[ -e "$tok" ]] && return 0
  report "$file:$lineno" "죽은 참조 — $tok"
}

# @../../.ai/rules/x.md 처럼 파일 기준 상대 경로인 import 를 해석해 검사한다.
# .claude/rules/*.md 는 본문이 이 한 줄뿐이라 대상이 옮겨가면 파일 전체가 조용히 무의미해진다.
check_import() {  # <파일> <라인번호> <@뒤 토큰>
  local file="$1" lineno="$2" tok="$3" dir resolved
  tok="${tok%%[[:space:]]*}"
  [[ -n "$tok" ]] || return 0
  dir="$(dirname "$file")"
  if [[ "$tok" == /* ]]; then
    resolved="$tok"
  else
    resolved="$(realpath -m --relative-to="$ROOT" "$dir/$tok" 2>/dev/null)" || resolved=""
  fi
  if [[ -z "$resolved" ]]; then
    report "$file:$lineno" "import 경로 해석 실패 — @$tok"
    return
  fi
  [[ -e "$resolved" ]] && return 0
  report "$file:$lineno" "죽은 import — @$tok (해석: $resolved)"
}

check_refs() {  # <파일>
  local file="$1" lineno=0 line trimmed rest inside masked
  mask_fenced "$file"
  masked="${MASKED_CACHE[$file]}"
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    trimmed="${line#"${line%%[![:space:]]*}"}"
    [[ -z "$trimmed" ]] && continue

    # import 행은 백틱이 없다.
    if [[ "$trimmed" == @* ]]; then
      check_import "$file" "$lineno" "${trimmed#@}"
      continue
    fi

    # 백틱으로 감싼 토큰. 이 저장소의 하네스는 경로를 예외 없이 백틱으로 적는다.
    rest="$line"
    while [[ "$rest" == *'`'*'`'* ]]; do
      rest="${rest#*\`}"
      inside="${rest%%\`*}"
      rest="${rest#*\`}"
      check_path "$file" "$lineno" "$inside"
    done
  done < "$masked"
  files_checked=$((files_checked + 1))
}

check_refs CLAUDE.md

# 에이전트 디렉터리가 전부 비면 아래 glob 이 0회 반복되어 축 1·2·3의 모든 .claude/agents/*.md
# 단정이 조용히 공회전한다 — nullglob 이 켜져 있어 에러 대신 exit 0 이 된다. 하한을 단정한다.
agent_count=0
for f in .claude/agents/*.md; do
  check_refs "$f"
  agent_count=$((agent_count + 1))
done
[[ $agent_count -ge 1 ]] || report ".claude/agents" "에이전트 파일 0개 — 최소 1개가 있어야 한다. 없으면 축 1·2·3의 .claude/agents/*.md 단정이 전부 공회전한다"

for f in .claude/rules/*.md; do
  check_refs "$f"
done

for d in .claude/skills/*/; do
  name="${d%/}"; name="${name##*/}"
  is_vendored "$name" && continue
  [[ -f "${d}SKILL.md" ]] || continue
  check_refs "${d}SKILL.md"
done

# ── 축 2: frontmatter ───────────────────────────────────────────────────
#
# .claude/rules/*.md 는 대상이 아니다. 그 파일들의 frontmatter 는 paths: 조건부 로딩이며
# name/description 을 갖지 않는다.

check_frontmatter() {  # <파일> <기대 name> <기대 name 의 출처 설명>
  local file="$1" expected="$2" kind="$3" name desc first_line close_line

  # head -n1 은 바이트 그대로 반환한다. CRLF 파일이면 "---\r" 이 되어 "---" 와 다르다고 오판한다.
  first_line="$(head -n 1 "$file")"
  if [[ "${first_line%$'\r'}" != "---" ]]; then
    report "$file:1" "frontmatter 시작 누락 — 1행이 '---' 이 아니다"
    return
  fi

  # 닫는 '---' 가 없으면 아래 awk 는 파일 끝까지 스캔해 본문의 'name:'/'description:' 로 시작하는
  # 줄을 frontmatter 값으로 잘못 집는다. 닫는 줄을 먼저 찾아 없으면 그 자체를 보고한다.
  close_line="$(awk 'NR>1 && /^---[[:space:]]*\r?$/ {print NR; exit}' "$file")"
  if [[ -z "$close_line" ]]; then
    report "$file:1" "frontmatter 종료 누락 — 여는 '---' 뒤에 닫는 '---' 가 없다. Claude Code 가 이 파일을 파싱하지 못한다"
    return
  fi

  name="$(awk -v end="$close_line" 'NR>1 && NR<end && /^name:/ {sub(/^name:[[:space:]]*/,""); sub(/[[:space:]\r]+$/,""); print; exit}' "$file")"
  desc="$(awk -v end="$close_line" 'NR>1 && NR<end && /^description:/ {sub(/^description:[[:space:]]*/,""); sub(/[[:space:]\r]+$/,""); print; exit}' "$file")"

  if [[ -z "$name" ]]; then
    report "$file:1" "frontmatter name 누락 또는 값이 빔"
  elif [[ "$name" != "$expected" ]]; then
    report "$file:1" "name 불일치 — frontmatter '$name', $kind '$expected'"
  elif [[ -n "${SEEN_NAMES[$name]:-}" ]]; then
    report "$file:1" "name 전역 중복 — '$name' 이 ${SEEN_NAMES[$name]} 에도 있다"
  else
    SEEN_NAMES[$name]="$file"
  fi

  [[ -z "$desc" ]] && report "$file:1" "frontmatter description 누락 또는 값이 빔"
}

for f in .claude/agents/*.md; do
  base="${f##*/}"
  check_frontmatter "$f" "${base%.md}" "파일명"
done
for d in .claude/skills/*/; do
  name="${d%/}"; name="${name##*/}"
  [[ -f "${d}SKILL.md" ]] || continue
  check_frontmatter "${d}SKILL.md" "$name" "디렉터리명"
done

# ── 축 3: 계약 단정 ─────────────────────────────────────────────────────
#
# 이 축은 참조·frontmatter 같은 일반 무결성이 아니라 이 저장소가 내린 설계 결정을 강제한다.
# 요구가 아니므로 담이 아니라 문턱이어야 한다 — 각 단정이 실패할 때 "왜 있는지" 와
# "의도적으로 없애려면 무엇을 하는지" 를 함께 알려준다.

require_section() {  # <파일> <정규식> <제거 조건>
  local f="$1" re="$2" howto="$3" masked
  if [[ ! -f "$f" ]]; then
    report "$f" "필수 파일 부재"
    return
  fi
  mask_fenced "$f"
  masked="${MASKED_CACHE[$f]}"
  grep -Eq -- "$re" "$masked" || report "$f:1" "필수 섹션 누락 — $re — 제거 조건: $howto"
}

require_order() {  # <파일> <앞에 와야 할 정규식> <뒤에 와야 할 정규식> <제거 조건>
  local f="$1" a="$2" b="$3" howto="$4" la lb masked
  [[ -f "$f" ]] || return 0
  mask_fenced "$f"
  masked="${MASKED_CACHE[$f]}"
  la="$(grep -En -- "$a" "$masked" | head -n 1)"; la="${la%%:*}"
  lb="$(grep -En -- "$b" "$masked" | head -n 1)"; lb="${lb%%:*}"
  # 부재는 require_section 이 보고한다. 여기서 중복 보고하지 않는다.
  [[ -n "$la" && -n "$lb" ]] || return 0
  [[ "$la" -lt "$lb" ]] || report "$f:$la" "순서 위반 — '$a'($la) 가 '$b'($lb) 보다 뒤에 있다 — 제거 조건: $howto"
}

ORCH=.claude/skills/orchestrator/SKILL.md

# 이전 산출물이 있을 때의 행동. 7번째 에이전트를 추가하는 사람도 걸려야 한다.
for f in .claude/agents/*.md; do
  require_section "$f" '^## 재호출 시' \
    '이 요구를 의도적으로 버리려면 각 에이전트 문서에서 이 절을 지우고, 이 require_section 호출을 같은 커밋에서 함께 지운다'
done

# 호출한 쪽으로의 완료 보고 의무. 표가 수평 통신(에이전트끼리)만 정의하는데 오케스트레이터는
# 허브로 돈다. spec-reviewer·code-reviewer·qa-agent 는 Write 도구가 없어 메시지가 유일한 산출물이다.
for f in .claude/agents/*.md; do
  require_section "$f" '^\| \*\*호출한 오케스트레이터\*\*' \
    '허브형 완료 보고 의무를 버리려면 각 에이전트의 팀 통신 프로토콜 표에서 이 행을 지우고, 이 require_section 호출을 같은 커밋에서 함께 지운다'
done

# 하네스 진입점. 사라지면 트리거 규칙과 변경 이력이 함께 사라진다.
require_section CLAUDE.md '^## 하네스' \
  '하네스 진입점을 다른 곳으로 옮기려면 트리거 규칙과 변경 이력을 이전한 뒤 이 require_section 호출을 같은 커밋에서 지운다'

# 컨텍스트 확인. 없으면 후속 실행을 초기 실행으로 오인하고 동결된 dod 위에 새 요구가 얹힌다.
require_section "$ORCH" '^## ⓪ 컨텍스트 확인' \
  '후속 실행 컨텍스트 확인 단계를 버리려면 후속 실행을 초기 실행으로 오인하지 않는 대안을 설계한 뒤 이 require_section 호출을 같은 커밋에서 지운다'

# 피드백 수집. 없으면 피드백이 하네스로 되돌아오는 경로가 끊긴다.
require_section "$ORCH" '^### ⑪-[a-z] 피드백 수집' \
  '피드백 수집 단계를 버리려면 피드백이 하네스로 되돌아오는 다른 경로를 설계한 뒤 이 require_section 호출을 같은 커밋에서 지운다'

# ⑤ 밀도와 ⑥ 축은 한 쌍이다. 한쪽만 지우면 계획이 짧아진 채로 그 단언을 대조하는 자리가 사라진다.
require_section "$ORCH" '^### ⑤ 코드 계약의 밀도' \
  '밀도 계약을 버리려면 "⑥ 전용 체크 축" 을 함께 되돌리고 두 require_section 호출을 같은 커밋에서 지운다'
require_section "$ORCH" '^### ⑥ 전용 체크 축' \
  '이 축을 버리려면 "⑤ 코드 계약의 밀도" 를 함께 되돌리고 두 require_section 호출을 같은 커밋에서 지운다'

# ⓪ 은 읽기 전용이라 ① 쓰기 격리보다 앞이어야 한다. "격리가 항상 최초" 만 읽은 사람이 되돌린다.
require_order "$ORCH" '^## ⓪ 컨텍스트 확인' '^## ① worktree 격리' \
  '이 순서 요구를 버리려면 ⓪ 을 ① 뒤에 두어도 후속 실행에서 갈라진 트리가 둘 생기지 않는 근거를 남긴 뒤 이 require_order 호출을 같은 커밋에서 지운다'

# 스킬 디렉터리는 SKILL.md 를 가져야 한다. 없는 디렉터리는 스킬로 로드되지 않으면서
# 이름만 점유해, 실재하는 스킬과 헷갈리게 만든다.
for d in .claude/skills/*/; do
  [[ -f "${d}SKILL.md" ]] && continue
  report "${d}" "SKILL.md 없는 스킬 디렉터리 — 스킬로 로드되지 않으면서 이름만 점유한다. 쓰려면 SKILL.md 를 만들고, 아니면 디렉터리를 지운다"
done

# ── 판정 ────────────────────────────────────────────────────────────────

if [[ $violations -gt 0 ]]; then
  echo
  echo "하네스 검사 실패: 위반 ${violations}건"
  exit 1
fi

echo "하네스 검사 통과: 파일 ${files_checked}개"
