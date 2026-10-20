---
feature: 세션 자동 로드 문서 정리
slug: harness-context-load
status: FROZEN
frozen_at: 2026-10-20
source: 2026-10-20 대화 — 매 세션 자동 로드 문서(CLAUDE.md + .claude/rules/*.md @import) 점검 결과 세 문제 수정 (③ 합의)
---

## 범위

**포함**

- `.claude/rules/batch.md`·`.claude/rules/http.md`의 `@import`를 Read 안내문으로 교체 (`paths` 유지)
- `CLAUDE.md ## Module Structure` 펜스 트리를 `AGENTS.md` 포인터 한 줄로 교체, 모듈 트리 갱신 지시 2개 파일(4곳) 정정
- `CLAUDE.md ### 변경 이력`을 `docs/harness-changelog.md`로 이전하고 이번 작업 행 추가
- "한 행을 남긴다" 지시 5곳을 새 파일로 갱신
- `harnessCheck` 계약 서술 2곳(`CLAUDE.md:59-61`, orchestrator SKILL.md 하네스 정합성 절)에 새 단정 반영
- `docs/check-harness.sh`에 변경 이력 파일 존재·표 헤더 단정 추가, 기존 `## 하네스` 단정 문구와 `check_import` 주석 정정
- `docs/test-check-harness.sh`에 케이스 3종 추가 (정상 1 · 위반 2)

**제외** *(scope creep 차단선)*

- `architecture`·`testing`·`naming`·`git` 래퍼를 조건부로 바꾸는 것
- `CLAUDE.md ## Tech Stack` 정리
- 과거 `docs/work/*` 산출물 수정
- 변경 이력 파일을 참조 무결성(축 1) 대상에 넣는 것 (③ 합의로 제외)
- design §9 "범위 밖 발견" 3건의 수정
- `docs/check-documentation.sh` 수정

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| custom gradle task | 있음 — `harnessCheck`(`docs/check-harness.sh`), `harnessCheckTest`(`docs/test-check-harness.sh`), 둘 다 `check`에 배선 | **예.** 축 3 `require_section`에 단정을 더하면 AC1이 T1이 된다. 자가검증 픽스처가 단정의 RED/GREEN을 증명한다 |
| CI job | 있음 — `.github/workflows/quality-gate.yml`이 위 둘과 `bash docs/check-documentation.sh`를 실행 | 회귀 방어선으로만. `check-documentation.sh`는 고정 목록 20개 파일만 보며 `CLAUDE.md`·`.claude/**`·`docs/harness-changelog.md`는 대상이 아니다 |
| architecture test | 있음 — `./gradlew architectureTest` | 아니오. JVM 바이트코드·모듈 의존 대상 |
| Claude Code 컨텍스트 로딩을 관찰하는 결정적 수단 | 없음 (이 저장소에도, 알려진 CLI 출력에도) | 아니오 → AC6·AC7은 LLM 자기보고 관찰(T3) |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `docs/harness-changelog.md`가 없거나 표 헤더 행이 없을 때 `harnessCheck`가 실패하고, 이력 행에 실재하지 않는 경로가 있어도 실패하지 않으며, 실 트리에서 두 게이트가 통과한다 | design §5.3·§5.4 | `요구` | T1 | `기존` `harnessCheck`·`harnessCheckTest` | `./gradlew harnessCheck harnessCheckTest --offline --no-daemon` | exit 0, 출력에 C1~C3 케이스 `ok` 줄 포함. 케이스 수는 보조 명령 `grep -c '^run_case ' docs/test-check-harness.sh` → `27` |
| AC2 | `CLAUDE.md`에 `### 변경 이력` 제목이 없고, `docs/harness-changelog.md`에 기존 3행이 바이트 그대로 있으며 2026-10-20 행이 첫 데이터 행이다 | design §5.1 | `요구` | T2 | 아래 §AC2 명령 | §AC2 | §AC2 기대 출력과 일치 |
| AC3 | 지시 5곳과 `CLAUDE.md` 계약 서술이 `docs/harness-changelog.md`를 가리키고, 하네스 파일에 "`CLAUDE.md` `## 하네스` 변경 이력" 류 문구와 "아래 변경 이력"이 남지 않는다 | design §5.2 | `요구` · `리뷰`(⑥ #6, `CLAUDE.md:59-61`) | T2 | 아래 §AC3 명령 | §AC3 | §AC3 기대 출력과 일치 |
| AC4 | `.claude/rules/batch.md`·`http.md`에 `@`로 시작하는 행이 없고, 원본 `paths` 값과 안내문 제목(`# Batch 규칙 진입 안내` / `# HTTP 규칙 진입 안내`)을 가진다 | design §3 | `요구` | T2 | 아래 §AC4 명령 | §AC4 | §AC4 기대 출력과 일치 |
| AC5 | `CLAUDE.md ## Module Structure` 아래에 트리 행이 없고 `AGENTS.md` 포인터 한 줄이 있으며, tech-docs 2개 파일이 `CLAUDE.md` 모듈 트리를 갱신 대상으로 지시하지 않는다 | design §4 | `요구`(트리 교체) · `추론`(tech-docs 2개 파일, [ASSUMPTION]) | T2 | 아래 §AC5 명령 | §AC5 | §AC5 기대 출력과 일치 |
| AC6 | worktree에서 파일을 건드리지 않은 `claude -p` 세션이 `# Batch Architecture Rules`·`# HTTP/API Rules`·`# Batch 규칙 진입 안내`·`# HTTP 규칙 진입 안내`는 ABSENT, `# Testing Rules`는 PRESENT로 보고한다 | design §3 | `요구` · `리뷰`(⑥ #1, 안내문 두 줄) | T3 | `관찰` 아래 §AC6 명령 3회 | §AC6 | 3회 모두 `BATCH=ABSENT`·`HTTP=ABSENT`·`TESTING=PRESENT`·`BATCH_GUIDE=ABSENT`·`HTTP_GUIDE=ABSENT` |
| AC7 | Read를 허용한 `claude -p` 세션이 `apps/batch` 아래 `.kt`를 읽은 뒤 `Batch 규칙 진입 안내`를, `apps/api` interfaces 아래 `.kt`를 읽은 뒤 `HTTP 규칙 진입 안내`를 인용한다 | design §3 | `요구`(batch) · `추론`(http 대칭 확인) | T3 | `관찰` 아래 §AC7 명령 각 3회 | §AC7 | 각 3회 모두 stdout에 해당 문자열 포함 |
| AC8 | 세션 시작 시 무조건 로드되는 문서의 문자 수가 변경 전(`$BASE`)보다 작다 | design §1 | `요구` | T2 | 아래 §AC8 명령 | §AC8 | `after < before`, 두 수치 기록. `before`는 약 15.7K자로 ③ 실측을 재현 |

**`출처`** — `요구`(사용자 요구 원문) / `상위`(상위 동결 문서의 ID) / `리뷰`(리뷰에서 파생) / `추론`(근거 없음).

**기준 개수**: 8개

**티어 강등 사유** *(T1이 아닌 항목만 — ⑦에서 사람 승인 필요)*

- AC2~AC5 → T2: 일회성 이전·문구 교체의 결과를 확인하는 기준이다. 각각을 영구 게이트 단정으로 만들면
  `check-harness.sh`가 산문 문구를 동결하게 되는데, ③에서 합의한 게이트 변경은 변경 이력 파일의 존재·헤더
  단정(AC1) 하나뿐이다. 앞으로의 회귀 중 비용이 큰 것(이력 파일 소실·헤더 파손·죽은 경로)은 AC1과 기존 축 1이 막는다.
  단일 `grep -c` 명령과 기대 출력으로 재현 가능하게 적었다.
- AC8 → T2: 변경 전·후 수치의 비교라 단일 명령의 exit code로 표현되지 않는다.
- AC6·AC7 → T3: Claude Code가 세션에 주입한 메모리를 결정적으로 관찰하는 수단이 없다(기존 gate 조사표 4행).
  **판정은 모델의 자기보고에 기대므로 결정적이지 않다** — 오보 가능성이 있다. 완화책으로 각 명령을 3회 돌려 3/3 일치만
  PASS로 하고, 불일치는 재시도로 덮지 않고 기록 후 보고한다. 조건부 로딩은 AC6(안내문 ABSENT)와 AC7(안내문 PRESENT)이
  **함께** 성립할 때만 증명된다. AC7이 일관되게 실패하면 "래퍼가 Read 시점에 발동하지 않는다"는 설계 수준의 발견이므로
  재시도가 아니라 `## 변경 요청`으로 올린다.

## 검증 명령

모든 명령은 worktree 루트에서 `BASE=$(git merge-base HEAD origin/dev)`를 잡은 셸에서 실행한다.

### AC2

```bash
grep -c '^### 변경 이력' CLAUDE.md
grep -cFxf <(git show "$BASE":CLAUDE.md | sed -n '73,75p') docs/harness-changelog.md
grep -c '^| 20' docs/harness-changelog.md
grep -m1 '^| 20' docs/harness-changelog.md
```
기대: `0` / `3` / `4` / `| 2026-10-20 | 세션 자동 로드 문서 정리 —`로 시작하는 한 줄.

### AC3

```bash
grep -c 'docs/harness-changelog.md' CLAUDE.md .claude/skills/orchestrator/SKILL.md .claude/agents/tech-docs.md .claude/skills/tech-docs-sync/SKILL.md
grep -rnE 'CLAUDE\.md.{0,20}## 하네스.{0,10}변경 이력|아래 변경 이력' CLAUDE.md .claude
```
기대: `CLAUDE.md:3` / `.claude/skills/orchestrator/SKILL.md:3` / `.claude/agents/tech-docs.md:1` /
`.claude/skills/tech-docs-sync/SKILL.md:1`. 두 번째 명령은 출력 없음, exit 1.

### AC4

```bash
grep -c '^@' .claude/rules/batch.md .claude/rules/http.md
grep -cE '^# (Batch|HTTP) 규칙 진입 안내$' .claude/rules/batch.md .claude/rules/http.md
grep -A1 '^paths:' .claude/rules/batch.md
grep -A2 '^paths:' .claude/rules/http.md
```
기대: `.claude/rules/batch.md:0` · `.claude/rules/http.md:0` / 각 `:1` /
```
paths:
  - "apps/batch/**/*.kt"
```
```
paths:
  - "http/**"
  - "apps/api/**/interfaces/**/*.kt"
```

### AC5

```bash
grep -cE '^(├──|└──)' CLAUDE.md
grep -c '^## Module Structure' CLAUDE.md
grep -cF '모듈 구조는 `AGENTS.md`의 `## Module Structure`가 정본이다.' CLAUDE.md
grep -cE 'CLAUDE\.md`(의)? 모듈 트리|AGENTS/CLAUDE' .claude/agents/tech-docs.md .claude/skills/tech-docs-sync/SKILL.md
```
기대: `0` / `1` / `1` / 각 파일 `:0`.

### AC6

프롬프트는 stdin으로 넘기고, 도구 목록은 쉼표로 이은 한 인자로 준다.

```bash
PROMPT='도구를 쓰지 마라. 이 사용자 메시지는 제외하고, 세션 시작 시 주입된 컨텍스트(CLAUDE.md 와 규칙 문서)만 보고 답하라. 아래 다섯 제목 줄 각각이 그 컨텍스트에 제목 줄로 그대로 있으면 PRESENT, 없으면 ABSENT 다. 설명 없이 정확히 다섯 줄만 출력하라. 첫째 줄 BATCH=PRESENT 또는 BATCH=ABSENT (대상: # Batch Architecture Rules). 둘째 줄 HTTP=PRESENT 또는 HTTP=ABSENT (대상: # HTTP/API Rules). 셋째 줄 TESTING=PRESENT 또는 TESTING=ABSENT (대상: # Testing Rules). 넷째 줄 BATCH_GUIDE=PRESENT 또는 BATCH_GUIDE=ABSENT (대상: # Batch 규칙 진입 안내). 다섯째 줄 HTTP_GUIDE=PRESENT 또는 HTTP_GUIDE=ABSENT (대상: # HTTP 규칙 진입 안내).'
printf '%s' "$PROMPT" | claude -p --model haiku --max-turns 1 --disallowedTools "Read,Bash,Grep,Glob,Edit,Write,Task"
```
기대(3회 모두): `BATCH=ABSENT` / `HTTP=ABSENT` / `TESTING=PRESENT` / `BATCH_GUIDE=ABSENT` / `HTTP_GUIDE=ABSENT`.
RED(T4 착수 전 1회): `BATCH=PRESENT` / `HTTP=PRESENT` / `TESTING=PRESENT` / `BATCH_GUIDE=ABSENT` / `HTTP_GUIDE=ABSENT`
(안내문은 착수 전에는 존재하지 않으므로 ABSENT가 맞다 — 이 두 줄은 RED가 아니라 기준선이다).
기대와 다르면 plan T6 Step 4(베이스 트리 밖 임시 worktree에서 AC6·AC7 재측정)를 먼저 수행한다.

### AC7

판정 문자열이 프롬프트에 들어가지 않도록 "새로 들어온 안내문의 제목을 인용하라"고만 묻는다. Read만 허용한다.

```bash
PROMPT='apps/batch/src/main/kotlin/io/premiumspread/PremiumSpreadBatchApplication.kt 파일을 Read 도구로 한 번 읽어라. 파일 내용은 요약하지 마라. 읽은 뒤 컨텍스트에 새로 들어온 규칙 안내문(system-reminder 등으로 붙은 것)이 있으면 그 안내문의 첫 번째 마크다운 제목 줄을 그대로 인용하라. 없으면 NONE 한 단어만 출력하라.'
printf '%s' "$PROMPT" | claude -p --model haiku --max-turns 3 --allowedTools "Read" --disallowedTools "Bash,Grep,Glob,Edit,Write,Task"
```
```bash
PROMPT='apps/api/src/main/kotlin/io/premiumspread/interfaces/api/premium/PremiumController.kt 파일을 Read 도구로 한 번 읽어라. 파일 내용은 요약하지 마라. 읽은 뒤 컨텍스트에 새로 들어온 규칙 안내문(system-reminder 등으로 붙은 것)이 있으면 그 안내문의 첫 번째 마크다운 제목 줄을 그대로 인용하라. 없으면 NONE 한 단어만 출력하라.'
printf '%s' "$PROMPT" | claude -p --model haiku --max-turns 3 --allowedTools "Read" --disallowedTools "Bash,Grep,Glob,Edit,Write,Task"
```
기대(각 3회 모두): 첫 명령 stdout에 `Batch 규칙 진입 안내`, 둘째 명령 stdout에 `HTTP 규칙 진입 안내`.
기대와 다르면 AC6와 같이 plan T6 Step 4를 먼저 수행한다.

### AC8

```bash
for f in CLAUDE.md .ai/rules/architecture.md .ai/rules/batch.md .ai/rules/http.md .ai/rules/testing.md .ai/rules/naming.md .ai/rules/git.md; do git show "$BASE:$f"; done | LC_ALL=C.UTF-8 wc -m
cat CLAUDE.md .ai/rules/architecture.md .ai/rules/testing.md .ai/rules/naming.md .ai/rules/git.md | LC_ALL=C.UTF-8 wc -m
```
첫 줄이 `before`(변경 전 무조건 로드: `CLAUDE.md` + 래퍼 6종이 펼친 `.ai/rules` 6종), 둘째 줄이 `after`(변경 후: `CLAUDE.md` +
무조건 래퍼 4종이 펼친 `.ai/rules` 4종. batch·http 래퍼는 경로 접촉 시에만 실린다). `.claude/rules/*.md`의 import 행 자체는
펼쳐진 내용으로 대체되므로 합산하지 않는다. 사용자 레벨 메모리와, worktree 세션에서 상위 경로로 함께 읽힐 수 있는 베이스 트리
메모리 파일은 이 저장소의 머지 후 상태와 무관하므로 제외한다. `LC_ALL=C.UTF-8`은 `wc -m`이 바이트가 아니라 문자를 세게
고정한다. [ASSUMPTION] `after`는 약 10K자로 예상(미측정).

## 검사 산출물

| 스크립트 | 경로 | 정상 표본 | 위반 표본 | 증명 |
|---|---|---|---|---|
| 하네스 검사기 (기존, 단정 추가) | `docs/check-harness.sh` | C1 `변경 이력의 과거 경로 무시` + 기존 정상 5종 | C2 `필수 파일 부재(변경 이력)`, C3 `필수 섹션 누락(변경 이력 헤더)` + 기존 위반 19종 | `docs/test-check-harness.sh` — plan T2 Step 2 RED, Step 4 GREEN |

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 정본 문서 무결성 | `bash docs/check-documentation.sh` → `documentation check passed (20 files, 15 required paths)` |
| R2 | 무조건 로드 래퍼 4종은 `@import`를 유지 | `grep -c '^@\.\./\.\./\.ai/rules/' .claude/rules/architecture.md .claude/rules/testing.md .claude/rules/naming.md .claude/rules/git.md` → 각 `:1` |
| R3 | 변경 범위가 plan Global Constraints의 9개 파일과 `docs/work/harness-context-load/`를 넘지 않음 | `git diff --name-only "$BASE"...HEAD` |

## 증거 로그

> 구현 중 append only. 실행 위치는 worktree `.worktrees/chore-harness-context-load` (branch `chore/harness-context-load`).

## 사람 확인 (T4)

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| — | 이번 계약에 T4 항목 없음 | — | — | — |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|
| AC6 검증 수단·티어 | T3 · `claude -p` 자기보고 프롬프트 3회, 5줄 모두 기대값 | T2 · `bash docs/work/harness-context-load/probe-rule-load.sh none` 1회 → `none instructions=['testing'] nested_memory=[]` (batch·http가 어느 형태로도 없음). 기존 자기보고 3회 결과는 보조 증거로 남김 | ⑩에서 transcript(`~/.claude/projects/<cwd>/<session>.jsonl`)의 `attachment` 기록이 규칙 로딩을 결정적으로 보여줌을 확인. 자기보고보다 강한 수단이 생겼으므로 같은 관찰을 결정적으로 고정 | 사용자 2026-10-20 (⑩ AskUserQuestion) |
| AC7 검증 수단·티어·통과 조건 | T3 · Read 허용 `claude -p` 자기보고 각 3회, 3/3 인용 | T2 · `probe-rule-load.sh batch` → `nested_memory`에 `batch` 포함 · `probe-rule-load.sh http` → `nested_memory`에 `http` 포함, 각 1회 | ⑩ 실측에서 자기보고는 batch 3/3·http 1/3(기본), batch 2/3·http 2/3(`/tmp`)로 비결정적이었고, 미인용 회차도 "Read 결과에 안내문이 붙어 있었다"고 보고 — 로딩이 아니라 모델의 인용 판단이 흔들림. transcript attachment는 Read 시점의 `nested_memory` 주입을 직접 기록 | 사용자 2026-10-20 (⑩ AskUserQuestion) |

## 최종 판정

> ⑩에서 기록한다.
