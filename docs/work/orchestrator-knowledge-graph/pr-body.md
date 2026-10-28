## 개발자 이해문서

orchestrator ② 의 영향 범위 파악은 `Explore` fan-out 이 전부였고, 저장소 밖 코드 지식 그래프(`graphify`)를 쓰는 방법은 문서화돼 있지 않았다. 이 PR 은 그것을 **선택 경로**로 넣는다 — `~/graphs/<레포>/graph.json` 과 `graphify` CLI 가 둘 다 있으면 ① 에서 판정·최신화(새 worktree, 변경 0 일 때만)하고 ② 첫 패스에 `explain`/`query` 를 쓰며, 없으면 지금과 완전히 같다(안내·질문·중단 없음). 자매 저장소 aic-api 에서 리뷰·머지된 문면을 옮겼고, 이 저장소 ① 이 `origin/{base}` 에서 분기하므로 `refresh.sh`·오래됨 비교의 base 만 `origin/{base}` 로 바꿨다.

📄 **[개발자 이해문서 전문 보기 →](https://github.com/geonyeop123/premium-spread/blob/d21630b0e20987087d90ffc0904fc4dc196c1b74/docs/work/orchestrator-knowledge-graph/understanding.md)**
<sub>`docs/work/orchestrator-knowledge-graph/understanding.md` · 커밋 고정 링크라 브랜치 삭제 후에도 열립니다</sub>

→ [design](https://github.com/geonyeop123/premium-spread/blob/d21630b0e20987087d90ffc0904fc4dc196c1b74/docs/work/orchestrator-knowledge-graph/design.md)
· [plan](https://github.com/geonyeop123/premium-spread/blob/d21630b0e20987087d90ffc0904fc4dc196c1b74/docs/work/orchestrator-knowledge-graph/plan.md)
· [dod](https://github.com/geonyeop123/premium-spread/blob/d21630b0e20987087d90ffc0904fc4dc196c1b74/docs/work/orchestrator-knowledge-graph/dod.md)

## Summary

| 파일 | 변경 |
|---|---|
| `.claude/skills/orchestrator/SKILL.md` | **+3 · −0** — "선택" 표 1 행(53) · ① 끝 불릿(215) · ② 끝 불릿(226) |
| `.claude/skills/orchestrator/references/knowledge-graph.md` | 신규 80 줄 — §1 판정 · §2 최신화(`refresh.sh <wt> origin/{base}`, 종료 코드 0/3/4/그 외, 멈추지 않음) · §3 오래됨(`BUILT_FROM`) · §4 사전 탐색 · §5 범위 밖 |
| `docs/harness-changelog.md` | 최상단 1 행 (CRLF 유지) |
| `docs/work/orchestrator-knowledge-graph/` | design · plan · dod(FROZEN) · understanding · README · 이 본문 |

하네스 파일 변경뿐. Kotlin·리소스·설정·Flyway 변경 0.

### 주요 설계 결정

- **base = `origin/{base}`.** 이 저장소 ① 은 로컬 `{base}` 를 pull 하지 않고 `origin/{base}` 에서 분기한다. `refresh.sh` 는 둘째 인자로 `merge-base --is-ancestor HEAD "$BASE"` 를 판정하므로 stale 한 로컬 `dev` 를 넘기면 판정이 틀어진다. aic-api 문면과의 유일한 차이(5 곳).
- **2 단계 판정.** `SKILL.md` 는 `~/graphs/` 존재만 본다 — 없으면 references 를 읽지도 않는다. 미설치자에게 늘어나는 행동은 디렉터리 확인 1 회.
- **references 를 전체 경로로 표기.** `docs/check-harness.sh` 축 1 은 소유 접두 백틱 경로만 실재 검사한다. 상대 표기였다면 파일이 사라져도 게이트가 모른다.
- **갱신은 새 worktree 직후·변경 0 일 때만.** 공용 그래프에 피처 코드가 섞이지 않게 한다.
- **⑦ 동결 근거:** 사용자 지시 "각 프로젝트별로 작업 후 MR 까지 올려" 를 동결 승인으로 보고 진행했다(동결 커밋 `60ee3ab`, dod 2 줄만).

## Test plan

- [x] `./gradlew harnessCheck harnessCheckTest` — 하네스 검사 통과: 파일 22개 · 자가검증 모든 케이스 통과 (exit 0)
- [x] **RED→GREEN:** `SKILL.md` 3 줄만 넣은 상태에서 `harnessCheck` 실패 — 죽은 참조 3 건(53·215·226 행), references 생성 후 통과
- [x] `bash docs/check-documentation.sh` — exit 0
- [x] `git diff --numstat 20a7484 -- SKILL.md` → `3	0`, hunk `@@ -52,0 +53 @@` · `@@ -213,0 +215 @@` · `@@ -223,0 +226 @@`
- [x] 문면 = design §5 블록 바이트 동일 (references sha256 `5369caf6…`, 블록 1–3 은 aic-api 와 동일)
- [x] `docs/harness-changelog.md` CRLF 유지 — `grep -c $'\r$'` 14 → 15
- [x] 그래프 실측(이 머신): `refresh.sh` 판정 재현(`merge-base --is-ancestor HEAD origin/dev`), `graphify explain '…/Premium.kt::Premium'` 연결 52 개에 `파일:L라인`, `explain premium` 은 `Ambiguous`(rc 3) → 문서 §4 재지정 경로대로
- [x] DoD AC1–AC8 · R1–R4 PASS — **DoD VERDICT: AWAITING_HUMAN**
- [ ] **AC9 (리뷰어 확인 요청):** 추가 3 줄과 references 서두를 읽고 (a) `~/graphs/` 가 없을 때 추가 행동이 디렉터리 확인 1 회뿐 (b) ② 줄이 판정 없을 때 "기존과 같다" 로 끝남 (c) 개인 저장소 이름 없음 — 을 확인해 코멘트로 남겨 주세요

## 리뷰 이력

- **⑥ 스펙 리뷰 (경미 3, 전부 수용):** `docs/harness-changelog.md` 가 CRLF 라 새 행도 CRLF 로 넣도록 지시·R4 추가 · `check-harness.sh` 접두 목록 인용을 `:45-50` 으로 정정 · 축 3 범위와 `SKILL.md:115` 인용 정정.
- **⑨ 계약 리뷰:** 지적 0. **⑨ 버그·회귀 리뷰:** 지적 0 — `origin/{base}` 인자로 refresh 판정이 의도대로 나오는 것을 실제 worktree 로 재현.
