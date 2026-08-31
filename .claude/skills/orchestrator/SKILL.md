---
name: orchestrator
description: "premium-spread 개발 파이프라인 오케스트레이터. 컨텍스트 확인 → worktree 격리 → 브레인스토밍 → spec/plan/DoD → 스펙 리뷰 → TDD 구현 → 병렬 코드 리뷰 → 검증·DoD 판정 → 문서 동기화 → 이해문서 → PR 까지 조율한다. '기능 추가', '새 도메인', '새 API', '배치 Job 추가', '버그 수정', '리팩터링'처럼 코드 변경을 만드는 작업 요청 시, 그리고 '재실행', '다시', '이어서', '보완', '업데이트'처럼 이미 진행했거나 완료한 작업을 손보는 요청 시 반드시 이 스킬을 사용할 것. 단순 질문·조회·단일 파일의 사소한 편집은 트리거하지 않는다."
---

# 오케스트레이터

## 이 스킬의 위치

premium-spread 전용 파이프라인이다. 단계 구성·승인 게이트·스킵 규칙·검증 명령·문서 경로를 **이 스킬이
단독으로 소유한다.** 각 단계는 `.ai/rules/*` 규칙 문서, `.claude/agents/*` 전문 에이전트, 형제 스킬,
이 저장소에 실재하는 Gradle 명령에 바인딩돼 있다.

## 전제 스킬

### 필수 — superpowers 플러그인

`using-git-worktrees`, `brainstorming`, `writing-plans`, `subagent-driven-development`,
`test-driven-development`, `systematic-debugging`, `dispatching-parallel-agents`,
`requesting-code-review`, `receiving-code-review`, `verification-before-completion`,
`finishing-a-development-branch`를 단계별로 호출한다. 미설치면 ①②④⑤⑧⑨⑩이 동작하지 않는다.

### 저장소 포함 — 별도 설치 불필요

| 자산 | 위치 | 단계 |
|------|------|------|
| `definition-of-done` | `.claude/skills/definition-of-done/` | ④⑦⑩ |
| `explain-pr` | `.claude/skills/explain-pr/` | ⑪-b |
| `module-layout`, `dto-pattern`, `jpa-entity-pattern`, `swagger-interface-pattern`, `test-strategy`, `qa-verification`, `tech-docs-sync`, `task-packet` | `.claude/skills/` | 각 단계 |
| `architect`, `implementer`, `spec-reviewer`, `code-reviewer`, `qa-agent`, `tech-docs` | `.claude/agents/` | 각 단계 |

`definition-of-done`과 `explain-pr`은 개인 환경 의존을 없애려고 **벤더링**한 사본이다. 원본과
byte-identical하게 유지하고, 원본이 갱신되면 통째로 다시 복사한다. **이 저장소 안에서 개별 수정하지
않는다.**

### 선택

| 스킬 | 단계 | 없을 때 |
|------|------|--------|
| `codex-spec-review` | ⑥ | Claude 경로(`spec-reviewer`)로 수행 — 기본값 |
| `codex-code-review` | ⑨ | Claude 경로(`code-reviewer`)로 수행 — 기본값 |
| `codex-subagent-driven-development` | ⑧ | `superpowers:subagent-driven-development` — 기본값 |
| `finalize` | ⑪-c | `.ai/rules/git.md` 규약대로 직접 커밋 + `gh pr create` |

**codex는 기본값이 아니다.** 사용자가 `--engine codex`처럼 명시하지 않으면 호출하지 않는다.

## 핵심 원칙

사용자가 명시적으로 스킵을 요청하지 않는 한 모든 단계를 수행한다.

**격리 원칙:** ① worktree 생성을 가장 먼저 하고, 이후 모든 산출물(spec·plan·dod·코드·이해문서)은
worktree 안에서만 만든다. 베이스 브랜치(`{base}`, 기본값 `dev`) 작업 트리는 끝까지 untouched로 둔다.

## 전체 흐름

