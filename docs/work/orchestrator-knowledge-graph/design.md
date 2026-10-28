# orchestrator ①② 에 선택 지식 그래프(graphify) 경로 추가 — 설계

작성일: 2026-10-28
상태: 설계 (구현 전). **코드 변경이 없는 하네스 파일 변경**이다 — `CLAUDE.md` "하네스" 절대로 `orchestrator` 를
그대로 돈다. 자매 저장소 aic-api 에 같은 변경을 먼저 넣었다(aic-api MR !239, 그쪽 `docs/work/orchestrator-knowledge-graph/`).
이 문서는 그 결정을 이 저장소 구조에 옮긴 것이다.

| 항목 | 값 |
|------|-----|
| 고치는 파일 | `.claude/skills/orchestrator/SKILL.md` — **줄 추가 3, 삭제 0** · `docs/harness-changelog.md` — 표 1 행 |
| 새 파일 | `.claude/skills/orchestrator/references/knowledge-graph.md` |
| 편집 지점 | **5** — 선택 표 행 · ① 불릿 · ② 불릿 · references 전문 · 하네스 변경 이력 행 (§5) |
| 건드리지 않는 것 | `SKILL.md` 기존 문장 전부, `CLAUDE.md`, `.claude/agents/*.md`, `docs/check-harness.sh`·`docs/test-check-harness.sh`, 벤더링 스킬 |
| Java·Kotlin·리소스·설정 변경 | **0** |
| 게이트 | `./gradlew harnessCheck`(축 1 참조 실재 · 축 2 frontmatter · 축 3 계약 단정), `./gradlew harnessCheckTest`, `bash docs/check-documentation.sh` |
| 기준 커밋 | `origin/dev` `20a7484` (worktree 분기점). `SKILL.md:행` 표기는 이 커밋 기준 |

> **이 작업의 산출물은 "문서 문장" 그 자체다.** 넣을 문장을 §5 에 전문으로 확정하고 ⑧ 은 그것을 기계적으로 옮긴다.

## 1. 무엇을 왜 바꾸는가

② 의 영향 범위 파악은 지금 `Explore` fan-out 이 전부다(`SKILL.md:115`·`:128` "사전 탐색 위임 원칙", `:223`).
저장소 밖 `~/graphs/<레포>/` 에 코드 지식 그래프를 두고 `graphify` CLI 를 쓰는 개발자는 "이 심볼을 누가 쓰는가 ·
어떤 테스트가 import 하는가" 를 `파일:L라인` 근거와 함께 한 번에 받는다. 이 저장소의 그래프는
`~/graphs/premium-spread/` 에 2026-10-28 처음 만들었다(BUILT_FROM = `20a7484`). 그 사용법을 orchestrator 의 **선택 경로**로 적는다.

## 2. 범위

**포함** — §5 의 편집 지점 5 개.

**제외** — ④ 이후 에이전트의 그래프 사용, `SKILL.md` 구조 정리, 그래프 구축 방법(`~/graphs/tools/`), 하네스 게이트 스크립트 변경.

## 3. 요구 (U — aic-api 에서 사용자와 합의, 이 저장소에 "마찬가지로" 적용 지시)

- **U1.** 그래프가 있으면 쓰고 없으면 지금과 **완전히 같게** 동작한다. 없는 개발자에게 안내·질문·중단이 생기지 않는다(최우선).
- **U2.** (a) `SKILL.md` "### 선택" 표에 1 행 (b) ① 끝과 ② 끝에 각 한 줄로 references 연결 (c) 상세는 새 파일 `.claude/skills/orchestrator/references/knowledge-graph.md`.
- **U3.** 위치 `~/graphs/<git-common-dir 부모 폴더명>` · `graph.json` 과 `graphify` CLI 둘 다 있을 때만 · 최신화는 새 worktree 직후 변경 0 이고 `refresh.sh` 가 있을 때만, 종료 코드 0/3/4/그 외, 어떤 경우도 멈추지 않음 · 없으면 `BUILT_FROM` 비교로 오래됨 감안 · ② 는 `explain '<경로>::<심볼>'`·`query --budget 2000` 이 출발점, `Ambiguous` 재지정, 테이블은 `explain`·`path` 금지, 베이스 스냅샷 · 구축 방법 범위 밖 · 개인 저장소 이름 미기재.
- **U4.** 에이전트 연동은 범위 밖, `SKILL.md` 증가는 최소.

## 4. 설계 판단 (A)

