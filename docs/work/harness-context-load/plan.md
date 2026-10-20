# 세션 자동 로드 문서 정리 Implementation Plan

> **For agentic workers:** 태스크 단위로 실행한다. 단계는 체크박스(`- [ ]`)로 추적한다.

**Goal:** path-scoped 규칙 래퍼가 세션 시작 시 펼쳐지지 않게 하고, `CLAUDE.md`의 낡은 모듈 트리와 변경 이력을
밖으로 옮겨 매 세션 로드량을 줄인다. 변경 이력 파일의 존재와 헤더는 `harnessCheck`가 단정한다.

**Spec:** `docs/work/harness-context-load/design.md` · **DoD:** `docs/work/harness-context-load/dod.md`

**밀도:** 오케스트레이터 ⑤ 표를 따른다. 결합점(셸 호출 형태·정규식·실패 메시지·픽스처 케이스 이름과 기대
문자열·산출 파일의 고정 문구·다른 문서가 인용하는 문구의 교체 전/후)은 전문, 구현부·테스트 본문·주석은 요건까지.

## Global Constraints

- 모든 작업은 worktree `/home/yeop/IdeaProjects/premium-spread/.worktrees/chore-harness-context-load`에서 한다.
- 고치는 파일은 아래 9개뿐이다(+ 이번 slug의 `docs/work/harness-context-load/`). 애플리케이션 코드·Gradle 스크립트·
  `.ai/rules/*`·`AGENTS.md`·다른 `docs/work/*`는 건드리지 않는다.
  `CLAUDE.md`, `docs/harness-changelog.md`(신규), `docs/check-harness.sh`, `docs/test-check-harness.sh`,
  `.claude/rules/batch.md`, `.claude/rules/http.md`, `.claude/skills/orchestrator/SKILL.md`,
  `.claude/agents/tech-docs.md`, `.claude/skills/tech-docs-sync/SKILL.md`
- 하네스 파일 본문의 백틱 경로는 실재해야 한다(`harnessCheck` 축 1). 래퍼 안내문의 어떤 행도 `@`로 시작하지 않는다.
- 커밋마다 `./gradlew harnessCheck harnessCheckTest --offline --no-daemon`이 초록이어야 한다. 그래서 T1(파일 생성)이
  T2(단정 추가)보다 앞선다 — design §6.
- 커밋 메시지는 `<type>: <subject>` + 한글 bullet 본문. `Co-Authored-By` 넣지 않는다.
- 분기점: 각 태스크 셸에서 `BASE=$(git merge-base HEAD origin/dev)`로 잡는다. [ASSUMPTION] 값은 `df15e79`.
  다르면 dod 증거 로그에 실제 값을 적는다.
- 아래 "교체 전" 문구는 2026-10-20 worktree에서 다시 열어 확인한 원문이다. 구현 시 원문이 다르면 멈추고 보고한다
  (⑥이 기존 코드와 대조하는 단언이다).

---

### Task 1: 변경 이력을 `docs/harness-changelog.md`로 이전

**Files:**
- Create: `docs/harness-changelog.md`
- Modify: `CLAUDE.md` (57행 교체, 하네스 표에 행 추가, 69~75행 삭제)

**Interfaces:**
- Produces: 경로 `docs/harness-changelog.md`와 표 헤더 `| 날짜 | 변경 내용 | 대상 | 사유 |` — T2의 단정과 T3·T4의 참조가 이것을 가리킨다.

- [ ] **Step 1: RED 기록 (dod AC2)**

```bash
grep -c '^### 변경 이력' CLAUDE.md
```
Expected: `1`. dod 증거 로그 AC2에 RED로 남긴다.

- [ ] **Step 2: `docs/harness-changelog.md` 생성**

파일 전문은 아래와 같다. 마지막 `{기존 3행}`은 placeholder가 아니라 **`git show "$BASE":CLAUDE.md`의 73~75행을
바이트 그대로 붙이는 지시**다(순서 유지). 손으로 다시 타이핑하지 않는다.