```text
⓪ 컨텍스트 확인 → ① worktree → ② 브레인스토밍 → ③ 합의 → ④ design+dod → ⑤ plan
  → ⑥ 스펙 리뷰 → ⑦ ★승인 + DoD 동결 → ⑧ 구현 → ⑨ 병렬 리뷰 → ⑩ 검증 + DoD 판정
  → ⑪ 마무리(문서 → 이해문서 → PR → 피드백)
⑨ → ⑧ 재작업 (최대 2회) · ⑩ 실패 → ⑧ (systematic-debugging)
```

## 단계별 매핑

| 단계 | 스킬 | 에이전트 | 산출/게이트 |
|------|------|---------|-----------|
| ⓪ 컨텍스트 확인 | — | — | 초기/후속/부분/새 실행 판정 |
| ① worktree 격리 | `superpowers:using-git-worktrees` | — | 이후 전 작업의 위치 |
| ② 브레인스토밍 | `superpowers:brainstorming` | — | 스코프·접근 |
| ③ 합의 | AskUserQuestion | — | 요구 확정 |
| ④ design + dod | `superpowers:brainstorming`, `definition-of-done` | `architect` | `docs/work/{slug}/design.md`, `dod.md`(DRAFT) |
| ⑤ plan | `superpowers:writing-plans` | `architect` | `docs/work/{slug}/plan.md` |
| ⑥ 스펙 리뷰 | 기본 Claude, 명시 시 `codex-spec-review` | `spec-reviewer` | 계약 검증 |
| ⑦ 승인 + 동결 | AskUserQuestion, `definition-of-done` | — | **동결 전 구현 금지** |
| ⑧ 구현 | `superpowers:subagent-driven-development`, `superpowers:test-driven-development`, `test-strategy` | `implementer` | 코드 + 태스크별 커밋 |
| ⑧′ 디버깅 | `superpowers:systematic-debugging` | `implementer` | 원인 규명 |
| ⑨ 병렬 리뷰 | `superpowers:dispatching-parallel-agents`, `requesting-code-review`, `receiving-code-review`, `task-packet` | `spec-reviewer` ∥ `code-reviewer` | 계약 ∥ 버그 |
| ⑩ 검증 + 판정 | `superpowers:verification-before-completion`, `qa-verification`, `definition-of-done` | `qa-agent` | 실측 수치 + DoD 판정 |
| ⑪ 마무리 | `tech-docs-sync`, `explain-pr`, `finalize` | `tech-docs` | 문서·이해문서·PR |

## 옵션

| 옵션 | 의미 | 기본값 |
|------|------|--------|
| `--base <branch>` | 피처 브랜치를 따낼 기준 | `dev` |
| `--engine <codex\|>` | ⑧ 구현 엔진과 ⑥⑨ codex 리뷰 사용 여부 | 미지정 = 전부 Claude |
| `--skip <번호,...>` | 스킵 단계 (⓪①⑪ 불가) | 없음 |

## 스킵 규칙

- 기본값은 전 단계 수행이다. 스킵은 사용자가 명시할 때만.
- 스킵 불가: ⓪ 컨텍스트 확인, ① worktree 격리, ⑪ 마무리.
- 원인이 명확한 소규모 버그픽스는 ②③④⑤⑥⑦ 스킵 가능. 스펙이 이미 있으면 ②③④ 스킵(기존 문서 재사용).

## 모델 라우팅

| 단계 | 실행 | 모델 | 근거 |
|------|------|------|------|
| 사전 탐색 | `Explore` | Haiku | fan-out 검색, 결과만 회수 |
| ④⑤ 설계·계획 | `architect` | Opus | 지능을 앞단에 몰아 구현을 단순하게 |
| ⑧ 구현 | `implementer` | Sonnet | 정밀한 plan은 명세 실행 |
| ⑧ escalate | `implementer` | Opus | claim fencing·Redis 락 lease·WebSocket generation·premium 정확성·plan 모호 |
| ⑥⑨ 리뷰 | `spec-reviewer`, `code-reviewer` | Opus | 구현 미스를 잡는 쪽에 강한 모델 |
| ⑩ 검증 | `qa-agent` | Sonnet | 명령 실행과 결과 해석 |
| ⑪ 문서 | `tech-docs` | Sonnet | 판단이 필요한 갱신 |

