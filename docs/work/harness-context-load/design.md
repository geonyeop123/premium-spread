# 세션 자동 로드 문서 정리 — 설계

- 작업 슬러그: `harness-context-load`
- 브랜치: `chore/harness-context-load` (base `dev`, 분기점은 구현 시 `git merge-base HEAD origin/dev`로 확정 — [ASSUMPTION] `df15e79`)
- 작성일: 2026-10-20
- 성격: 하네스 파일 변경 (문서 + 셸 스크립트). 애플리케이션 코드·Gradle 스크립트는 바꾸지 않는다.

## 1. 배경

매 세션 자동으로 로드되는 문서는 `CLAUDE.md`와 `.claude/rules/*.md`(각각 `.ai/rules/*.md`를 `@import`)다.
점검 결과 세 문제가 있다.

| # | 문제 | 근거 |
|---|------|------|
| P1 | path-scoped 래퍼 `.claude/rules/batch.md`(`paths: apps/batch/**/*.kt`)·`.claude/rules/http.md`(`paths: http/**`, `apps/api/**/interfaces/**/*.kt`)의 조건부 로딩이 무력화돼 있다 | 실측(이 worktree, `claude -p --model haiku`, 도구 금지, 파일 미접촉). 원본 상태 → `# Batch Architecture Rules`·`# HTTP/API Rules` 모두 PRESENT. `batch.md`의 import를 지우고 고유 마커만 남긴 래퍼로 바꾸자 → Batch 본문 ABSENT, 마커 ABSENT(래퍼 자체는 정상 보류), HTTP는 여전히 PRESENT. **결론: path-scoped 래퍼 안의 `@import`는 `paths` 조건과 무관하게 세션 시작 시 즉시 펼쳐진다** |
| P2 | `CLAUDE.md ## Module Structure` 트리가 실제와 다르다 — `domain/`, `infrastructure/{common,api,batch}`, `architecture-tests/`, `apps/web`이 빠져 있다 | `AGENTS.md ## Module Structure`는 `settings.gradle.kts` include와 일치(③에서 대조 완료). `apps/web`은 Gradle 모듈이 아닌 Next.js 디렉터리로 실재 |
| P3 | `CLAUDE.md ### 변경 이력` 표(3행, 1,573자)가 매 세션 로드되지만 작업 중 거의 읽히지 않는다 | 하네스를 바꿀 때만 쓰는 기록이다 |

기본 로드량(`CLAUDE.md` + `.ai/rules/{architecture,batch,http,testing,naming,git}.md`)은 약 15.7K자다.

## 2. 목표와 비목표

**목표**

- G1. `batch`·`http` 규칙 본문이 해당 경로를 건드리기 전까지 컨텍스트에 실리지 않는다.
- G2. `CLAUDE.md`가 모듈 구조를 옮겨 적지 않고 `AGENTS.md`를 가리킨다.
- G3. 변경 이력을 `docs/harness-changelog.md`로 옮기고, 그 파일의 존재와 표 헤더를 `harnessCheck`가 단정한다.

**비목표**

- `architecture.md`·`testing.md`·`naming.md`·`git.md` 래퍼를 조건부로 바꾸는 것
- `CLAUDE.md ## Tech Stack` 정리
- 과거 `docs/work/*` 산출물(동결 기록) 수정
- orchestrator SKILL.md의 다른 낡은 서술 정리 — §9 "범위 밖 발견"에 기록만 한다

## 3. 결정 1 — path-scoped 래퍼를 Read 안내문으로 바꾼다 (P1)

- 두 래퍼에서 `@import` 행을 지우고 "이 경로를 고치기 전에 `.ai/rules/{batch,http}.md`를 Read로 읽는다"는
  안내문으로 바꾼다. `paths` frontmatter는 그대로 둔다. 안내문 전문은 plan T4가 소유한다.