```markdown
# 하네스 변경 이력

하네스 파일(`CLAUDE.md`·`.claude/**`)과 하네스 게이트(`docs/check-harness.sh`·`docs/test-check-harness.sh`)를
바꾼 작업마다 한 행을 남긴다. 최신 행이 위다.

이 파일은 `harnessCheck`의 참조 무결성 검사 대상이 아니다. 과거 시점의 경로를 그대로 담는 기록이라, 경로를
rename한 뒤 게이트가 소급 실패하면 안 된다. 게이트는 이 파일의 존재와 아래 표 헤더만 단정한다.

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-10-20 | 세션 자동 로드 문서 정리 — path-scoped 규칙 래퍼(`.claude/rules/batch.md`·`.claude/rules/http.md`)의 `@import` 를 Read 안내문으로 교체, `CLAUDE.md` 모듈 트리를 `AGENTS.md` 포인터로 교체, 변경 이력을 `CLAUDE.md` 에서 이 파일로 분리하고 `harnessCheck` 에 이 파일의 존재·표 헤더 단정 추가 | `.claude/rules/batch.md`, `.claude/rules/http.md`, `CLAUDE.md`, `docs/harness-changelog.md`, `docs/check-harness.sh`, `docs/test-check-harness.sh`, `.claude/skills/orchestrator/SKILL.md`, `.claude/agents/tech-docs.md`, `.claude/skills/tech-docs-sync/SKILL.md` | path-scoped 래퍼 안의 `@import` 는 `paths` 조건과 무관하게 세션 시작 시 펼쳐진다 — `claude -p --model haiku` 실측에서 `batch.md` 의 import 를 지우자 Batch 본문만 사라지고 HTTP 본문은 남았다. 조건부로 둔 두 규칙 본문이 매 세션 실리고 있었다. 모듈 트리는 `domain`·`infrastructure`·`architecture-tests`·`apps/web` 이 빠진 채 `AGENTS.md` 와 갈라져 있었고, 하네스를 바꿀 때만 읽는 변경 이력이 매 세션 1,573자를 차지했다. 이력 파일은 과거 경로를 그대로 담는 기록이라 참조 무결성 검사 대상에서 뺐다. 로드량 전후 수치는 `docs/work/harness-context-load/dod.md` 증거 로그. 자가검증 픽스처 27종(정상 6 · 위반 21) |
{기존 3행}
```

- [ ] **Step 3: `CLAUDE.md` 수정**

(a) 57행 교체 — 지시 5곳 중 1번.

교체 전:
```
| 정합성 검사 | `./gradlew harnessCheck` — 하네스 파일을 고쳤으면 커밋 전에 직접 한 번 돌리고 아래 변경 이력에 한 행을 남긴다 |
```
교체 후:
```
| 정합성 검사 | `./gradlew harnessCheck` — 하네스 파일을 고쳤으면 커밋 전에 직접 한 번 돌리고 `docs/harness-changelog.md`에 한 행을 남긴다 |
```

(b) 바로 아래에 행 추가 (하네스 표의 마지막 행이 된다):
```
| 변경 이력 | `docs/harness-changelog.md` — 하네스를 바꾼 작업마다 `날짜 · 변경 내용 · 대상 · 사유` 한 행. 매 세션 로드되는 이 파일에는 두지 않는다 |
```

(c) `### 변경 이력` 제목부터 표 마지막 행까지(현재 69~75행)를 지운다. `check-documentation.sh` 설명 문단과
`## 관련 문서` 사이에는 빈 줄 하나만 남긴다. `## 하네스` 제목과 그 아래 두 설명 문단은 그대로 둔다.

- [ ] **Step 4: 검증**

```bash
grep -c '^### 변경 이력' CLAUDE.md
grep -cFxf <(git show "$BASE":CLAUDE.md | sed -n '73,75p') docs/harness-changelog.md
grep -c '^| 20' docs/harness-changelog.md
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
```
Expected: `0` / `3` / `4` / `BUILD SUCCESSFUL` (이 시점 `harnessCheckTest`는 기존 24종).

- [ ] **Step 5: 커밋**

```bash
git add docs/harness-changelog.md CLAUDE.md
git commit -m "chore: 하네스 변경 이력을 docs/harness-changelog.md 로 분리" \
  -m "- CLAUDE.md 의 변경 이력 표 3행을 그대로 옮기고 이번 작업 행을 추가
- 하네스 표에 변경 이력 포인터 행을 두고 정합성 검사 행의 기록 위치를 새 파일로 변경
- 매 세션 로드되는 CLAUDE.md 에서 1,573자를 덜어낸다"
```

---

### Task 2: `harnessCheck`에 변경 이력 단정 추가 (TDD)

**Files:**
- Modify: `docs/test-check-harness.sh`
- Modify: `docs/check-harness.sh`

**Interfaces:**
- Consumes: T1의 파일 경로와 표 헤더
- Produces: 아래 실패 메시지 두 종. T3이 orchestrator 하네스 정합성 절의 단정 목록에 이 단정을 올린다.

- [ ] **Step 1: 자가검증 픽스처 먼저 추가**

`make_ok` 요건: `"$r/docs"`는 이미 생긴다(`docs/runbooks`를 만들므로). `"$r/docs/harness-changelog.md"`를 아래 내용으로 만든다.
```
# 하네스 변경 이력

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-01-01 | 픽스처 | `docs/runbooks/redis-contract.md` | 테스트 |
```

케이스 3개. 이름·기대 exit·기대 문자열은 전문, 변형 함수 본문은 요건이다.

**C1 — 정상 표본.** `# ── 정상 표본` 절 끝(`m_external` 케이스 뒤).
- 변형 함수 `m_changelog_old_path` — `docs/harness-changelog.md` 끝에 실재하지 않는 소유 경로(`apps/`)를 담은 행을 한 줄 덧붙인다. 덧붙일 행:
  ```
  | 2026-01-02 | rename 전 기록 | `apps/renamed-away/build.gradle.kts` | 과거 경로 |
  ```
- 주석 요건: 이 표본은 "이력 파일은 `check_refs` 대상이 아니다"라는 결정을 지킨다. 누가 이 파일을 축 1에 넣으면 이 케이스가 실패한다.
- 호출: `run_case "변경 이력의 과거 경로 무시" 0 m_changelog_old_path`
- `expect_output` 없음.

**C2 — 위반 표본.** `# ── 위반 표본: 축 3` 절, `m_no_harness_section` 케이스 바로 뒤.
- 변형 함수 `m_no_changelog` — `rm -f "$1/docs/harness-changelog.md"`
- 호출과 기대 문자열:
  ```bash
  run_case "필수 파일 부재(변경 이력)" 1 m_no_changelog
  expect_output "필수 파일 부재(변경 이력)" "docs/harness-changelog.md: 필수 파일 부재"
  ```

**C3 — 위반 표본.** C2 바로 뒤.
- 변형 함수 `m_no_changelog_header` — 헤더 행 하나만 지운다: `sed -i '/^| 날짜 | 변경 내용 | 대상 | 사유 |$/d' "$1/docs/harness-changelog.md"` (BRE에서 `|`는 리터럴).
- 호출과 기대 문자열 (기대 문자열은 **작은따옴표** — 백슬래시를 그대로 넘겨야 `grep -F`가 검사기 출력 원문과 맞는다):
  ```bash
  run_case "필수 섹션 누락(변경 이력 헤더)" 1 m_no_changelog_header
  expect_output "필수 섹션 누락(변경 이력 헤더)" '^\| 날짜 \| 변경 내용 \| 대상 \| 사유 \|'
  ```

- [ ] **Step 2: RED 확인**

```bash
bash docs/test-check-harness.sh
```
Expected: exit 1. 아래 4줄이 FAIL로 나온다 — 기대한 이유(검사기가 아직 이 파일을 보지 않는다)다.
```
FAIL [필수 파일 부재(변경 이력)] exit 0, 기대 1
FAIL [필수 파일 부재(변경 이력)] 기대 문자열 없음: docs/harness-changelog.md: 필수 파일 부재
FAIL [필수 섹션 누락(변경 이력 헤더)] exit 0, 기대 1
FAIL [필수 섹션 누락(변경 이력 헤더)] 기대 문자열 없음: ^\| 날짜 \| 변경 내용 \| 대상 \| 사유 \|
```
`ok [변경 이력의 과거 경로 무시] exit 0`은 처음부터 초록이다 — 회귀 방어용 정상 표본이라 RED가 없는 것이 맞다.
기존 24종은 전부 `ok`. 출력 전체를 dod 증거 로그 AC1에 RED로 붙인다.

- [ ] **Step 3: `docs/check-harness.sh` 수정**

(a) 상단 주석(14~15행, check-documentation.sh와의 대상 구분 문단) 뒤에 의도 한두 줄 추가. 요건: 이력 파일은 축 3에서
존재와 표 헤더만 단정하고 축 1 대상에 넣지 않는다, 과거 경로를 담는 기록이라 rename 뒤 소급 실패하면 안 된다.

(b) 326~328행 교체 — 주석과 제거 조건 문구만 바뀐다. 정규식 `'^## 하네스'`는 그대로.

교체 전:
```bash
# 하네스 진입점. 사라지면 트리거 규칙과 변경 이력이 함께 사라진다.
require_section CLAUDE.md '^## 하네스' \
  '하네스 진입점을 다른 곳으로 옮기려면 트리거 규칙과 변경 이력을 이전한 뒤 이 require_section 호출을 같은 커밋에서 지운다'
```
교체 후:
```bash
# 하네스 진입점. 사라지면 트리거 규칙과 변경 이력 포인터가 함께 사라진다.
require_section CLAUDE.md '^## 하네스' \
  '하네스 진입점을 다른 곳으로 옮기려면 트리거 규칙과 변경 이력 포인터(docs/harness-changelog.md)를 이전한 뒤 이 require_section 호출을 같은 커밋에서 지운다'
```

(c) 바로 아래에 새 단정 추가. 호출 형태 전문(주석은 요건 수준이지만 아래 문안을 그대로 써도 된다):
```bash
# 하네스 변경 이력. 사라지면 "한 행을 남긴다" 는 지시 다섯 곳이 가리킬 자리가 없어진다.
# 축 1 대상이 아니다 — 과거 경로를 그대로 담는 기록이라 rename 뒤 소급 실패하면 안 된다.
require_section docs/harness-changelog.md '^\| 날짜 \| 변경 내용 \| 대상 \| 사유 \|' \
  '변경 이력을 다른 곳으로 옮기려면 CLAUDE.md 하네스 표의 포인터와 이 파일을 가리키는 하네스 참조(orchestrator ⑪-d·하네스 정합성, tech-docs, tech-docs-sync)를 함께 옮긴 뒤 이 require_section 호출을 같은 커밋에서 지운다'
```
결과 실패 메시지 전문 (형식은 기존 `require_section`이 만든다):
- 파일 부재: `docs/harness-changelog.md: 필수 파일 부재`
- 헤더 누락: `docs/harness-changelog.md:1: 필수 섹션 누락 — ^\| 날짜 \| 변경 내용 \| 대상 \| 사유 \| — 제거 조건: ` 뒤에 (c)의 제거 조건 문자열 전문

`check_refs` 호출 목록(211~231행)은 바꾸지 않는다.

- [ ] **Step 4: GREEN 확인**

```bash
bash docs/test-check-harness.sh
grep -c '^run_case ' docs/test-check-harness.sh
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
```
Expected: `== 모든 케이스 통과 ==` exit 0 / `27` / `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋**

```bash
git add docs/check-harness.sh docs/test-check-harness.sh
git commit -m "chore: harnessCheck 에 변경 이력 파일 단정 추가" \
  -m "- docs/harness-changelog.md 의 존재와 표 헤더를 require_section 으로 단정
- CLAUDE.md 하네스 진입점 단정의 주석·제거 조건에서 이전된 변경 이력 서술을 정정
- 자가검증 케이스 3종 추가(정상 1 · 위반 2), 합계 27종
- 이력 파일은 과거 경로를 담는 기록이라 참조 무결성 대상에서 제외함을 정상 표본으로 고정"
```

---

### Task 3: "한 행을 남긴다" 지시 4곳 갱신 (2~5번)

**Files:**
- Modify: `.claude/skills/orchestrator/SKILL.md` (525행, 538행, 542~543행)
- Modify: `.claude/agents/tech-docs.md` (32행)
- Modify: `.claude/skills/tech-docs-sync/SKILL.md` (23행)

**Interfaces:**
- Consumes: T1의 경로, T2의 단정

- [ ] **Step 1: RED 기록 (dod AC3)**

```bash
grep -rnE 'CLAUDE\.md.{0,20}## 하네스.{0,10}변경 이력|아래 변경 이력' CLAUDE.md .claude
```
Expected: 4건 — `.claude/skills/orchestrator/SKILL.md:525`, `:542`, `.claude/agents/tech-docs.md:32`, `.claude/skills/tech-docs-sync/SKILL.md:23`
(`CLAUDE.md:57`은 T1에서 이미 바뀌었다). 기록한다.

- [ ] **Step 2: 교체**

**2번 — `.claude/skills/orchestrator/SKILL.md:525` (⑪-d)**

교체 전:
```
반영을 수행할 때는 `CLAUDE.md ## 하네스`의 **변경 이력**에 `날짜 · 변경 내용 · 대상 · 사유` 한 행을 남긴다.
```
교체 후:
```
반영을 수행할 때는 `docs/harness-changelog.md`에 `날짜 · 변경 내용 · 대상 · 사유` 한 행을 남긴다.
```

**2-a — 같은 파일 538행 (하네스 정합성 절의 단정 목록)** [ASSUMPTION — design §5.2]

교체 전:
```
절의 존재, ⓪→① 순서, 에이전트의 `## 재호출 시`와 완료 보고 행, `SKILL.md` 없는 스킬 디렉터리)이 남아
```
교체 후 (같은 문단 안에서 줄바꿈 재배치 허용):
```
절의 존재, ⓪→① 순서, 에이전트의 `## 재호출 시`와 완료 보고 행, `SKILL.md` 없는 스킬 디렉터리,
`docs/harness-changelog.md`의 존재와 표 헤더)이 남아
```

**3번 — 같은 파일 542~543행 (하네스 정합성 절)**

교체 전:
```
기계가 판정하지 않는다. 그래서 하네스를 고쳤으면 여전히 `CLAUDE.md ## 하네스` 변경 이력에 한 행을
남긴다 — 무엇을 왜 바꿨는지는 다음 사람이 읽을 유일한 근거다.
```
교체 후:
```
기계가 판정하지 않는다. 그래서 하네스를 고쳤으면 여전히 `docs/harness-changelog.md`에 한 행을
남긴다 — 무엇을 왜 바꿨는지는 다음 사람이 읽을 유일한 근거다. 게이트는 그 파일의 존재와 표 헤더만
보고, 이번 작업의 행이 추가됐는지는 보지 않는다.
```

**4번 — `.claude/agents/tech-docs.md:32`**

교체 전:
```
| 하네스(에이전트·스킬·단계) | `CLAUDE.md`의 `## 하네스` 변경 이력에 한 행 |
```
교체 후:
```
| 하네스(에이전트·스킬·단계) | `docs/harness-changelog.md`에 한 행 |
```

**5번 — `.claude/skills/tech-docs-sync/SKILL.md:23`**

교체 전:
```
| 하네스(에이전트·스킬·단계) 변경 | `CLAUDE.md`의 `## 하네스` 변경 이력에 한 행 |
```
교체 후:
```
| 하네스(에이전트·스킬·단계) 변경 | `docs/harness-changelog.md`에 한 행 |
```

- [ ] **Step 3: 검증**

```bash
grep -c 'docs/harness-changelog.md' CLAUDE.md .claude/skills/orchestrator/SKILL.md .claude/agents/tech-docs.md .claude/skills/tech-docs-sync/SKILL.md
grep -rnE 'CLAUDE\.md.{0,20}## 하네스.{0,10}변경 이력|아래 변경 이력' CLAUDE.md .claude
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
```
Expected:
```
CLAUDE.md:2
.claude/skills/orchestrator/SKILL.md:3
.claude/agents/tech-docs.md:1
.claude/skills/tech-docs-sync/SKILL.md:1
```
두 번째 명령은 출력 없음(exit 1). 세 번째는 `BUILD SUCCESSFUL`.

- [ ] **Step 4: 커밋**

```bash
git add .claude/skills/orchestrator/SKILL.md .claude/agents/tech-docs.md .claude/skills/tech-docs-sync/SKILL.md
git commit -m "chore: 변경 이력 기록 위치 지시를 docs/harness-changelog.md 로 갱신" \
  -m "- orchestrator ⑪-d·하네스 정합성 절, tech-docs 에이전트, tech-docs-sync 스킬의 기록 위치를 새 파일로 변경
- 하네스 정합성 절의 단정 목록에 변경 이력 파일 단정을 추가하고, 게이트가 행 추가 여부는 보지 않음을 명시"
```

---

### Task 4: path-scoped 래퍼의 `@import`를 Read 안내문으로 교체

**Files:**
- Modify: `.claude/rules/batch.md`
- Modify: `.claude/rules/http.md`

**Interfaces:**
- Produces: 판정 문자열 `Batch 규칙 진입 안내`, `HTTP 규칙 진입 안내` — dod AC4·AC7이 쓴다.

- [ ] **Step 1: RED 기록 (dod AC4·AC6)**

```bash
grep -c '^@' .claude/rules/batch.md .claude/rules/http.md
```
Expected: `.claude/rules/batch.md:1` / `.claude/rules/http.md:1`. 이어서 dod AC6 명령을 **1회** 돌려
`BATCH=PRESENT`·`HTTP=PRESENT`가 나오는 것을 RED로 기록한다(③ 실측의 재현).

- [ ] **Step 2: 파일 전문으로 교체**

`.claude/rules/batch.md`:
```markdown
---
paths:
  - "apps/batch/**/*.kt"