---

# 단계별 상세

## ⓪ 컨텍스트 확인 (읽기 전용, ① 앞)

`docs/work/` 아래에 이 주제의 산출물이 있는지 본다. **읽기만 한다.**

| 관찰 | 판정 | 진입 |
|------|------|------|
| 해당 `docs/work/{slug}/` 없음 | 초기 실행 | ①부터 전 단계 |
| 있고 dod가 `DRAFT`이며 중단 흔적 | 후속 실행 | 기존 worktree로 이동 후 중단 단계부터 |
| 있고 사용자가 특정 산출물을 지적 | 부분 재실행 | 기존 worktree로 이동 후 해당 단계만 |
| 있고 이전 사이클 완료(dod `FROZEN`·판정 존재)인데 **새 요구**가 들어옴 | 새 실행 | **새 slug로 ①부터.** 이전 산출물은 git 이력에 남으므로 옮기지 않는다 |

네 번째 갈래를 지우지 말 것. 없으면 완료된 작업에 새 요구가 들어왔을 때 후속 실행으로 오분류되어
**동결된 dod 위에 새 요구가 얹힌다.**

**왜 ① 앞인가.** ①의 목적은 쓰기 격리다. ⓪은 `ls`·`cat`만 하므로 격리 대상이 아니다. 반대로 ⓪을 ① 뒤에
두면 후속 실행인데도 새 worktree와 새 브랜치를 먼저 만들어 갈라진 트리가 둘 생긴다.

## ① worktree 격리 (스킵 불가, 항상 최초)

**스킬:** `superpowers:using-git-worktrees`

브랜치는 `<type>/<short-description>` — type은 `feat|fix|refactor|docs|test|chore` 중 요청 유형으로
추론하고 모호하면 한 번 확인한다. 이슈 번호가 있으면 `<type>/issue-N-<주제>`.

```bash
git fetch origin
git worktree add -b {branch} .worktrees/{branch-dashed} origin/{base}
cd .worktrees/{branch-dashed}
```

- **`origin/{base}`에서 분기한다.** 로컬 `{base}`가 stale하면 `git pull --ff-only`가 막히거나(작업 트리
  변경) 낡은 기준으로 분기된다. 이후 ⑨⑩⑪의 diff 대상과 PR target도 모두 같은 `origin/{base}`를 쓴다.
- 베이스 작업 트리의 미커밋 변경은 건드리지 않는다. 임의 `--force`·reset 금지.
- worktree 제거는 ⑪ PR 머지 이후에만.

## ② 브레인스토밍

**스킬:** `superpowers:brainstorming` (worktree 안에서)

- 이 저장소는 **Controller→Facade 단일 진입 / 포트-어댑터 / MarketPair identity / DB-first**를 전제한다.
  새 요구가 이 전제와 충돌하면 여기서 드러내고 ③에서 합의한다.
- 내구성 있는 사후 반응이 필요하면 durable notification 경로(claim·fencing·재시도)를 설계에 넣을지
  여기서 판단한다. in-memory event나 `@Async`를 전달 보장으로 쓰지 않는다.
- 광범위한 코드 탐색은 `Explore`에 위임하고 요약만 받는다.

## ③ 사용자 합의

AskUserQuestion으로 확인한다. 합의 전 다음 단계로 가지 않는다.

## ④ design + DoD

**에이전트:** `architect` (Opus) · **스킬:** `superpowers:brainstorming` 스펙 작성 + `definition-of-done`

| 파일 | 내용 |
|------|------|
| `docs/work/{slug}/design.md` | API·엔티티·모듈 배치·예외 매핑·마이그레이션 계획 |
| `docs/work/{slug}/dod.md` | 수용기준 + 검증 티어 + 검증 명령, `status: DRAFT` |

두 스킬의 기본 경로를 모두 override 한다 (`docs/superpowers/specs/`·`docs/dod/` → `docs/work/{slug}/`).
`definition-of-done`은 벤더링 사본이므로 파일을 고치지 않고 **호출할 때 경로를 지정**한다. ⑦ 동결과 ⑩
판정도 같은 파일을 본다.