- **A1. references 를 전체 경로로 표기.** `docs/check-harness.sh` 축 1 은 `docs/ .ai/ .claude/ ci/ config/ http/ gradle/ docker/` 접두 백틱 토큰만 실재 검사한다(`:46`). 전체 경로여야 파일 삭제·이동이 게이트에 걸린다.
- **A2. 2 단계 판정.** `SKILL.md` 는 `~/graphs/` 존재만 본다 — 없으면 references 를 읽지도 않는다. 미설치자에게 늘어나는 행동은 디렉터리 확인 1 회.
- **A3. `refresh.sh` 의 base 인자는 `origin/{base}`.** 이 저장소 ① 은 로컬 `{base}` 가 아니라 `origin/{base}` 에서 분기한다(`SKILL.md:205-210`). `refresh.sh` 는 둘째 인자를 `merge-base --is-ancestor HEAD "$BASE"` 에 넘기므로 `origin/{base}` 가 맞다. 로컬 `dev` 는 stale 할 수 있다. §3 오래됨 비교도 같은 ref 를 쓴다. **aic-api 문면과 다른 유일한 지점이다.**
- **A4. references 는 축 1·2 스캔 대상이 아니다.** `check_refs`·`check_frontmatter` 는 `.claude/skills/*/SKILL.md` 만 본다(`docs/check-harness.sh:229-233`, `:279-283`). "SKILL.md 없는 스킬 디렉터리" 검사(`:360`)는 `.claude/skills/*/` 최상위만 보므로 `references/` 하위 디렉터리는 걸리지 않는다. frontmatter 없이 둔다.
- **A5. 하네스 변경 이력 1 행.** `CLAUDE.md:44-45` 가 하네스 변경마다 `docs/harness-changelog.md` 에 한 행을 요구한다(최신 행이 위). aic-api 에서는 changelog 항목을 사용자 결정으로 뺐지만 그쪽은 별도 changelog 디렉터리였고, 이 저장소는 저장소 규칙이 강제한다.
- **A6.** 판정은 ① 에서 한 번, ② 가 이어받되 컨텍스트에서 사라졌으면 §1 을 다시 확인한다. 종료 코드 `3` 은 "안 씀", `4`·그 외는 "오래됨 감안".
- **A7.** 다른 표·기존 문장은 고치지 않는다. `SKILL.md` 595 → 598 행.

## 5. 편집 지점 — 최종 문면 전문

> 블록 **안쪽 내용**이 그대로 들어간다. 삽입 후 hunk 는 `@@ -52,0 +53 @@` · `@@ -213,0 +215 @@` · `@@ -223,0 +226 @@`.

### 5.1 [편집 1] 선택 표 1 행 — `SKILL.md:52` (`finalize` 행) 뒤

````markdown
| 지식 그래프 — `graphify` CLI + `~/graphs/<레포>/graph.json` (저장소 밖 개인 설치) | ①② | 기존 `Explore` 탐색. 둘 다 있으면 사용자 명시 없이 쓴다 — `.claude/skills/orchestrator/references/knowledge-graph.md` |
````

### 5.2 [편집 2] ① 불릿 — `SKILL.md:213` (`- worktree 제거는 ⑪ PR 머지 이후에만.`) 뒤

````markdown
- **지식 그래프(선택).** `~/graphs/` 가 있으면 `.claude/skills/orchestrator/references/knowledge-graph.md` §1 판정을 하고, worktree 를 이번에 새로 만들었으면(변경 0) §2 최신화까지 한다. `~/graphs/` 가 없으면 아무것도 하지 않고 알리지도 않는다.
````

### 5.3 [편집 3] ② 불릿 — `SKILL.md:223` (`- 광범위한 코드 탐색은 \`Explore\`에 위임하고 요약만 받는다.`) 뒤

````markdown
- ①에서 지식 그래프를 쓰기로 판정했으면 영향 범위 첫 패스는 `.claude/skills/orchestrator/references/knowledge-graph.md` §4 를 따른다. 본문·로직 판단은 그대로 Read·`Explore` 다. ① 판정이 컨텍스트에 남아 있지 않으면 references §1 을 다시 확인한다. 판정하지 않았으면 기존과 같다.
````

### 5.4 [편집 4] 신규 `.claude/skills/orchestrator/references/knowledge-graph.md` 전문

````markdown
# 지식 그래프 — orchestrator ①② 탐색 보조