- 정본은 `.ai/rules/`에 그대로 둔다. `AGENTS.md`(Codex)와 공유하는 문서라 래퍼에 내용을 옮겨 적지 않는다.
- 안내문의 고유 제목(`# Batch 규칙 진입 안내` / `# HTTP 규칙 진입 안내`)이 조건부 로딩 실측의 판정 문자열이다.
  이 두 문자열은 다른 자동 로드 문서에 나오지 않는다. 조건부 로딩을 증명하려면 **두 쪽을 모두** 봐야 한다 —
  파일을 건드리지 않은 세션에서는 안내문이 ABSENT(dod AC6), 경로를 읽은 세션에서는 PRESENT(dod AC7).
  AC7만으로는 "무조건 로드"와 구별되지 않는다.
- 안내문의 어떤 행도 `@`로 시작하지 않는다. `@`는 인라인 코드(`` `@` ``)로만 언급한다.
- **죽은 참조 보호는 유지된다.** `check-harness.sh` 축 1은 `.claude/rules/*.md`를 `check_refs`로 훑고,
  import 행이 아닌 행에서는 백틱 토큰을 `check_path`로 검사한다. 안내문의 `` `.ai/rules/batch.md` ``는
  `.ai/` 소유 접두에 걸리므로, 정본이 옮겨가면 지금의 "죽은 import"와 같은 자리에서 "죽은 참조"로 실패한다.
- `check_import` 위 주석(`check-harness.sh:165`, "`.claude/rules/*.md` 는 본문이 이 한 줄뿐이라…")은 이 변경 뒤
  거짓이 된다. `paths` 없는 래퍼에만 해당하도록 고친다(plan T2 Step 3 (d)).
- 나머지 네 래퍼(`architecture`·`testing`·`naming`·`git`)는 `paths`가 없는 무조건 로드이며 `@import`를 유지한다.

**측정상 주의.** 이 worktree는 베이스 저장소 디렉터리 아래(`.worktrees/`)에 있다. Claude Code는 cwd에서
상위로 올라가며 메모리 파일을 읽으므로 worktree 세션에는 베이스 트리의 `CLAUDE.md`도 실릴 수 있다. 직전 실측에서는
베이스의 `.claude/rules/`가 실리지 않았지만(Batch ABSENT), ⑥ 리뷰어 세션에는 베이스 경로의 `.ai/rules`가 실려
있었다. 그래서 AC6에서 ABSENT를 기대한 항목이 PRESENT로 나오거나 AC7이 기대와 다르면, 재시도하기 전에 **두 AC 모두**
베이스 트리 밖 임시 worktree에서 다시 잰다(plan T6 Step 4). 로드량(AC8)은 세션이 아니라 파일 합산으로 잰다.

## 4. 결정 2 — Module Structure를 포인터로 바꾼다 (P2)

- `CLAUDE.md ## Module Structure`의 펜스 트리를 지우고 한 줄 포인터만 남긴다(전문은 plan T5). `## Module Structure`
  제목은 유지한다 — 이 제목으로 찾아오는 사람이 포인터를 만나게 하려는 것이다.
- `tech-docs` 에이전트와 `tech-docs-sync` 스킬의 매핑표·description이 "`AGENTS.md`·`CLAUDE.md` 모듈 트리"를
  갱신 대상으로 지시한다. 트리가 사라지면 없는 대상을 가리키게 되므로 같은 태스크에서 `AGENTS.md`만 남긴다.
  **[ASSUMPTION]** 합의된 변경 2의 직접 귀결로 보고 범위에 넣었다. 범위 밖이라고 판단하면 T5 Step 3을 빼면 된다.

## 5. 결정 3 — 변경 이력을 `docs/harness-changelog.md`로 옮긴다 (P3)

### 5.1 새 파일

