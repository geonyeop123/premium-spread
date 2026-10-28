---
feature: orchestrator ①② 에 선택 지식 그래프(graphify) 경로 추가 — `SKILL.md` 3 줄 + `references/knowledge-graph.md` 신설 + 하네스 변경 이력 1 행
slug: orchestrator-knowledge-graph
status: FROZEN
frozen_at: 2026-10-28T10:03:34+09:00
verdict_commit:
source: 요구 U1–U4 (`docs/work/orchestrator-knowledge-graph/design.md` §3), 설계 판단 A1–A7 (§4), 최종 문면 §5. 하네스 파일 변경이며 코드 변경 없음
---

## 범위

**포함**

- `.claude/skills/orchestrator/SKILL.md` — 선택 표 1 행(§5.1) · ① 불릿(§5.2) · ② 불릿(§5.3). **추가 3 · 삭제 0**
- 신규 `.claude/skills/orchestrator/references/knowledge-graph.md` (§5.4)
- `docs/harness-changelog.md` 최상단 1 행 (§5.5)

**제외** — ④ 이후 에이전트의 그래프 사용, 그래프 구축, 하네스 게이트 스크립트·`CLAUDE.md`·에이전트 정의 변경.

## 기존 gate 조사

| gate | 무엇을 | 이 작업에서 |
|---|---|---|
| `./gradlew harnessCheck` (`docs/check-harness.sh`) | 축 1 백틱 경로 실재(`docs/ .ai/ .claude/ ci/ config/ http/ gradle/ docker/`) · 축 2 frontmatter · 축 3 필수 절·순서·스킬 디렉터리 형태·변경 이력 헤더 | references 전체 경로 3 곳의 실재를 잰다 — RED 가능 |
| `./gradlew harnessCheckTest` (`docs/test-check-harness.sh`) | 검사기 자가검증 픽스처 | 검사기 무변경 — 회귀 방어선 |
| `bash docs/check-documentation.sh` | 정본 문서(AGENTS.md·`.ai/*`·`docs/runbooks/*`) 구조 | 무변경 — 회귀 방어선 |

## 수용기준

`S` = `.claude/skills/orchestrator/SKILL.md`, `R` = `.claude/skills/orchestrator/references/knowledge-graph.md`, `C` = `docs/harness-changelog.md`

| # | 기준 | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 기대 |
|---|---|---|---|---|---|---|---|
| AC1 | `S` 선택 표 끝에 지식 그래프 행이 있다 | U2(a) | `요구` | T2 | `기존` `grep` | `grep -n "^. 지식 그래프 — " <S>` | 1 행, 행 번호 53, references 전체 경로 포함 |
| AC2 | `S` ① 끝에 그래프 불릿이 있다 | U2(b) · U1 | `요구` | T2 | `기존` `grep` | `grep -n "지식 그래프(선택)" <S>` | 1 행, 행 번호 215, `알리지도 않는다` 포함 |
| AC3 | `S` ② 끝에 불릿이 있다 | U2(b) · A6 | `요구` | T2 | `기존` `grep` | `grep -n "쓰기로 판정했으면" <S>` | 1 행, 행 번호 226, `§4`·`기존과 같다` 포함 |
| AC4 | `R` 이 design §5.4 블록과 바이트 동일하다 — §1 판정 · §2 최신화(`origin/{base}`) · §3 오래됨 · §4 탐색 · §5 범위 밖 | U2(c) · U3 · A3 | `요구` | T2 | `기존` `sha256sum` | `sha256sum <R>` | `5369caf6d47c1503…` 로 시작 |
| AC5 | `R` 의 `refresh.sh` 호출이 `origin/{base}` 를 넘긴다 | A3 | `추론` | T2 | `기존` `grep` | `grep -c 'refresh.sh "<worktree 절대경로>" origin/{base}' <R>` | `1` |
| AC6 | 하네스 정합성 검사가 통과한다 — references 경로 3 곳 실재 | U2(c) · A1 | `요구` | T1 | `기존` | `./gradlew harnessCheck` | exit 0 |
| AC7 | `S` diff 가 추가 3 · 삭제 0 | U4 · U1 | `요구` | T2 | `기존` `git diff` | `git diff --numstat 20a7484 -- <S>` | `3	0	…` |
| AC8 | `C` 최상단(헤더 구분선 다음) 행이 이 변경을 기록한다 | `CLAUDE.md:44-45` · A5 | `요구` | T2 | `기존` `grep` | `grep -n "^| 2026-10-28 | orchestrator ①② 에 선택 지식 그래프" <C>` | 1 행, 헤더 구분선 바로 다음 행 |
| AC9 | 그래프가 없는 환경에서 행동이 바뀌지 않는다 — 사람이 추가 3 줄과 references 서두를 읽고 (a) 추가 행동이 `~/graphs/` 확인 1 회뿐 (b) ② 줄이 "기존과 같다" 로 끝남 (c) 개인 저장소 이름 없음 을 확인 | U1 | `요구` | T4 | 사람 검토 | — | PR 코멘트 URL |

**`추론`** — AC5 하나(A3: 이 저장소 ① 이 `origin/{base}` 에서 분기한다는 기존 코드에서 도출).

**티어 강등 사유** — AC1–AC5·AC7·AC8 은 마크다운 문자열이 대상이고, 이를 단정하는 이름 붙은 산출물은 `docs/check-harness.sh` 축 3 뿐이며 그 스크립트 변경은 범위 밖이다(aic-api 동일 작업과 같은 판단).

## 회귀 방어선

| # | 명령 | 기대 |
|---|---|---|
| R1 | `./gradlew harnessCheckTest` | exit 0 |
| R2 | `bash docs/check-documentation.sh` | exit 0 |
| R3 | `git diff --quiet 20a7484 -- CLAUDE.md .claude/agents docs/check-harness.sh docs/test-check-harness.sh` | exit 0 |
| R4 | `grep -c $'\r$' docs/harness-changelog.md` — 이 파일의 CRLF 규약 유지(⑥ ISSUE-1) | `15` (기준선 14 + 새 행 1) |

## 증거 로그

> append only.

### 작성 시점 기준선 (2026-10-28, `20a7484`)

```
$ ./gradlew harnessCheck -q
하네스 검사 통과: 파일 22개
```

## 사람 확인 (T4)

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC9 | (a)(b)(c) | | | |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: orchestrator-knowledge-graph @ <commit SHA>
  수용기준 표:     9개  (T1 1 · T2 7 · T3 0 · T4 1)
  T1/T2 자동:      8개 중 <p>개 PASS
  T4 사람 확인:    1개 중 <r>건 완료
  =>
```
