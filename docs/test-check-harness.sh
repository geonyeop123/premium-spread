#!/usr/bin/env bash
# test-check-harness.sh — check-harness.sh 동작 검증.
# 사용법: test-check-harness.sh
# 출력: 케이스별 ok/FAIL 을 stdout 으로. 하나라도 FAIL 이면 exit 1.
#
# 정상 표본이 위반 표본과 함께 있어야 한다. 정상 표본이 없으면 "무조건 exit 1" 인 스크립트도
# 위반 표본 전부를 통과시킨다. 반대로 위반 표본이 없으면 "무조건 exit 0" 이 통과한다.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/check-harness.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail=0
LAST_OUT=""

# 통과해야 하는 최소 하네스 트리를 만든다. 각 케이스는 이 트리를 만든 뒤 한 군데만 변형한다.
make_ok() {
  local r="$1"
  mkdir -p "$r/.claude/agents" \
           "$r/.claude/rules" \
           "$r/.claude/skills/alpha-skill" \
           "$r/.claude/skills/orchestrator" \
           "$r/.ai/rules" \
           "$r/apps/api" \
           "$r/docs/runbooks"
  : > "$r/.ai/rules/architecture.md"
  : > "$r/apps/api/build.gradle.kts"
  : > "$r/docs/runbooks/redis-contract.md"

  cat > "$r/CLAUDE.md" <<'FIXTURE'
# CLAUDE.md

## 하네스

| 항목 | 내용 |
|------|------|
| 정합성 검사 | `./gradlew harnessCheck` |
| 규칙 원본 | `.ai/rules/architecture.md` |
FIXTURE

  cat > "$r/.claude/rules/architecture.md" <<'FIXTURE'
@../../.ai/rules/architecture.md
FIXTURE

  cat > "$r/.claude/agents/alpha.md" <<'FIXTURE'
---
name: alpha
description: 테스트용 에이전트.
---

# Alpha

`apps/api/build.gradle.kts` 와 `docs/runbooks/redis-contract.md` 를 본다.

## 재호출 시

이전 산출물을 먼저 읽는다.

## 팀 통신 프로토콜

| 대상 | 시점 | 내용 |
|---|---|---|
| **호출한 오케스트레이터** | 작업 종료 시 항상 | 결과 요약 + 산출물 경로 + 미완 항목 |
FIXTURE

  cat > "$r/.claude/skills/alpha-skill/SKILL.md" <<'FIXTURE'
---
name: alpha-skill
description: 테스트용 스킬.
---

# Alpha Skill

`.ai/rules/architecture.md` 를 따른다.
FIXTURE

  cat > "$r/.claude/skills/orchestrator/SKILL.md" <<'FIXTURE'
---
name: orchestrator
description: 테스트용 오케스트레이터.
---

# 오케스트레이터

## ⓪ 컨텍스트 확인 (읽기 전용, ① 앞)

읽기만 한다.

## ① worktree 격리 (스킵 불가, 항상 최초)

worktree 를 만든다.

## ⑤ plan

계획을 쓴다.

### ⑤ 코드 계약의 밀도 — 전문 칸과 요건 칸

전문 칸과 요건 칸을 나눈다.

## ⑥ 스펙 리뷰

리뷰한다.

### ⑥ 전용 체크 축 — 계획의 단언 ↔ 기존 코드

기존 코드와 대조한다.

## ⑪ 마무리 (스킵 불가)

### ⑪-d 피드백 수집

한 번 묻는다.
FIXTURE
}

# 케이스 실행기. <설명> <기대 exit> <변형 함수>
run_case() {
  local desc="$1" want="$2" mutate="$3"
  local r="$TMP/case-$RANDOM$RANDOM"
  mkdir -p "$r"
  make_ok "$r"
  "$mutate" "$r"
  LAST_OUT="$(bash "$CHECK" --root "$r" 2>&1)"
  local got=$?
  if [[ "$got" -eq "$want" ]]; then
    echo "ok [$desc] exit $got"
  else
    echo "FAIL [$desc] exit $got, 기대 $want"
    echo "$LAST_OUT" | sed 's/^/      /'
    fail=1
  fi
}