- 기존 3행을 **글자 그대로** 옮기고(순서 유지, 최신 행이 위), 이번 작업 한 행(2026-10-20)을 맨 위에 추가한다.
- 표 헤더는 기존과 같다: `| 날짜 | 변경 내용 | 대상 | 사유 |`.
- `CLAUDE.md ## 하네스` 절은 유지한다(harnessCheck 단정 대상). 진입점 표에 `변경 이력` 행을 추가해 새 파일을 가리킨다.

### 5.2 "한 행을 남긴다" 지시 5곳과 게이트 서술

| # | 위치 | 갱신 |
|---|------|------|
| 1 | `CLAUDE.md:57` 하네스 표 `정합성 검사` 행 — "아래 변경 이력에" | 새 파일 경로로 (T1) |
| 2 | `.claude/skills/orchestrator/SKILL.md:525` ⑪-d | 새 파일 경로로 (T3) |
| 3 | `.claude/skills/orchestrator/SKILL.md:542` 하네스 정합성 절 | 새 파일 경로로 (T3) |
| 4 | `.claude/agents/tech-docs.md:32` | 새 파일 경로로 (T3) |
| 5 | `.claude/skills/tech-docs-sync/SKILL.md:23` | 새 파일 경로로 (T3) |

교체 전/후 전문은 plan이 소유한다. 그 밖에 `harnessCheck`가 보는 계약을 나열하는 서술 두 곳에 새 단정을 넣는다.

- `.claude/skills/orchestrator/SKILL.md:537~538` 하네스 정합성 절의 단정 목록 — **[ASSUMPTION]** 새 단정의 직접 귀결 (T3)
- `CLAUDE.md:59~61` `harnessCheck` 계약 서술 문단 — ⑥ 리뷰 반영, 오케스트레이터 결정으로 범위에 넣음 (T3)

### 5.3 게이트 (`docs/check-harness.sh`)

- 축 3에 단정 하나를 추가한다: `docs/harness-changelog.md`가 존재하고 표 헤더 행을 가진다. 기존 `require_section`을
  그대로 쓴다 — 파일이 없으면 `require_section`이 "필수 파일 부재"를, 헤더가 없으면 "필수 섹션 누락"을 보고한다.
  정규식·제거 조건 전문은 plan T2.
- `require_section CLAUDE.md '^## 하네스'`(326~328행)의 주석과 제거 조건이 "변경 이력이 함께 사라진다 / 변경 이력을
  이전한 뒤"라고 적는다. 이제 이력은 `CLAUDE.md`에 없으므로 "변경 이력 포인터"로 고친다.
- **변경 이력 파일은 축 1(참조 무결성) 대상에 넣지 않는다 [합의된 결정].** 과거 시점의 경로를 그대로 담는
  기록이라, 경로를 rename한 뒤 게이트가 소급 실패하면 안 된다. 현재 `check_refs` 대상은 `CLAUDE.md`,
  `.claude/agents/*.md`, `.claude/rules/*.md`, 비벤더 `SKILL.md`뿐이므로 **코드 변경 없이 성립한다.** 이 의도를
  스크립트 상단 주석에 한 줄 남기고, 자가검증에 정상 표본("변경 이력의 과거 경로 무시")을 하나 넣어 누군가
  나중에 이 파일을 `check_refs`에 추가하면 `harnessCheckTest`가 실패하게 한다. **[ASSUMPTION]** 이 정상 표본은
  합의된 결정을 고정하려고 추가한 것이다.

### 5.4 자가검증 (`docs/test-check-harness.sh`)

- `make_ok`가 헤더와 한 행을 가진 `docs/harness-changelog.md`를 만든다.
- 케이스 3개를 추가한다 — 정상 1(과거 경로 무시), 위반 2(파일 부재 / 헤더 누락). 기존 24종은 그대로 통과해야 한다.
- 합계 24 → **27종(정상 6 · 위반 21)**. 이 수치를 새 changelog 행에 적는다.

### 5.5 `docs/check-documentation.sh`와 무관함