설계에 아래가 빠지면 미완으로 본다.

- [ ] Facade 이름과 주입 대상 (Controller는 Facade 하나)
- [ ] Domain port와 이를 구현할 infrastructure 모듈
- [ ] MarketPair identity 보존 경로
- [ ] 시간·범위 계약 (`Instant`, UTC 버킷 vs `aggregation.zone`, `[from,to)`)
- [ ] DB·캐시 순서 (DB-first 또는 after-commit)
- [ ] Flyway 필요 여부 — 위치 `infrastructure/common/src/main/resources/db/migration/`,
      번호는 `ls ... | sort -V | tail -1` +1
- [ ] 배치면 lock key·lease·timeout과 `JobResult` 분기
- [ ] 공개 endpoint 변경이면 `PublicEndpointPolicy`와 `docs/runbooks/auth-security.md` 갱신 계획

## ⑤ plan

**에이전트:** `architect` (Opus) · **스킬:** `superpowers:writing-plans`

- `docs/work/{slug}/plan.md`. 기본 경로를 override 한다.
- 태스크마다 파일 경로·작성 내용·실행 명령·예상 결과. placeholder 금지.
- 순서는 `test-strategy`의 TDD 순서: Domain 불변식 → 포트·Service → adapter → Criteria/Result/Facade →
  Controller 또는 Scheduler→Job → integration → `architectureTest`.

## ⑥ 스펙 리뷰

**기본(Claude):** `spec-reviewer` (Opus) + `superpowers:brainstorming`의 Spec Self-Review
**codex를 명시했을 때만:** `codex-spec-review`

`spec-reviewer`를 **fresh context**로 띄운다. 작성자와 같은 세션에서 리뷰하면 빠진 것을 못 본다.
대상은 design.md + plan.md 두 문서이며 전달은 `task-packet` 형식이다.

추가로 본다: placeholder 잔존, 문서 간 모순, 두 갈래로 해석되는 요구, plan 태스크의 구체성,
**dod의 검증 명령이 이 저장소에 실재하는 명령인지**(`lint` 태스크는 없다).

ACCEPT/REBUT 루프 후 문서를 갱신한다. 유지된 이슈가 사용자 합의 스코프를 바꾸면 ⑦에 명시 보고한다.

## ⑦ 사용자 리뷰 + DoD 동결

design·plan·dod를 함께 공유하고 승인받는다. 승인 시 dod에 `status: FROZEN`과 `frozen_at`을 적는다.
**동결 전에는 ⑧로 가지 않는다.** 이후 기준을 바꾸려면 구현을 멈추고 `## 변경 요청`에 적어 재승인받는다.
조용한 수정 금지.

## ⑧ 구현

**에이전트:** `implementer` (Sonnet 기본) · **스킬:** `superpowers:subagent-driven-development` +
`superpowers:test-driven-development`

- plan 태스크를 순서대로 실행하고 태스크마다 커밋한다.
- 각 태스크 직후 해당 모듈만 검증: `./gradlew :{module}:test --offline --no-daemon`
- Opus escalate: durable notification claim·fencing, Redis 락 owner token·lease, WebSocket generation
  fencing, premium 계산 정확성, plan 모호.

### ⑧′ 실패 시

**스킬:** `superpowers:systematic-debugging`. 추측으로 고치지 않고 원인을 규명한 뒤 ⑧로 돌아온다.

## ⑨ 병렬 코드 리뷰

**기본(Claude):** `superpowers:dispatching-parallel-agents` → `requesting-code-review` →
`receiving-code-review`. **codex를 명시했을 때만 추가:** `codex-code-review --scope branch --base {base}`

`spec-reviewer`(계약)와 `code-reviewer`(버그·회귀)를 **병렬**로 띄운다. 대상은 브랜치 전체 diff다.

```bash
git diff origin/{base}...HEAD
```

서로의 범위를 `task-packet`으로 공유해 중복 지적을 줄인다. 결과를 통합해 `implementer`에게 재작업을
요청하되 **최대 2회 순환**, 초과 시 사용자에게 올린다. 지적을 무비판 수용하지 않는다 — 기술적으로 틀린
지적은 근거를 들어 반박한다.

