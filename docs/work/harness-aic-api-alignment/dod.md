---
feature: 하네스 aic-api 정합 재구성
slug: harness-aic-api-alignment
status: DRAFT
frozen_at:
verdict_commit:
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
| AC3 | 새 하네스 문서 본문이 언급하는 저장소 경로가 전부 실재한다 (`_workspace/` 처럼 부재가 문서에 명시된 경로 제외) | design §1 #1·#2 재발 방지 | `리뷰` | T3 | `관찰` 경로 토큰 추출 후 `test -e` 결과 기록 | — | 죽은 참조 0건 |
| AC4 | 벤더링한 `definition-of-done` 이 원본과 byte-identical | design §3 | `요구` | T1 | `기존` `diff -r` | `diff -r /home/yeop/.claude/skills/definition-of-done .claude/skills/definition-of-done` | exit 0 |
| AC5 | 벤더링한 `explain-pr` 이 원본과 byte-identical | design §3 | `요구` | T1 | `기존` `diff -r` | `diff -r /home/yeop/.claude/skills/explain-pr .claude/skills/explain-pr` | exit 0 |
| AC6 | `CLAUDE.md` 에 `## 하네스` 섹션이 있고 변경 이력 표가 포함된다 | design §4 | `요구` | T1 | `기존` `grep` | `grep -q '^## 하네스' CLAUDE.md` | exit 0 |
| AC7 | 정본 문서 무결성 검사가 통과한다 (깨진 링크·placeholder·낡은 아키텍처 서술 0건) | 기존 CI gate | `요구` | T1 | `기존` `docs/check-documentation.sh` | `bash docs/check-documentation.sh` | exit 0 |
| AC8 | 구 하네스 자산(에이전트 8·스킬 9)이 제거됐고, 변경 범위가 `.claude/` · `CLAUDE.md` · `docs/work/` 밖으로 나가지 않는다 | design §2 비목표·§6 | `요구` | T3 | `관찰` `git diff --stat dev...HEAD` 기록 | — | 구 자산 0개 잔존, 범위 밖 파일 0개 |

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

> 구현 중 append only.

### AC1

```
(대기)
```

### AC2

```
(대기)
```

### AC3

```
(대기)
```

### AC4

```
(대기)
```

### AC5

```
(대기)
```

### AC6

```
(대기)
```

### AC7

```
(대기)
```

### AC8

```
(대기)
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
DoD VERDICT: harness-aic-api-alignment @ <commit SHA>
  수용기준 표:     8개  (T1 4 · T2 0 · T3 4 · T4 0)
  T1/T2 자동:      4개 중 <p>개 PASS
  T3 기록 제출:    4개 중 <q>건
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       <k>건
  =>
```

**사람 확인이 필요한 항목**

- (없음)