이 스크립트는 고정 목록 `authoritative_files`(`AGENTS.md`, `.ai/*` 10개, `docs/runbooks/*` 9개, 계 20개)만 읽고
glob을 쓰지 않는다. `docs/harness-changelog.md`는 그 목록에 없으므로 placeholder·링크·낡은 서술 검사 어디에도
걸리지 않는다. `CLAUDE.md:66-67`의 "두 검사는 대상이 겹치지 않는다"는 서술은 그대로 참이다. 이 스크립트는
고치지 않는다.

## 6. 태스크 순서와 그 이유

T1 변경 이력 이전 → T2 게이트 단정 + 자가검증 → T3 참조 4곳 + 게이트 서술 2곳 → T4 래퍼 교체 → T5 모듈 트리 포인터 → T6 전체 검증.

오케스트레이터 제안(T1 게이트 → T2 이전)과 T1·T2가 바뀌었다. 게이트 단정을 먼저 넣으면 그 커밋의 실 트리에는
`docs/harness-changelog.md`가 없어 `harnessCheck`가 빨갛다 — 커밋마다 `check`가 초록이어야 한다는 규칙
(`CLAUDE.md` Coding Guidelines 4)에 어긋난다. TDD RED는 픽스처 기반인 `harnessCheckTest`에서 그대로 확보된다
(T2 Step 1~2). 이 단정은 실 트리와 무관하게 임시 디렉터리에서 검증된다.

## 7. 테스트 전략

| 계층 | 대상 | 수단 |
|------|------|------|
| 게이트 자가검증 | 새 단정이 위반을 검출하고 과거 경로를 무시하는가 | `harnessCheckTest` — 새 케이스 3개, RED → GREEN |
| 실 트리 정합 | 새 파일·참조·래퍼의 백틱 경로가 실재하고 단정이 통과하는가 | `harnessCheck` |
| 정본 문서 회귀 | 변경이 정본 문서 검사에 영향 없음 | `bash docs/check-documentation.sh` |
| 문구 단정 | 이력 제거·트리 제거·참조 교체·래퍼에 import 없음 | 단일 `grep -c` 명령과 기대 출력 (dod T2) |
| 런타임 로딩 | 무조건 로드에서 batch/http 본문과 안내문이 모두 빠지고, 경로 접촉 시 안내문이 실리는가 | `claude -p --model haiku` 실측 3회 (dod T3, 비결정적) |
| 로드량 | 변경 전/후 문자 수 | `LC_ALL=C.UTF-8 wc -m` 합산 (dod T2) |

## 8. 리스크

| 항목 | 내용 | 처리 |
|------|------|------|
| 안내문을 읽지 않고 고침 | 조건부 로드되는 것은 안내문뿐이라 에이전트가 Read를 건너뛰면 규칙 없이 batch/http를 고칠 수 있다 | 안내문 제목·첫 문장을 명령형으로 둔다. `implementer`·`spec-reviewer`는 이미 `.ai/rules/*`를 참조 대상으로 지시받는다. 효과가 부족하면 ⑪-d 피드백으로 되돌린다 |
| LLM 자기보고의 비결정성 | AC6·AC7은 모델이 자기 컨텍스트를 보고하는 것이라 오보 가능성이 있다 | 각 3회 실행, 3/3 일치일 때만 PASS. 불일치는 재시도로 덮지 않고 기록 후 보고 |
| 상위 경로 메모리 혼입 | worktree가 베이스 트리 아래라 베이스의 `CLAUDE.md`·규칙이 측정 세션에 섞일 수 있다(⑥ 리뷰어 세션에서 관찰) | §3 "측정상 주의" — 이상 시 두 AC 모두 `/tmp` worktree에서 재측정 |
| 변경 이력 행 추가 누락 | 게이트는 헤더만 보고 행 추가 여부는 보지 않는다 | 기존과 같은 수준(이전에도 판정하지 않았다). orchestrator 하네스 정합성 절에 그 한계를 명시(T3) |
| 이력 파일을 나중에 `check_refs`에 넣음 | 과거 경로로 게이트가 소급 실패한다 | 정상 표본 "변경 이력의 과거 경로 무시"가 그 변경을 빨갛게 만든다 |