## ⑩ 검증 + DoD 판정

**에이전트:** `qa-agent` · **스킬:** `superpowers:verification-before-completion` + `qa-verification` +
`definition-of-done`

```bash
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
bash docs/check-documentation.sh
```

**`./gradlew lint`은 이 저장소에 없다.** 정적 경계는 `architectureTest`가 본다. 커버리지 게이트는
`./gradlew jacocoTestReport jacocoTestCoverageVerification`이며 최종 증거는 해당 SHA의 CI artifact다.

경계면 교차 검증(응답 shape↔DTO, Entity↔migration, `http/api/*.http`↔endpoint, 캐시↔runbook)을 함께
수행한다. Docker 부재 등으로 실행하지 못한 항목은 **green으로 기록하지 않는다.**

DoD 판정: 각 수용기준의 증거 로그를 채우고 고정 포맷으로 판정을 출력한다. `AWAITING_HUMAN`이 남으면
"완료"라고 쓰지 않는다. 그 뒤 AskUserQuestion으로 최종 승인을 받는다.

## ⑪ 마무리 (스킵 불가)

### ⑪-a 문서 동기화

**에이전트:** `tech-docs` · **스킬:** `tech-docs-sync`

변경 유형별 매핑표대로 갱신하고 `bash docs/check-documentation.sh`로 확인한다. 없는 경로(`.ai/diagrams/`)를
갱신했다고 보고하지 않는다. plan 체크박스를 `[x]`로, design에 구현 중 바뀐 사항을 반영한다.

### ⑪-b 개발자 이해문서

**스킬:** `explain-pr` → `docs/work/{slug}/understanding.md`

같은 세션에서 방금 구현했으므로 warm 문맥(왜 이 방향인지, 무엇을 버렸는지, 어디가 함정인지)을 담는다.

### ⑪-c finalize

**스킬:** `finalize` (없으면 아래를 직접 수행)

```bash
git push -u origin {branch}
gh pr create --base {base} --title "<커밋 제목과 동일>" --body-file docs/work/{slug}/pr-body.md
```

- 원격은 **GitHub**이다 (`gh`). MR이 아니라 **PR**이며 target은 `{base}`(기본 `dev`)다.
- 커밋은 `<type>: <subject>` + 한글 bullet 본문. **`Co-Authored-By: Claude`를 넣지 않는다**
  (`.ai/rules/git.md`).
- `main`·`dev`에 직접 커밋·push 하지 않는다.
- 이슈 컨텍스트로 시작했으면 PR 본문 최상단에 `Closes #N`을 넣는다. `Refs`·`Related` 같은 약한 표현으로
  대체하지 않는다. 미완 작업이 남았으면 close 키워드를 보류하고 Draft PR로 만든다.
- 본문에는 이해문서 요약과 `docs/work/{slug}/` 산출물 링크, Test plan의 **실측 수치**를 넣는다.
  지금 확인할 수 없는 항목은 체크를 풀어 둔 채 남긴다.

### ⑪-d 피드백 수집

PR 생성 후 **한 번** 묻는다: 이번 파이프라인에서 어색했거나 반복해서 지적하게 된 것이 있는가.
없으면 그대로 끝낸다. 재차 묻지 않는다.

| 피드백 성격 | 반영 대상 |
|---|---|
| 특정 역할의 판단 기준·체크 축 | `.claude/agents/{agent}.md` |
| 반복되는 작성 패턴·코드 규칙 | `.claude/skills/{skill}/SKILL.md` |
| 단계 순서·승인 게이트·스킵 규칙 | `.claude/skills/orchestrator/SKILL.md` |
| 프로젝트 전역 계약 | `CLAUDE.md` + 해당 `.ai/rules/` 문서 |

**이번 PR 스코프에 흡수하지 않는다.** dod는 ⑦에서 동결됐다. 여기서 나온 피드백을 그 자리에서 반영하면
동결된 계약 밖 변경이 같은 PR에 조용히 들어간다. 등록만 하고 별도 작업으로 연다.