---

# Batch 규칙 진입 안내

`apps/batch` 의 Kotlin 소스를 고치기 전에 `.ai/rules/batch.md` 를 Read 도구로 끝까지 읽는다.
규칙의 정본은 그 파일이다 — `AGENTS.md`(Codex)와 공유하므로 여기에 옮겨 적지 않는다.

이 파일에 `@` import 를 넣지 않는다. 래퍼 안의 import 는 `paths` 조건과 무관하게 세션 시작 시 펼쳐져
조건부 로딩을 무력화한다(실측 근거: `docs/harness-changelog.md` 2026-10-20 행).
```

`.claude/rules/http.md`:
```markdown
---
paths:
  - "http/**"
  - "apps/api/**/interfaces/**/*.kt"
---

# HTTP 규칙 진입 안내

`http/` 의 요청 샘플이나 `apps/api` 의 interfaces 계층을 고치기 전에 `.ai/rules/http.md` 를 Read 도구로
끝까지 읽는다. 규칙의 정본은 그 파일이다 — `AGENTS.md`(Codex)와 공유하므로 여기에 옮겨 적지 않는다.

이 파일에 `@` import 를 넣지 않는다. 래퍼 안의 import 는 `paths` 조건과 무관하게 세션 시작 시 펼쳐져
조건부 로딩을 무력화한다(실측 근거: `docs/harness-changelog.md` 2026-10-20 행).
```

`paths` 블록은 원본과 글자까지 같아야 한다. 나머지 네 래퍼(`architecture`·`testing`·`naming`·`git`)는 건드리지 않는다.

- [ ] **Step 3: 검증**

```bash
grep -c '^@' .claude/rules/batch.md .claude/rules/http.md
grep -cE '^# (Batch|HTTP) 규칙 진입 안내$' .claude/rules/batch.md .claude/rules/http.md
git diff "$BASE" -- .claude/rules/batch.md .claude/rules/http.md
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
```
Expected: 각 파일 `:0` / 각 파일 `:1` / diff hunk가 frontmatter(`---`~`---`) 아래에서만 시작 / `BUILD SUCCESSFUL`
(안내문의 백틱 경로 `apps/batch`·`.ai/rules/batch.md`·`http/`·`apps/api`·`.ai/rules/http.md`·`AGENTS.md`·
`docs/harness-changelog.md`가 축 1을 통과한다).

- [ ] **Step 4: 커밋**

```bash
git add .claude/rules/batch.md .claude/rules/http.md
git commit -m "chore: path-scoped 규칙 래퍼의 @import 를 Read 안내문으로 교체" \
  -m "- 래퍼 안의 @import 가 paths 조건과 무관하게 세션 시작 시 펼쳐져 조건부 로딩이 무력화돼 있었다