# 직전 케이스의 출력에 기대 문자열이 들어 있는지 확인한다. exit code 만 보면
# "다른 이유로 실패" 를 검출로 착각한다.
expect_output() {  # <설명> <문자열>
  if grep -qF -- "$2" <<< "$LAST_OUT"; then
    echo "ok [$1] 보고 확인: $2"
  else
    echo "FAIL [$1] 기대 문자열 없음: $2"
    echo "$LAST_OUT" | sed 's/^/      /'
    fail=1
  fi
}

noop() { :; }

# ── 정상 표본 ───────────────────────────────────────────────────────────

run_case "정상 트리" 0 noop

m_fenced_dead() {  # 펜스 안의 죽은 경로는 검사 대상이 아니다
  cat >> "$1/.claude/agents/alpha.md" <<'F'

```bash
cat docs/does-not-exist.md
```
F
}
run_case "펜스 안 죽은 경로 무시" 0 m_fenced_dead

m_ellipsis() {  # `.../` 생략 표기는 실재하는 경로가 아니다
  printf '\n`apps/api/.../db/migration` 은 생략 표기다.\n' >> "$1/.claude/agents/alpha.md"
}
run_case "생략 표기 무시" 0 m_ellipsis

m_placeholder() {  # 템플릿 플레이스홀더
  printf '\n`docs/work/{slug}/design.md` 에 쓴다.\n' >> "$1/.claude/agents/alpha.md"
}
run_case "플레이스홀더 무시" 0 m_placeholder

m_external() {  # 이 저장소가 소유하지 않는 경로는 검사하지 않는다
  printf '\n`node_modules/whatever/x.js` 는 외부다.\n' >> "$1/.claude/agents/alpha.md"
}
run_case "외부 경로 무시" 0 m_external

# ── 위반 표본: 축 1 참조 무결성 ─────────────────────────────────────────

m_dead_docs() { printf '\n`docs/runbooks/gone.md` 를 본다.\n' >> "$1/.claude/agents/alpha.md"; }
run_case "죽은 참조(docs)" 1 m_dead_docs
expect_output "죽은 참조(docs)" "죽은 참조 — docs/runbooks/gone.md"

# aic-api 가 놓쳤던 축이다. 경로 화이트리스트에 apps/ 가 없어 앱 rename 때 하네스 참조
# 4곳이 9일간 죽은 채 게이트를 통과했다. 이 케이스가 그 회귀를 막는다.
m_dead_apps() { printf '\n`apps/gone-api/build.gradle.kts` 를 본다.\n' >> "$1/.claude/agents/alpha.md"; }
run_case "죽은 참조(apps)" 1 m_dead_apps
expect_output "죽은 참조(apps)" "죽은 참조 — apps/gone-api/build.gradle.kts"

m_dead_import() { printf '@../../.ai/rules/gone.md\n' > "$1/.claude/rules/architecture.md"; }
run_case "죽은 import" 1 m_dead_import
expect_output "죽은 import" "죽은 import"

m_unclosed_fence() { printf '\n```bash\necho hi\n' >> "$1/.claude/agents/alpha.md"; }
run_case "닫히지 않은 코드펜스" 1 m_unclosed_fence
expect_output "닫히지 않은 코드펜스" "닫히지 않은 코드펜스"

# ── 위반 표본: 축 2 frontmatter ─────────────────────────────────────────

m_no_start() { printf '# 제목만 있다\n' > "$1/.claude/agents/alpha.md"; }
run_case "frontmatter 시작 누락" 1 m_no_start
expect_output "frontmatter 시작 누락" "frontmatter 시작 누락"

m_no_close() { printf -- '---\nname: alpha\ndescription: x\n\n# 본문\n' > "$1/.claude/agents/alpha.md"; }
run_case "frontmatter 종료 누락" 1 m_no_close
expect_output "frontmatter 종료 누락" "frontmatter 종료 누락"

m_name_mismatch() {
  sed -i 's/^name: alpha$/name: beta/' "$1/.claude/agents/alpha.md"
}
run_case "name 불일치" 1 m_name_mismatch
expect_output "name 불일치" "name 불일치"

m_no_desc() {
  sed -i '/^description:/d' "$1/.claude/skills/alpha-skill/SKILL.md"
}
run_case "description 누락" 1 m_no_desc
expect_output "description 누락" "description 누락"

