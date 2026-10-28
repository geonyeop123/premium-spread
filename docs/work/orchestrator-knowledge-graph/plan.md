# 구현 계획 — orchestrator ①② 선택 지식 그래프 경로: 편집 지점 5개

| 항목 | 값 |
|---|---|
| 설계 | `docs/work/orchestrator-knowledge-graph/design.md` — **§5 가 최종 문면 정본** |
| 계약서 | `docs/work/orchestrator-knowledge-graph/dod.md` — AC1–AC9, R1–R3 |
| 브랜치 | `chore/orchestrator-knowledge-graph` (base `origin/dev` `20a7484`) |
| 코드 변경 | 0 |

**문면은 손으로 다시 치지 않는다.** design §5 의 4-백틱 블록 내부를 추출 스크립트로 뽑아 앵커 줄 뒤에 기계 삽입한다
(`awk -v n=N '/^````markdown$/{c++; if(c==n){f=1; next}} f && /^````$/{f=0} f' design.md`, 스크립트 파일로 실행).

| 블록 | 편집 | 행수 | sha256 (앞 16자) | 앵커 (줄 전체 일치) |
|---|---|---|---|---|
| 1 | 선택 표 행 | 1 | `d2203a8e2799a058` | `SKILL.md:52` `finalize` 행 |
| 2 | ① 불릿 | 1 | `99c260d699d27462` | `SKILL.md:213` `- worktree 제거는 ⑪ PR 머지 이후에만.` |
| 3 | ② 불릿 | 1 | `f54d1ac5a313c1cb` | `SKILL.md:223` `- 광범위한 코드 탐색은 \`Explore\`에 위임하고 요약만 받는다.` |
| 4 | references 전문 | 80 | `5369caf6d47c1503` | 신규 파일 |
| 5 | 변경 이력 행 | 1 | `3add2bed1de1c937` | `docs/harness-changelog.md` 표 헤더 구분선(`|---|---|---|---|`) 뒤 |

`grep -c '^````markdown$' design.md` = **5**. ⑥ 이 §5 를 고치면 이 표를 다시 잰다.

순서 원리: Java 유스케이스가 아니므로 `tdd-rules` 계층 순서 대신 **참조를 먼저 넣고(RED) 파일을 만든다(GREEN).**

## Task 0 — 선행 확인 (읽기 전용)

- [ ] 블록 지문이 위 표와 같다.
- [ ] 앵커 3 줄이 `SKILL.md` 에 각 1 회(`grep -cxF`), 변경 이력 헤더 구분선이 1 회.
- [ ] `./gradlew harnessCheck` exit 0 (기준선).

## Task 1 — 편집 1·2·3 (`SKILL.md`)

- [ ] 블록 1·2·3 을 앵커 줄 바로 뒤에 삽입(`python3 -I`, 줄 전체 일치).
- [ ] `git diff --numstat -- .claude/skills/orchestrator/SKILL.md` → `3	0`; hunk `@@ -52,0 +53 @@` · `@@ -213,0 +215 @@` · `@@ -223,0 +226 @@`.
- [ ] `./gradlew harnessCheck` → **exit 1**, 죽은 참조 3 건(53·215·226 행) — AC6 RED. 다른 이유면 멈춘다.

## Task 2 — 편집 4 (references) + 편집 5 (변경 이력) + 커밋

- [ ] 블록 4 → `.claude/skills/orchestrator/references/knowledge-graph.md` (sha `5369caf6…`).
- [ ] 블록 5 → `docs/harness-changelog.md` 헤더 구분선 다음 줄.
- [ ] `./gradlew harnessCheck harnessCheckTest` exit 0 · `bash docs/check-documentation.sh` exit 0.
- [ ] 커밋: `chore: orchestrator 에 선택 지식 그래프 경로 추가` + 한글 bullet 본문 (`.ai/rules/git.md`, Co-Authored-By 없음).

## Task 3 — 검증 + dod 증거

- [ ] dod AC1–AC8 · R1–R3 명령을 dod 에 적힌 그대로 실행, 기대값 비교, 증거 로그에 추가.
- [ ] 커밋: `docs: orchestrator 지식 그래프 DoD 증거 기록`.

## AC 커버리지

| AC | 태스크 |
|---|---|
| AC1·AC2·AC3·AC7 | T1 (확인 T3) |
| AC4·AC5·AC8 | T2 (확인 T3) |
| AC6 | RED T1 · GREEN T2 |
| AC9 | ⑩ 사람 확인 |