- batch·http 래퍼는 paths 를 유지하고, 경로 접촉 시 .ai/rules 정본을 Read 하라는 안내문만 싣는다
- 정본은 .ai/rules 에 그대로 두어 AGENTS.md(Codex)와 공유한다"
```

---

### Task 5: `CLAUDE.md` 모듈 트리를 `AGENTS.md` 포인터로 교체

**Files:**
- Modify: `CLAUDE.md` (`## Module Structure` 절 본문)
- Modify: `.claude/agents/tech-docs.md` (3행 description, 22행) — [ASSUMPTION, design §4]
- Modify: `.claude/skills/tech-docs-sync/SKILL.md` (3행 description, 12행) — [ASSUMPTION, design §4]

- [ ] **Step 1: RED 기록 (dod AC5)**

```bash
grep -cE '^(├──|└──)' CLAUDE.md
```
Expected: `7`. 기록한다.

- [ ] **Step 2: `CLAUDE.md` 수정**

`## Module Structure` 제목은 남기고, 그 아래 펜스 블록(여는 펜스부터 닫는 펜스까지, 현재 13~26행)을 아래 한 줄로 바꾼다.
```
모듈 구조는 `AGENTS.md`의 `## Module Structure`가 정본이다. 여기에 옮겨 적지 않는다 — 옮겨 적으면 두 곳이 갈린다.
```

- [ ] **Step 3: 모듈 트리 갱신 지시 정정**

`.claude/agents/tech-docs.md:22`

교체 전:
```
| 모듈 구성·경계 | `.ai/architecture/ARCHITECTURE_DESIGN.md`, `AGENTS.md`·`CLAUDE.md` 모듈 트리 |
```
교체 후:
```
| 모듈 구성·경계 | `.ai/architecture/ARCHITECTURE_DESIGN.md`, `AGENTS.md` 모듈 트리 (`CLAUDE.md`는 포인터만 둔다) |
```

`.claude/skills/tech-docs-sync/SKILL.md:12`

교체 전:
```
| 모듈 구성·경계 변경 | `.ai/architecture/ARCHITECTURE_DESIGN.md`, `AGENTS.md`·`CLAUDE.md`의 모듈 트리 |
```
교체 후:
```
| 모듈 구성·경계 변경 | `.ai/architecture/ARCHITECTURE_DESIGN.md`, `AGENTS.md`의 모듈 트리 (`CLAUDE.md`는 포인터만 둔다) |
```

두 파일의 3행 description에서 `AGENTS/CLAUDE 모듈 트리`를 `AGENTS 모듈 트리`로 바꾼다. description의 나머지 문구는 그대로.

- [ ] **Step 4: 검증**

```bash
grep -cE '^(├──|└──)' CLAUDE.md
grep -cF '모듈 구조는 `AGENTS.md`의 `## Module Structure`가 정본이다.' CLAUDE.md
grep -cE 'CLAUDE\.md`(의)? 모듈 트리|AGENTS/CLAUDE' .claude/agents/tech-docs.md .claude/skills/tech-docs-sync/SKILL.md
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
```
Expected: `0` / `1` / 각 파일 `:0` / `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋**