m_dup_name() {  # 에이전트와 스킬이 같은 name 을 가지면 호출이 갈린다
  mkdir -p "$1/.claude/skills/alpha"
  printf -- '---\nname: alpha\ndescription: 중복.\n---\n\n# Alpha\n' > "$1/.claude/skills/alpha/SKILL.md"
}
run_case "name 전역 중복" 1 m_dup_name
expect_output "name 전역 중복" "name 전역 중복"

# ── 위반 표본: 축 3 계약 단정 ───────────────────────────────────────────

m_no_recall() { sed -i '/^## 재호출 시$/d' "$1/.claude/agents/alpha.md"; }
run_case "필수 섹션 누락(재호출 시)" 1 m_no_recall
expect_output "필수 섹션 누락(재호출 시)" "^## 재호출 시"

m_no_report_row() { sed -i '/호출한 오케스트레이터/d' "$1/.claude/agents/alpha.md"; }
run_case "필수 행 누락(완료 보고)" 1 m_no_report_row
expect_output "필수 행 누락(완료 보고)" "호출한 오케스트레이터"

m_no_harness_section() { sed -i '/^## 하네스$/d' "$1/CLAUDE.md"; }
run_case "필수 섹션 누락(CLAUDE 하네스)" 1 m_no_harness_section
expect_output "필수 섹션 누락(CLAUDE 하네스)" "^## 하네스"

m_no_stage0() { sed -i '/^## ⓪ 컨텍스트 확인/d' "$1/.claude/skills/orchestrator/SKILL.md"; }
run_case "필수 섹션 누락(⓪)" 1 m_no_stage0
expect_output "필수 섹션 누락(⓪)" "^## ⓪ 컨텍스트 확인"

m_no_density() { sed -i '/^### ⑤ 코드 계약의 밀도/d' "$1/.claude/skills/orchestrator/SKILL.md"; }
run_case "필수 섹션 누락(⑤ 밀도)" 1 m_no_density
expect_output "필수 섹션 누락(⑤ 밀도)" "^### ⑤ 코드 계약의 밀도"

m_no_axis6() { sed -i '/^### ⑥ 전용 체크 축/d' "$1/.claude/skills/orchestrator/SKILL.md"; }
run_case "필수 섹션 누락(⑥ 축)" 1 m_no_axis6
expect_output "필수 섹션 누락(⑥ 축)" "^### ⑥ 전용 체크 축"

m_no_feedback() { sed -i '/^### ⑪-d 피드백 수집$/d' "$1/.claude/skills/orchestrator/SKILL.md"; }
run_case "필수 섹션 누락(⑪ 피드백)" 1 m_no_feedback
expect_output "필수 섹션 누락(⑪ 피드백)" "피드백 수집"

m_order_swapped() {  # ⓪ 을 ① 뒤로 보낸다
  local f="$1/.claude/skills/orchestrator/SKILL.md"
  sed -i 's/^## ⓪ 컨텍스트 확인 (읽기 전용, ① 앞)$/## ZZZTMP/' "$f"
  sed -i 's/^## ① worktree 격리 (스킵 불가, 항상 최초)$/## ⓪ 컨텍스트 확인 (읽기 전용, ① 앞)/' "$f"
  sed -i 's/^## ZZZTMP$/## ① worktree 격리 (스킵 불가, 항상 최초)/' "$f"
}
run_case "단계 순서 위반(⓪ ↔ ①)" 1 m_order_swapped
expect_output "단계 순서 위반(⓪ ↔ ①)" "순서 위반"

m_empty_skill_dir() { mkdir -p "$1/.claude/skills/ghost/references"; }
run_case "SKILL.md 없는 스킬 디렉터리" 1 m_empty_skill_dir
expect_output "SKILL.md 없는 스킬 디렉터리" "SKILL.md 없는 스킬 디렉터리"

m_no_agents() { rm -f "$1"/.claude/agents/*.md; }
run_case "에이전트 0개" 1 m_no_agents
expect_output "에이전트 0개" "에이전트 파일 0개"

# ── 판정 ────────────────────────────────────────────────────────────────

echo
if [[ $fail -ne 0 ]]; then
  echo "== 자가검증 실패 =="
  exit 1
fi
echo "== 모든 케이스 통과 =="
