---
feature: 하네스 aic-api 정합 재구성
slug: harness-aic-api-alignment
status: FROZEN
frozen_at: 2026-08-31
verdict_commit: 8ce7009
source: 2026-08-30 대화 — "aic-server(=aic-api)와 동일하게 하네스 구조 맞춰줘 feature-workflow"
---

## 범위

**포함**

- `.claude/agents/` 를 aic-api와 동일한 역할 6축(architect·implementer·spec-reviewer·code-reviewer·qa-agent·tech-docs)으로 교체
- `.claude/skills/` 를 11종으로 교체 — 진입 스킬 `orchestrator` + 패턴 스킬 8 + 벤더링 2
- 각 파일 내용을 premium-spread의 실제 계약(Kotlin·MySQL/Redis·포트/어댑터·MarketPair·`infrastructure:common` Flyway)으로 재작성
- `CLAUDE.md` 에 `## 하네스` 포인터와 변경 이력 등록
- 구 하네스 자산(에이전트 8·스킬 9) 삭제

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- `scripts/harness-check.sh` 자기검증 게이트 이식 (③에서 "게이트 생략" 결정)
- 애플리케이션 코드·테스트·Gradle 빌드 스크립트 변경
- `.ai/rules/*` · `.ai/architecture/*` 규칙 문서 수정
- ControllerDocs/Swagger 어노테이션의 코드 도입
- `.ai/skills/tdd-workflow/SKILL.MD` 고아 자산 정리

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest`(`:architecture-tests`) | 아니오. 대상이 JVM 바이트코드·모듈 의존성이라 `.claude/` 마크다운을 보지 않는다 |
| lint 규칙 | 없음 — 이 저장소에 `lint` 태스크가 없다 | — |
| custom gradle task | 있음 — `unitTest`·`verifyTestIsolationPolicy`·`verifyCoverageExclusions`·`verifySecurityDependencyVersions` | 아니오. 전부 코드/설정 대상이며 하네스 문서를 검사하지 않는다 |
| CI job | 있음 — `.github/workflows/quality-gate.yml:59`가 `bash docs/check-documentation.sh` 실행 | **부분적으로 가능.** 정본 문서(AGENTS.md·`.ai/*`·`docs/runbooks/*`)의 깨진 링크·placeholder·낡은 서술은 잡지만 `.claude/` 는 대상이 아니다 |

> 결론: 하네스 파일 자체를 검사하는 기존 gate는 없다. 사용자가 신규 게이트 도입을 제외했으므로
> 구조 축 수용기준은 T3(1회 관찰 기록)으로 내려간다. 이 강등은 §"티어 강등 사유"에 명시한다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `.claude/agents/` 에 정확히 6개 파일(architect·implementer·spec-reviewer·code-reviewer·qa-agent·tech-docs)이 있고, 각 frontmatter가 `name`·`description`·`tools`·`model` 4키를 가지며 `name`이 파일명과 일치한다 | design §4 | `요구` | T3 | `관찰` frontmatter 덤프 기록 | — | 6/6 일치, 누락 0 |
| AC2 | `.claude/skills/` 에 11개 스킬 디렉터리가 있고 각 `SKILL.md` frontmatter `name`이 디렉터리명과 일치한다 | design §4 | `요구` | T3 | `관찰` frontmatter 덤프 기록 | — | 11/11 일치 |
| AC3 | **새로 쓴 하네스 문서 전체**(`.claude/agents/*.md` 6개 + 자체 작성 스킬 9종 `SKILL.md` + `CLAUDE.md`) 본문이 언급하는 저장소 경로가 전부 실재한다. 벤더링 사본 2종과 `_workspace/`처럼 부재가 문서에 명시된 경로는 제외 | design §1 #1·#2 재발 방지 | `리뷰` | T3 | `관찰` 대상 파일 전체에서 경로 토큰 추출 후 `test -e` 결과 기록 (plan Task 8 Step 2의 루프) | — | 죽은 참조 0건 |
| AC4 | 벤더링한 `definition-of-done` 이 원본과 byte-identical | design §3 | `요구` | T1 | `기존` `diff -r` | `diff -r /home/yeop/.claude/skills/definition-of-done .claude/skills/definition-of-done` | exit 0 |
| AC5 | 벤더링한 `explain-pr` 이 원본과 byte-identical | design §3 | `요구` | T1 | `기존` `diff -r` | `diff -r /home/yeop/.claude/skills/explain-pr .claude/skills/explain-pr` | exit 0 |
| AC6 | `CLAUDE.md` 에 `## 하네스` 섹션이 있고 **그 뒤에** `### 변경 이력` 표가 온다 | design §4 | `요구` | T1 | `기존` `grep -P` | `grep -Pzoq '## 하네스[\s\S]*?### 변경 이력' CLAUDE.md` | exit 0 |
| AC7 | 정본 문서 무결성 검사가 통과한다 (깨진 링크·placeholder·낡은 아키텍처 서술 0건) | 기존 CI gate | `요구` | T1 | `기존` `docs/check-documentation.sh` | `bash docs/check-documentation.sh` | exit 0 |
| AC8 | 구 하네스 자산(에이전트 8·스킬 9)이 제거됐고, 변경 범위가 `.claude/` · `CLAUDE.md` · `docs/work/` 밖으로 나가지 않는다 | design §2 비목표·§6 | `요구` | T3 | `관찰` `git diff --stat origin/dev...HEAD` 기록 | — | 구 자산 0개 잔존, 범위 밖 파일 0개 |

> **AC4·AC5의 한계 (⑥ 리뷰 반영).** 이 두 명령은 개인 홈(`/home/yeop/.claude/skills/`)을 기준으로 비교하므로
> CI나 다른 작업자 머신에서는 재현되지 않는다. 이번 MR에서는 복사 충실도 증거로 충분하다고 보되, 나중에
> 감사할 수 있도록 사본 파일의 `sha256sum`을 증거 로그에 함께 남긴다. 저장소 내부 manifest 도입은
> 게이트 생략 결정과 묶어 후속으로 넘긴다.
>
> **AC8의 baseline은 `origin/dev`다 (⑥ 리뷰 반영).** 이 worktree는 `origin/dev@4560124`에서 분기했고 로컬
> `dev`는 `b877d42`로 100 커밋 stale이다. `dev...HEAD`로 재면 남의 작업 120개 파일이 포함돼 판정이 항상 실패한다.

**`출처`** — `요구`(사용자 요구 원문) / `상위`(상위 동결 문서의 ID) /
`리뷰`(리뷰에서 파생) / `추론`(근거 없음).

**기준 개수**: 8개

**티어 강등 사유** *(T1이 아닌 항목만)*

- AC1·AC2·AC3·AC8 → T3: 하네스 파일(`.claude/**/*.md`)을 검사하는 자동 gate가 이 저장소에 없고,
  그것을 만드는 일(=`scripts/harness-check.sh` 이식)을 사용자가 이번 스코프에서 **명시적으로 제외**했다.
  기존 gate 조사표대로 대체 가능한 수단이 없으므로 1회 관찰 기록으로 판정한다. "인프라가 없어서"가
  아니라 "인프라를 만들지 않기로 사람이 결정해서"다.

## 검사 산출물

> `신규스크립트`를 검증 수단으로 쓰지 않는다 (게이트 생략 결정). 해당 없음.

| 스크립트 | 경로 | 정상 표본 | 위반 표본 | 증명 |
|---|---|---|---|---|
| — | — | — | — | — |

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 정본 문서 무결성 (AGENTS.md·`.ai/*`·`docs/runbooks/*`의 링크·placeholder·낡은 서술) | `bash docs/check-documentation.sh` |
| R2 | 애플리케이션 빌드가 이 변경에 영향받지 않음 (코드 무변경이므로 컴파일 경로 유지) | `./gradlew compileKotlin --offline --no-daemon` |

## 증거 로그

> 구현 중 append only. 아래 실행은 모두 worktree
> `.worktrees/chore-harness-aic-api-alignment` (branch `chore/harness-aic-api-alignment`) 에서 수행했다.

### AC1

```
[T3 관찰] 2026-08-31 8ce7009
$ ls .claude/agents | wc -l
6
$ head -6 .claude/agents/*.md | grep -cE '^(name|description|tools|model):'
24        # 6개 파일 × 4키 = 24, 누락 0

파일별 확인: architect(opus/Read,Grep,Glob,Write) · implementer(sonnet/+Write,Edit,Bash) ·
spec-reviewer(opus/Read,Grep,Glob) · code-reviewer(opus/+Bash) · qa-agent(sonnet/+Bash) ·
tech-docs(sonnet/Read,Edit,Write,Grep,Glob). name 은 모두 파일명과 일치.
```

### AC2

```
[T3 관찰] 2026-08-31 8ce7009
$ bash <AC2 확인 스크립트>   # 디렉터리명 ↔ SKILL.md frontmatter name 대조
OK   definition-of-done / dto-pattern / explain-pr / jpa-entity-pattern / module-layout /
     orchestrator / qa-verification / swagger-interface-pattern / task-packet /
     tech-docs-sync / test-strategy
---
스킬 11 개 / 불일치 0 건
```

### AC3

```
[T3 관찰] 2026-08-31 8ce7009
대상: .claude/agents/*.md 6개 + 자체 작성 스킬 9종 SKILL.md + CLAUDE.md
제외: frontmatter, 코드펜스 내부, `{...}` 플레이스홀더, `/.../` 생략 표기,
      ALLOWED_MISSING(.ai/diagrams/ — 부재를 문서 본문이 명시), 벤더링 사본 2종

검사한 경로 토큰: 96 / 죽은 참조: 0

1차 실행에서 18건이 떴으나 전부 검사기 오탐(frontmatter 의 `·` 구분자, 펜스 내 예시 경로,
문서가 "없다"고 서술한 .ai/diagrams/)이었다. 실제 수정이 필요했던 것은 1건 —
module-layout 의 `db/migration/V{다음}__{설명}.sql` 표기가 경로 토큰을 자르던 것으로,
디렉터리와 파일명 패턴을 분리해 해소했다.
```

### AC4

```
[GREEN] 2026-08-31 8ce7009
$ diff -r /home/yeop/.claude/skills/definition-of-done .claude/skills/definition-of-done
(출력 없음, exit 0)

$ git ls-files -s .claude/skills/definition-of-done
100644 ... SKILL.md
100644 ... template.dod.md          # 심볼릭 링크(120000) 아님

$ sha256sum
5161281b504adef0c9b11a5b5dd03cfee1715e0aed25003339e0f01385515e31  SKILL.md
fb2c3893979518fe7937512b03975337ee43475ba437cf7c2a9cf852957b876f  template.dod.md

[RED] 2026-08-31 d90a733 — 최초 복사본은 심볼릭 링크였다.
원본 ~/.claude/skills/definition-of-done 자체가 /home/yeop/dev/ai-skills/shared/... 로의
링크라 cp -r 이 링크를 그대로 복사했고 git 에 mode 120000 으로 들어갔다. diff -r 은 양쪽이
같은 대상을 따라가 통과해 이 상태를 검출하지 못했다(코드 리뷰 P1 이 검출). cp -rL 로 교체.
```

### AC5

```
[GREEN] 2026-08-31 8ce7009
$ diff -r /home/yeop/.claude/skills/explain-pr .claude/skills/explain-pr
(출력 없음, exit 0)

$ git ls-files -s .claude/skills/explain-pr
100644 SKILL.md · 100644 template.md · 100755 scripts/gather.sh · 100755 scripts/test_gather.sh

$ sha256sum
25d6605dacc9aa3bf4cdc7a98fa5aa8fd1e9991b7538903a1a2bdbe4284e0e28  SKILL.md
bdf382f892e137604fedb3c1d77bdb1566ffbda74e7fa7df489f9b035356333a  template.md
f7d5f7b4a9b17eaf3425c248e81e16a635007dc40eeb50f80a702550d8012e6d  scripts/gather.sh
e3b8b9efffb78d709229d78386c32de421af58afe0eba06497a62251394ace8c  scripts/test_gather.sh

[RED] AC4 와 같은 원인. 추가로 worktree 가 /mnt/c(DrvFs)라 실행 비트가 100644 로 죽어
SKILL.md 가 직접 실행하는 scripts/gather.sh 가 깨질 수 있었다. git update-index --chmod=+x 로 복구.
```

### AC6

```
[GREEN] 2026-08-31 8ce7009
$ grep -Pzoq '## 하네스[\s\S]*?### 변경 이력' CLAUDE.md
(exit 0)

명령 자체 검증: 정상 표본(## 하네스 + ### 변경 이력) exit 0,
위반 표본(## 하네스 만 있고 변경 이력 없음) exit 1 을 확인했다.
```

### AC7

```
[GREEN] 2026-08-31 8ce7009
$ bash docs/check-documentation.sh
documentation check passed (20 files, 15 required paths)
```

### AC8

```
[T3 관찰] 2026-08-31 8ce7009
$ git diff --stat origin/dev...HEAD | tail -1
40 files changed, 2930 insertions(+), 1853 deletions(-)

$ git diff --name-only origin/dev...HEAD | grep -vE '^(\.claude/|CLAUDE\.md$|docs/work/)'
(출력 없음 — 범위 밖 파일 0개)

$ ls .claude/agents
architect.md code-reviewer.md implementer.md qa-agent.md spec-reviewer.md tech-docs.md
$ ls .claude/skills
definition-of-done dto-pattern explain-pr jpa-entity-pattern module-layout orchestrator
qa-verification swagger-interface-pattern task-packet tech-docs-sync test-strategy

구 자산 잔존 0개 (에이전트 analyzer·planner·db-migrator·frontend-dev·qa-validator·tech-writer,
스킬 planning·api-feature·batch-job·db-migration·frontend·qa-validator·code-review·tech-docs·
premium-orchestrator 모두 제거). AGENTS.md·.ai/*·docs/* 에 삭제된 이름을 가리키는 참조 없음.
```

### 회귀 방어선

```
[R1] 2026-08-31 8ce7009
$ bash docs/check-documentation.sh
documentation check passed (20 files, 15 required paths)

[R2] 2026-08-31 8ce7009
$ ./gradlew compileKotlin --offline --no-daemon
BUILD SUCCESSFUL in 44s — 15 actionable tasks: 15 executed
```

## 사람 확인 (T4)

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| — | 이번 계약에 T4 항목 없음 | — | — | — |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: harness-aic-api-alignment @ 8ce7009
  수용기준 표:     8개  (T1 4 · T2 0 · T3 4 · T4 0)
  T1/T2 자동:      4개 중 4개 PASS
  T3 기록 제출:    4개 중 4건
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       0건
  => PASS
```

판정 대상은 구현 최종 커밋 `8ce7009`이며, 이후 커밋은 이 증거 로그를 기록하는 문서 변경뿐이다.

**사람 확인이 필요한 항목**

- (없음)