```bash
git add CLAUDE.md .claude/agents/tech-docs.md .claude/skills/tech-docs-sync/SKILL.md
git commit -m "chore: CLAUDE.md 모듈 트리를 AGENTS.md 포인터로 교체" \
  -m "- domain·infrastructure·architecture-tests·apps/web 이 빠진 채 AGENTS.md 와 갈라진 트리를 제거
- tech-docs 에이전트와 tech-docs-sync 스킬의 갱신 대상에서 CLAUDE.md 모듈 트리를 제외"
```

---

### Task 6: 전체 검증과 증거 기록

**Files:**
- Modify: `docs/work/harness-context-load/dod.md` (증거 로그 append only)

- [ ] **Step 1: 게이트 (dod AC1, R1)**

```bash
./gradlew harnessCheck harnessCheckTest --offline --no-daemon
bash docs/check-documentation.sh
```
Expected: `BUILD SUCCESSFUL` / `documentation check passed (20 files, 15 required paths)`.

- [ ] **Step 2: 문구 단정 재실행 (dod AC2~AC5, R2)**

dod 수용기준 표의 AC2~AC5·R2 검증 명령을 그대로 다시 돌려 최종 커밋 기준 출력을 기록한다.

- [ ] **Step 3: 런타임 로딩 실측 (dod AC6·AC7)**