`orchestrator` ①·② 가 이 파일을 가리킨다. 저장소 밖에 지식 그래프(`graphify`)를 둔 개발자를 위한
**선택 경로**다. 그래프가 없으면 여기 적힌 어느 것도 적용되지 않고, orchestrator 는 이 파일이 없던
때와 똑같이 돈다 — 안내 메시지도 내지 않는다. 그래프를 만드는 방법은 이 스킬의 범위가 아니다.

## 1. 판정 — 쓸지 말지 (①)

그래프 위치는 `~/graphs/<레포>/` 다. `<레포>` 는 **원래 저장소의 폴더명**이다. worktree 안에서 현재
디렉터리 이름을 쓰면 worktree 이름이 나오므로 git 공통 디렉터리의 부모로 구한다. worktree 안에서도
같은 이름이 나온다.

```bash
basename "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
```

폴더명은 개발자마다 달라도 된다 — 각자 자기 클론의 폴더명으로 찾는다.

**`~/graphs/<레포>/graph.json` 과 `graphify` CLI 가 둘 다 있을 때만 쓴다.** 하나라도 없으면 사용자에게
알리지 않고 기존 `Explore` 탐색으로 간다. 판정 결과는 ② 까지 들고 간다.

## 2. 최신화 — 새 worktree 를 만든 직후에만 (①)

`~/graphs/tools/refresh.sh` 가 **있을 때만**, worktree 를 이번에 새로 만든 직후 — 아무것도 바꾸기 전 — 한 번 돌린다.
기존 worktree 로 이어 가는 실행에서는 돌리지 않는다. 이미 피처 커밋이 있어 `4` 가 나올 뿐이다.

```bash
~/graphs/tools/refresh.sh "<worktree 절대경로>" origin/{base}
```

| 종료 코드 | 뜻 | 이어서 |
|---|---|---|
| `0` | 그래프가 최신이거나 방금 갱신했다 | 그래프를 쓴다 |
| `3` | 이 레포의 그래프가 없다 | 그래프를 쓰지 않는다 — 판정을 "안 씀" 으로 바꾼다 |
| `4` | 피처 변경(미커밋 변경, 또는 `origin/{base}` 에 없는 커밋)이 있어 갱신을 거부했다 | 갱신 없이 쓰되 §3 을 감안한다 |
| 그 외 | 갱신 실패 | `4` 와 같다 |

**어떤 종료 코드도 워크플로를 멈추지 않는다.** 그래프는 탐색 보조이지 게이트가 아니다.

**베이스 상태에서만 갱신하는 이유.** 그래프는 이 머신의 모든 worktree 가 함께 쓴다. 피처 작업 중인 트리로
갱신하면 머지되지 않은 코드가 공용 그래프에 섞여, 다른 작업의 ② 가 존재하지 않는 코드를 근거로 영향 범위를
잡는다. 이 조건을 지우지 말 것.

## 3. 오래됨

§2 에서 `0` 을 받지 못했으면(스크립트 없음 · 이어 가는 실행이라 돌리지 않음 · `4` · 그 외) 그래프를 그대로 쓰되, 그래프 폴더의 `BUILT_FROM`(그래프를 만든 커밋
SHA)을 `origin/{base}` 의 HEAD 와 비교한다. 다르면 그래프가 오래됐다는 것을 감안한다.

```bash
cat ~/graphs/<레포>/BUILT_FROM
git rev-parse origin/{base}
git diff --stat <BUILT_FROM 의 SHA> origin/{base}
```

마지막 명령에 나온 파일은 그래프가 모르는 변경이다. 그 파일에 대한 그래프 답은 Read 로 다시 확인한다.
`BUILT_FROM` 이 없거나 그 SHA 가 이 클론에 없어 비교가 실패하면 그래프가 오래됐다고 보고, 그래프 답을 전부 Read 로 다시 확인한다.

## 4. 사전 탐색 (②)

영향 범위의 **첫 패스**를 그래프로 잡는다.

```bash
GRAPHIFY_OUT=~/graphs/<레포> graphify explain '<파일경로>::<심볼>'
GRAPHIFY_OUT=~/graphs/<레포> graphify query "<질문>" --budget 2000
GRAPHIFY_OUT=~/graphs/<레포> graphify explain <테이블명>
```