## 9. 범위 밖 발견 (기록만, 이번에 고치지 않는다)

| 위치 | 내용 |
|------|------|
| `.claude/skills/orchestrator/SKILL.md:319` | "이 저장소에는 하네스 자동 게이트가 없으므로" — 2026-09-16 `harnessCheck` 신설 이후 낡았다. 다만 "밀도 표 전달 누락은 게이트가 잡지 않는다"는 결론 자체는 여전히 참이라 문장 수정이 필요하다 |
| `.claude/skills/tech-docs-sync/SKILL.md:44-45` | "하네스 문서가 가리키는 경로가 실재하는지는 사람이 확인해야 한다" — `harnessCheck` 축 1이 이미 검사한다 |
| `CLAUDE.md:85` 관련 문서 표 | `docs/work/{slug}/`를 "`feature-workflow` 산출물"로 적는다. 현재 진입 스킬은 `orchestrator`다 |

## 10. 스펙 리뷰(⑥) 반영

1라운드 6건 전부 ACCEPT.

| # | 등급 | 지적 | 반영 |
|---|------|------|------|
| 1 | major | AC6가 안내문 부재를 확인하지 않아 AC7만으로는 조건부 로딩이 증명되지 않는다 | AC6에 `BATCH_GUIDE`·`HTTP_GUIDE` 두 줄 추가, 기대 3회 모두 ABSENT(RED도 ABSENT). §3에 근거 명시 |
| 2 | minor | AC1의 "27종"을 출력에서 관찰할 수 없다 | AC1 통과 조건에 `grep -c '^run_case '` → `27` 추가 |
| 3 | minor | `claude -p` 인자 순서가 깨지기 쉽다 | 프롬프트는 stdin, 도구 목록은 쉼표로 이은 한 인자 |
| 4 | minor | `wc -m`이 로케일에 따라 바이트를 센다 | `LC_ALL=C.UTF-8` 고정 |
| 5 | minor | `check-harness.sh:165` 주석이 T4 뒤 거짓이 된다 | plan T2 Step 3 (d)에 정정 전문 |
| 6 | minor | `CLAUDE.md:59-61` 계약 서술에 새 단정이 빠진다, dod 범위 문구 부정확 | plan T3에 교체 전/후 전문(오케스트레이터 결정으로 범위 포함), dod 범위 "2개 파일(4곳)" |
| 관찰 | — | 리뷰어 세션에 베이스 경로 `.ai/rules`가 실려 있었다 | §3·§8, plan T6 Step 4를 AC6·AC7 공통으로 |

## 11. 구현 중 바뀐 사항

- **줄바꿈.** `CLAUDE.md`·`docs/harness-changelog.md`는 원래 CRLF라 그대로 둔다. `.claude/rules/batch.md`·`http.md`는
  LF다 — CRLF면 dod AC4의 행 끝 앵커(`^@`류 끝 앵커 검사)가 GNU grep에서 CR 때문에 실패한다.
- **AC6·AC7 검증 수단 개정(사용자 승인).** LLM 자기보고(프롬프트에 답하게 하는 방식)를 세션 transcript의
  attachment 판정으로 바꿨다. 판정기는 `docs/work/harness-context-load/probe-rule-load.sh`이며 import로 펼쳐진 정본
  경로도 로드로 본다. 명령과 증거는 dod가 소유한다.
- **⑨ 리뷰 보류 minor 2건(고치지 않음, plan이 지정한 정규식 유지).** 헤더 정규식에 끝 앵커가 없다.
  앞뒤에 공백을 덧댄 패딩 헤더는 허용하지 않는다.