반영을 수행할 때는 `CLAUDE.md ## 하네스`의 **변경 이력**에 `날짜 · 변경 내용 · 대상 · 사유` 한 행을 남긴다.

## 하네스 정합성은 사람이 지킨다

이 저장소에는 하네스 파일(`.claude/**/*.md`)의 죽은 경로·frontmatter 파손·단계 순서를 검사하는 자동
게이트가 **없다.** `docs/check-documentation.sh`는 정본 문서(`AGENTS.md`·`.ai/*`·`docs/runbooks/*`)만
본다. 그래서 하네스 파일을 고쳤으면 다음 두 가지를 직접 한다.

1. 문서가 가리키는 경로가 실재하는지 확인한다.
2. `CLAUDE.md ## 하네스` 변경 이력에 한 행을 남긴다.

## 데이터 전달

| 범위 | 방식 |
|------|------|
| 단계 간 영구 산출물 | `docs/work/{slug}/` (design·plan·dod·understanding) |
| 단계 간 임시 산출물 | `_workspace/` (gitignored, 필요할 때만 생성) |
| 에이전트 간 요청·결과·재작업 | `task-packet` 포맷 |

## 에러 핸들링

| 상황 | 대처 |
|------|------|
| 에이전트가 1회 재시도 후에도 실패 | 단계를 조용히 생략하지 않는다. 사용자에게 보고하고 진행 여부를 확인한다 |
| ⑨ 리뷰 순환 2회 초과 | 사용자 에스컬레이션 |
| 빌드·테스트 실패 | `superpowers:systematic-debugging`으로 원인 규명 후 ⑧ 복귀 |
| ⑩ 미통과 상태로 ⑪ 요청 | Draft PR로 만들고 `Closes` 보류 |

## Red Flags

| 상황 | 대처 |
|------|------|
| ① 없이 ②부터 진행 | worktree 먼저. `dev` 트리에 문서가 생기는 것을 막는 것이 ①의 목적 |
| `dev` 작업 트리에 spec/plan이 생성됨 | 즉시 worktree로 옮기고 첫 커밋에 포함 |
| `dev`·`main`에 직접 커밋·push | 절대 금지 (`.ai/rules/git.md`) |
| 로컬 `dev`를 기준으로 분기·diff | `origin/dev`를 쓴다. 로컬이 stale하면 판정이 통째로 어긋난다 |
| DoD 동결 없이 ⑧ 진행 | ⑦ 승인 후 진행 |
| ③⑦⑩ 승인 없이 다음 단계 | 승인 게이트다 |
| 앱 안에 `infrastructure`/`cache`/`repository`/`client` 패키지 생성 | `.ai/rules/architecture.md` 위반 |
| 이미 적용된 Flyway migration 수정 | append-only 위반 |
| non-default MarketPair를 symbol-only로 fallback | MarketPair identity 위반 |
| 손상 row가 있는데 부분 캐시 결과 반환 | persistence 계약 위반 |
| 커밋에 `Co-Authored-By: Claude` 포함 | `.ai/rules/git.md` 위반 |
| PR 머지 전 worktree 삭제 | 머지·원격 브랜치 정리 후에만 |
| ⑪ 없이 작업 종료 | ⑪은 스킵 불가 |

## 테스트 시나리오

- **정상:** "알림 구독 해제 API 추가" → ⓪ 초기 판정 → ① worktree → ②③ 합의 → ④⑤ 문서 → ⑥ 리뷰 →
  ⑦ 동결 → ⑧ TDD 구현 → ⑨ 병렬 리뷰 → ⑩ 검증·판정 → ⑪ 문서·이해문서·PR
- **후속:** "그 API 응답에 pair 추가하고 다시" → ⓪이 기존 `docs/work/{slug}/` 감지 → 부분 재실행 →
  기존 worktree에서 ④부터
- **새 요구:** 이전 사이클이 끝난 뒤 "이번엔 구독 목록 조회도" → ⓪이 네 번째 갈래로 판정 → 새 slug로 ①부터