- `explain` 은 그 심볼을 누가 쓰는가, 어떤 테스트가 import 하는가를 보여 준다. 각 연결에 `파일:L라인` 근거가 붙는다.
- 넓은 질문은 `query "<질문>" --budget 2000` 으로 한다.
- 결과는 **출발점일 뿐이다.** 본문·로직 판단은 Read 나 `Explore` 로 한다. 그래프는 어디를 볼지를 줄이고, 무엇이 맞는지는 코드가 정한다.
- 코드와 문서에 같은 이름의 심볼이 있으면 `Ambiguous` 가 나온다. `<파일경로>::<심볼>` 로 다시 지정하고, 같은 파일 안에서도 겹치면(클래스와 생성자 등) 출력에 나온 node id 로 다시 지정한다.
- `explain`·`query` 의 종료 코드 `3`(이름이 여러 노드에 걸림)·`4`(매칭 없음)는 그래프 고장이 아니다. `3` 은 위처럼 다시 지정하고, `4` 는 그 심볼을 Read·`Explore` 로 확인한다 — 이번 작업에서 새로 쓰는 코드라 그래프에 없는 경우가 많다.
- 테이블 영향은 `explain <테이블명>` 으로 본다. `path` 는 쓰지 않는다 — 테스트의 import 를 지름길로 잡아 실제 호출 경로가 아닌 것을 최단 경로로 낸다.
- 그래프는 `BUILT_FROM` 시점 베이스의 스냅샷이다. **이번 작업에서 새로 쓰는 코드는 그래프에 없다.**
- 출력에는 상한이 있다(`explain` 기본 연결 20 개, `query --budget 2000`). 그래서 메인 세션이 직접 돌려도 "사전 탐색 위임 원칙" 이 막는 광범위 읽기가 아니다.

## 5. 이 파일이 다루지 않는 것

- ④ 이후 에이전트(`architect`·`implementer`·`spec-reviewer` 등)의 그래프 사용. ② 에서 얻은 결과는 기존처럼 ② 의 요약으로 넘어간다.
- 그래프를 만들거나 `~/graphs/tools/` 를 갖추는 방법.
````

### 5.5 [편집 5] `docs/harness-changelog.md` 표 최상단 행 (헤더 구분선 바로 다음)

````markdown
| 2026-10-28 | orchestrator ①② 에 선택 지식 그래프(`graphify`) 경로 추가 — `SKILL.md` 3 줄(선택 표 · ① · ②), 상세는 `references/knowledge-graph.md` 신설 | `.claude/skills/orchestrator/SKILL.md`, `.claude/skills/orchestrator/references/knowledge-graph.md` | 저장소 밖 개인 설치 그래프라 다수에게는 없다. 그래서 판정 실패는 조용히 기존 `Explore` 로 가고, 갱신은 공용 그래프에 피처 코드가 섞이지 않도록 새 worktree 직후·변경 0 일 때만 `origin/{base}` 기준으로 한다. 자매 저장소 aic-api 에 같은 변경을 먼저 넣었다(MR !239) |
````

## 6. 버린 대안

1. **`SKILL.md` 본문에 절 추가** — 이미 595 행이고 미설치자도 매번 읽는다.
2. **상대 표기 `references/knowledge-graph.md`** — 축 1 이 검사하지 않는다(A1).
3. **`refresh.sh` 에 로컬 `{base}` 전달** — 이 저장소 ① 은 로컬 `{base}` 를 갱신하지 않는다(A3).

## 7. 게이트가 보는 것과 못 보는 것

| 본다 | 못 본다 |
|---|---|
| references 전체 경로 3 곳의 실재(축 1) · `SKILL.md` frontmatter(축 2) · ⓪·⑤·⑪-d 필수 절(축 3) · `docs/harness-changelog.md` 헤더 | references 본문의 정확성 · 그래프 없는 환경에서의 행동 — dod AC 의 grep 과 사람 검토(T4)가 본다 |

## 8. 구현 순서

1. 편집 1·2·3 → `./gradlew harnessCheck` — **기대 exit 1**, 죽은 참조 3 건(53·215·226 행).
2. 편집 4 → `harnessCheck` exit 0.
3. 편집 5 → `harnessCheck`·`harnessCheckTest`·`bash docs/check-documentation.sh` exit 0.

## 9. 확인한 사실

- `SKILL.md` 595 행, 앵커 3 줄이 각각 52·213·223 행에 정확히 1 회(`grep -nxF`). 223 행은 ② 불릿 목록의 마지막이고 다음은 `## ③ 사용자 합의`.
- `./gradlew harnessCheck` 기준선 "하네스 검사 통과: 파일 22개".
- `refresh.sh` 둘째 인자 → `merge-base --is-ancestor HEAD "$BASE"`.
- 블록 1~3 은 aic-api 최종 문면과 바이트 동일, 블록 4 는 `{base}` → `origin/{base}` 다섯 곳만 다르다.