명령 전문은 dod AC6·AC7 칸이 소유한다. 각 명령을 **3회** 실행한다. 3/3이 통과 조건과 일치해야 PASS다.
한 번이라도 어긋나면 재시도로 덮지 않고 세 출력을 모두 기록한 뒤 오케스트레이터에 보고한다.

주의: 프롬프트는 `-p` 바로 뒤에 둔다. `--disallowedTools`·`--allowedTools`는 가변 인자라 그 뒤에 프롬프트를 두면
도구 이름으로 먹힌다.

- [ ] **Step 4: AC6가 PRESENT로 나올 때의 대체 절차**

worktree가 베이스 저장소 디렉터리 아래에 있어 상위 경로의 메모리 파일이 섞였을 가능성을 먼저 배제한다(design §3).
```bash
git worktree add --detach /tmp/harness-context-load-probe HEAD
```
`/tmp/harness-context-load-probe`에서 AC6 명령을 다시 3회 돌리고 두 결과를 모두 기록한다. 끝나면
`git worktree remove /tmp/harness-context-load-probe`. 판정은 오케스트레이터에 맡긴다(혼자 PASS로 적지 않는다).

- [ ] **Step 5: 로드량 실측 (dod AC8)**

dod AC8 칸의 두 명령을 돌려 변경 전·후 수치를 기록한다.

- [ ] **Step 6: 범위 확인 (dod R3)**

```bash
git diff --name-only "$BASE"...HEAD
```
Expected: Global Constraints의 9개 파일과 `docs/work/harness-context-load/` 아래 파일만.

- [ ] **Step 7: 커밋**

```bash
git add docs/work/harness-context-load/dod.md
git commit -m "docs: harness-context-load DoD 증거 기록" \
  -m "- 게이트·문구 단정·런타임 로딩 실측·로드량 전후 수치를 증거 로그에 추가"
```
