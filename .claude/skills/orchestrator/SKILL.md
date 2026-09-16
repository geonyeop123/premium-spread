---
name: orchestrator
description: "premium-spread 개발 파이프라인 오케스트레이터. 컨텍스트 확인 → worktree 격리 → 브레인스토밍 → spec/plan/DoD → 스펙 리뷰 → TDD 구현 → 병렬 코드 리뷰 → 검증·DoD 판정 → 문서 동기화 → 이해문서 → PR 까지 조율한다. '기능 추가', '새 도메인', '새 API', '배치 Job 추가', '버그 수정', '리팩터링'처럼 코드 변경을 만드는 작업 요청 시, 그리고 '재실행', '다시', '이어서', '보완', '업데이트'처럼 이미 진행했거나 완료한 작업을 손보는 요청 시 반드시 이 스킬을 사용할 것. 단순 질문·조회·단일 파일의 사소한 편집은 트리거하지 않는다."
---

# 오케스트레이터

## 이 스킬의 위치

premium-spread 전용 파이프라인이다. 단계 구성·승인 게이트·스킵 규칙·검증 명령·문서 경로를 **이 스킬이
단독으로 소유한다.** 각 단계는 `.ai/rules/*` 규칙 문서, `.claude/agents/*` 전문 에이전트, 형제 스킬,
이 저장소에 실재하는 Gradle 명령에 바인딩돼 있다.

**같은 저장소의 형제 스킬은 소유 범위가 다르다.** 이 스킬은 단계 구성·승인 게이트·스킵 규칙을
소유하고, `dto-pattern`·`test-strategy`처럼 각 단계에 박힌 형제 스킬은 각자 자기 패턴을 소유한다.
이 스킬을 바꿀 때 형제 스킬을 함께 고칠 의무는 **단계 표가 지목하는 이름이 바뀔 때만** 생긴다.

**`feature-workflow`와 척추를 공유한다는 계약은 두지 않는다.** 그 스킬은 개인 플러그인 저장소
(`claude-toolkit`의 `dev-workflow`)에 있어 이 저장소의 PR이 닿을 수 없다. 닿을 수 없는 대상에 대한
동기화 의무는 지켜지지 않은 채 문서에만 남고 두 스킬은 어차피 갈린다. 코드 변경 작업의 진입점은
`CLAUDE.md ## 하네스`가 지정한 대로 **이 스킬**이다.

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
| `--design-model <fable\|opus>` | ④⑤ `architect` 호출의 Agent `model` 인자에 명시할 모델. "설계는 Fable로" 같은 자연어 지시도 같은 뜻이다 | 미지정 = frontmatter의 Opus. **`model` 인자를 넘기지 않는다** |

## 스킵 규칙

- 기본값은 전 단계 수행이다. 스킵은 사용자가 `--skip 2,6` 또는 "브레인스토밍은 건너뛰자"처럼 명시할 때만.
- 스킵 불가: ⓪ 컨텍스트 확인, ① worktree 격리, ⑪ 마무리.

| 시나리오 | 권장 스킵 |
|---------|----------|
| 원인이 명확한 소규모 버그픽스 | ②③④⑤⑥⑦ |
| 의미를 바꾸지 않는 하네스 파일 소규모 수정 (오타·죽은 링크·문장 다듬기) | ②③④⑤⑥⑦ — 단계·게이트·규칙의 **의미가 바뀌면 이 행이 아니다.** ⓪①⑪과 §"하네스 정합성은 사람이 지킨다"의 두 항목은 그대로 강제된다 |
| 스펙이 이미 존재 | ②③④ (기존 문서를 worktree로 옮겨 진행) |
| codex 사용 | ⑥⑨에 codex 리뷰를 **추가**로 붙임 (기본은 Claude, 스킵이 아니라 추가) |

## 모델 라우팅

| 단계 | 실행 | 모델 | 근거 |
|------|------|------|------|
| 사전 탐색 | `Explore` | Haiku | fan-out 검색, 결과만 회수 |
| ④⑤ 설계·계획 | `architect` | Opus 기본 · `--design-model fable` 시 **Fable** | 지능을 앞단에 몰아 구현을 단순하게. 보안·동시성·다중 앱처럼 결정이 무거운 설계에서만 올린다 |
| ⑧ 구현 | `implementer` | Sonnet | 정밀한 plan은 명세 실행 |
| ⑧ escalate | `implementer` | Opus | claim fencing·Redis 락 lease·WebSocket generation·premium 정확성·plan 모호 |
| ⑥⑨ 리뷰 | `spec-reviewer`, `code-reviewer` | Opus | 구현 미스를 잡는 쪽에 강한 모델 |
| ⑩ 검증 | `qa-agent` | Sonnet | 명령 실행과 결과 해석 |
| ⑪ 문서 | `tech-docs` | Sonnet | 판단이 필요한 갱신 |

**모델은 에이전트 frontmatter(`.claude/agents/*.md`의 `model:`)가 결정한다.** Agent 호출의 `model`
인자는 frontmatter를 **덮어쓰므로**, `--design-model`이나 그에 해당하는 사용자 지시가 없으면 인자를
넘기지 않는다 — 위 표의 "Opus"를 보고 `model: opus`를 명시하면 frontmatter를 바꿔도 반영되지 않는
상태가 된다. `--design-model`은 ④⑤의 `architect` 호출에만 적용되며 다른 에이전트는 바꾸지 않는다.

**사전 탐색 위임 원칙:** 코드베이스를 메인 세션에서 광범위하게 읽지 말고 `Explore`에 위임해 distilled
결과만 받는다(context rot 회피). 원인이 명확한 소규모 버그픽스는 예외.

---

# 단계별 상세

## ⓪ 컨텍스트 확인 (읽기 전용, ① 앞)

`docs/work/` 아래에 이 주제의 산출물이 있는지 본다. **읽기만 한다.**

| 관찰 | 판정 | 진입 |
|------|------|------|
| 이 주제의 `docs/work/{slug}/` 없음 | **초기 실행** | ①부터 전 단계 |
| 있고, 요구가 **기존 합의·계약 범위 안**이며 사용자가 특정 산출물·부분을 **지적하지 않음** — 3·4행에 해당하지 않는 나머지 전부 | **후속 실행** | 기존 worktree로 이동 후 **중단된 단계부터.** dod가 `FROZEN`이어도 구현이 끝나지 않았으면 이 행이다 — 동결 계약은 그대로 두고 구현만 이어서 한다. 중단된 단계가 없으면 수행할 것이 없다는 사실을 보고하고 멈춘다 |
| 있고, 요구가 **범위 안**이며 사용자가 특정 산출물·부분을 **지적** | **부분 재실행** | 기존 worktree로 이동 후 **해당 단계부터.** 사용자가 지목한 지점이 진입점이며 **2행보다 우선한다** — 그 단계를 마친 뒤 **남은 중단 단계를 이어서 수행한다** |
| 있고, 요구가 **기존 합의·계약 범위 밖**(= 새 요구다. 통상 dod `FROZEN`이거나 판정이 존재) | **새 실행** | **새 slug로 ①부터.** 이전 산출물은 git 이력에 남으므로 옮기거나 백업하지 않는다 |

후속·부분 재실행이면 ①은 "새 worktree 생성"이 아니라 **"기존 worktree로 이동"** 으로 대체된다.
브랜치와 `{base}`는 이미 정해져 있으므로 다시 묻지 않는다.

**표 읽는 법 (지우지 말 것).** 판별은 두 축으로 끝난다 — ① 산출물 디렉터리가 있는가, ② 요구가 기존
합의·계약 범위 안인가. 범위 밖이면 4행, 범위 안인데 사용자가 특정 지점을 지목했으면 3행, 그 나머지
전부가 2행이다. **"새 요구"는 별도 축이 아니라 "범위 밖"의 다른 말이다** — 제3의 축으로 쓰면 축 조합에
구멍이 생긴다. 2·3·4행은 디렉터리가 있는 경우의 입력 공간을 **빠짐없이, 겹침 없이** 나눈다.

**네 번째 갈래를 지우지 말 것.** 없으면 완료된 작업에 새 요구가 들어왔을 때 "후속 실행"으로 오분류되어
**동결된 dod 위에 새 요구가 얹힌다.** 그리고 그 상황은 드문 예외가 아니다 — description에 넣은 후속
키워드 `보완`·`업데이트`·`수정`이 **가리키는 상황에 이 갈래도 포함된다.** 다만 **어느 갈래인지는
키워드가 아니라 위 범위 축으로 판별한다** — 같은 `보완`이라도 범위 안이면 2행이거나 3행이다.

**범위의 기준은 ③ 합의 또는 ⑦ 동결로 확정된 것이다 (지우지 말 것).** 그 앞에서 중단된 산출물에는
확정된 범위가 아직 없으므로, 새로 들어온 디테일을 범위 밖으로 보지 않는다 — 중단된 단계부터 재개하며
그 자리에서 흡수한다(지목이 없으면 2행, 있으면 3행). 이 문단이 없으면 ② 브레인스토밍 중 멈춘 작업에
"계속하되 X도 반영해줘"가 왔을 때 X가 문면상 범위 밖이 되어 4행으로 튕기고, **합의 직전까지 간
worktree와 브랜치가 폐기된다.**

**③·⑦이 스킵된 실행은 사용자 원 요청의 범위가 확정 범위 역할을 한다 (지우지 말 것).** 진행 중이든 이미
끝났든 같다. 스킵 규칙 표가 ②③④⑤⑥⑦을 건너뛰는 경로를 허용하므로 그 실행에는 합의도 동결도 영구히
없는데, ⑪은 스킵할 수 없어 `docs/work/{slug}/`는 만들어진다. 이 문장이 없으면 그 디렉터리에 새 요구가
왔을 때 **확정 범위가 없다는 이유로 2행에 갇혀** "중단된 단계가 없으니 수행할 것이 없다"로 정당한 요구를
버린다. 원 요청 밖이면 평소대로 4행이다.

**3행이 2행보다 우선하는 이유.** 사용자가 특정 산출물을 지목했다는 것은 "지금 그 지점이 문제다"라는
관찰이다. 중단 지점부터 순서대로 재개하면 사용자가 이미 틀렸다고 말한 산출물 위에 다음 단계를 얹는다.
그래서 지목이 이긴다. 다만 지목된 단계만 처리하고 끝내면 중단된 나머지가 반쪽으로 남으므로, 그 단계를
마친 뒤 남은 중단 단계를 이어서 수행한다.

**2행이 `FROZEN`을 포함하는 이유.** 동결은 계약을 고정하는 것이고 구현 완료와 무관하다. `FROZEN`인데
⑧이 끝나지 않은 상태는 정상이며, 그때 "이어서 해줘"는 범위 안 요구다. 2행이 dod `DRAFT`만 받으면 이
상태가 어느 행에도 걸리지 않아 4행으로 튕기고 **이미 동결된 계약과 진행된 구현이 새 slug로 버려진다.**

**요구가 여러 개면 요구 단위로 분해해 각각 판정한다.** 한 메시지에 범위 안 지목 수정과 범위 밖 새 요구가
함께 오면 요청 전체를 한 행에 밀어넣지 않는다 — 범위 밖 단위는 새 slug(4행), 범위 안 지목 단위는 기존
worktree의 3행이다. 분해하지 않으면 4행이 요청 전체를 끌고 가 기존 수정까지 새 slug로 옮긴다.

**다만 범위 밖 단위가 여럿이고 서로 같은 파일 셋을 만지면 한 slug로 묶을 수 있다.** 분해의 목적은 판정을
가능하게 하는 것이지 파이프라인 수를 늘리는 것이 아니다. 같은 표의 인접 행이나 같은 절을 고치는 두
단위를 따로 돌리면 두 PR이 충돌하고, 뒤에 머지되는 쪽이 rebase로 상대 행을 건드린다. **묶을지는 사용자
결정 사안이다** — 임의로 정하지 않고 겹치는 파일을 근거로 제시해 묻는다.

**분해한 단위를 동시에 진행하면 순서와 의존을 먼저 정한다.** 4행 단위의 새 worktree는 `origin/{base}`에서
갈라지므로 3행 단위의 진행 중 변경을 담지 않는다. **파일이 겹치면 직렬로 돌린다** — 기존 worktree의
단위를 먼저 끝내 머지하고, 새 slug 단위가 그 결과를 기준으로 rebase한 뒤 시작한다. **겹치지 않으면
병렬로 두고 rebase도 필요 없다.**

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
- `superpowers:subagent-driven-development`가 소비할 태스크 단위로 분해한다.
- 태스크마다 **파일 경로 / 코드 계약 / 실행 명령 / 예상 결과**. placeholder 금지. '코드 계약'의 밀도 —
  무엇을 전문으로 쓰고 무엇을 요건 목록까지만 쓰는가 — 는 아래 §"⑤ 코드 계약의 밀도"가 정한다.
- 순서는 `test-strategy`의 TDD 순서: Domain 불변식 → 포트·Service → adapter → Criteria/Result/Facade →
  Controller 또는 Scheduler→Job → integration → `architectureTest`.
- **세 산출물이 갖춰지면 초안 커밋을 남긴다.** dod는 `status: DRAFT`인 채로 커밋한다.

  ```bash
  git add docs/work/{slug}/design.md docs/work/{slug}/plan.md docs/work/{slug}/dod.md
  git commit -m "docs: {주제} 설계·계획·DoD 초안 추가"
  ```

  이 커밋이 없으면 ⑦의 동결 커밋이 dod.md **신규 파일 전체**를 담아 단독 커밋 조건을 만족하지 못하고,
  **`DRAFT → FROZEN` 전이 자체가 히스토리에 남지 않는다.** 덧붙여 design·plan이 ⑥~⑩ 내내 untracked로
  남아 worktree 사고에 그대로 유실된다.

### ⑤ 코드 계약의 밀도 — 전문 칸과 요건 칸

`plan.md`는 **태스크 간 결합점**을 전문으로 쓰고 나머지는 요건 목록까지만 쓴다. 기준은 "종이에서
리뷰할 수 있는가"다 — 이웃 태스크가 참조하는 이름·타입·값은 전문이어야 ⑥이 문서만으로 대조할 수 있고,
구현부는 전문을 써도 ⑥이 종이에서 검증할 수 없다.

| 전문으로 쓴다 (태스크 간 결합점 · 종이에서 리뷰 가능) | 요건 목록까지만 쓴다 |
|---|---|
| Domain port·Service·Facade·Controller·Job **시그니처** | 메서드 **구현부** |
| DTO **프로퍼티 전체**(타입 + 이름) — `*Request`·`*Response`·`*Criteria`·`*Result`·`*Command`·`*Snapshot` | **테스트 본문** |
| 예외·enum **값 전부**, 예외 → HTTP 상태 코드 매핑 | 로깅 **문구**·주석 — 로그가 담을 **키와 담지 말아야 할 값**은 "값 전부"로서 왼쪽 칸이다 |
| 상태 전이표 · `JobResult` 분기(success/skipped/failure) | |
| 컬럼 물리명·타입, Flyway DDL **전문** (`infrastructure/common/src/main/resources/db/migration/`) | |
| Redis **key 패턴·TTL·payload 스키마** — `modules:redis`와 `docs/runbooks/redis-contract.md`의 결합점이다 | |
| **`data class`의 자동 생성 `toString()`이 노출하는 프로퍼티** | |

**마지막 두 행이 보정 항목이다 — 구현부인데 전문 칸에 있다.** 호출자가 우리 코드 밖이라 시그니처만
봐서는 종이에서 보이지 않기 때문이다. Kotlin `data class`는 `toString()`을 자동 생성하므로 Request/
Criteria에 토큰·이메일·API key가 들어가면 **아무도 그렇게 쓰지 않았는데** Spring의 요청 로깅과 예외
메시지에 전문이 찍힌다. 마스킹이 있는 쪽과 없는 쪽의 비대칭은 프로퍼티 목록을 나란히 놓아야 드러난다.
Redis 계약도 같다 — key/TTL/payload는 `modules:redis`·runbook·어댑터 셋이 동시에 지켜야 하는 값이라
한 곳에서만 바뀌면 조용히 갈린다(`.ai/rules/architecture.md`).

**`superpowers:writing-plans`의 "No Placeholders" 여섯 항목 중 둘을 의도적으로 완화한다 — 숨기지
않는다.** `"Write tests for the above" (without actual test code)`와 `Steps that describe what to do
without showing how`의 **주절**이다. 위 표가 테스트 본문과 메서드 구현부를 요건 칸에 두므로 둘 다
정면으로 부딪힌다. 완화하는 이유는 **이 저장소에 테스트 요건의 소유자가 따로 있기** 때문이다 —
태스크 구분과 격리 규칙은 `.ai/rules/testing.md`, 작성법은 `.claude/skills/test-strategy/SKILL.md`,
RED 순서는 ⑧의 `superpowers:test-driven-development`가 소유한다. 계획서가 본문까지 옮기면 같은 계약의
네 번째 사본이 생긴다. **대신 계획은 테스트가 "무엇을 재는가"(태스크 · 대상 · 기대 상태 코드 · 경계값 ·
고정 `Clock`과 zone)를 요건으로 확정한다** — 그것까지 비면 완화가 아니라 placeholder다.

**항목 5의 괄호(`code blocks required for code steps`)는 완화하지 않는다.** 전문 칸의 시그니처·DTO
프로퍼티·값·DDL이 그 "code block"이므로 **코드 단계마다 블록이 있다** — 그것마저 없으면 완화가 아니라
위반이다. 완화한 것은 그 블록이 알고리즘까지 보여주지는 않는다는 것뿐이다.

**나머지 넷은 그대로 지킨다.** 특히 `References to types, functions, or methods not defined in any
task`가 이 밀도의 경계선이다 — 요건 목록은 타입·이름·시그니처·값을 전부 확정한 뒤 알고리즘 전문만
생략한 것이므로 걸리지 않고, 이웃 태스크가 참조할 이름이 어느 태스크에도 없다면 그것은 밀도가 아니라
placeholder 위반이다. "적절한 예외 처리를 추가" 류가 금지인 것도 그대로다.

**이 표를 `architect`에게 전달하는 것은 오케스트레이터의 몫이다.** ④⑤로 `architect`를 호출할 때 이 표와
보정 항목을 프롬프트에 그대로 싣는다. `.claude/agents/architect.md`는 밀도를 소유하지 않으므로 싣지
않으면 그쪽에는 기준이 없다. ⑥ 절차가 `spec-reviewer`에 대해 요구하는 것과 한 쌍이며, 두 경우 모두
**전달을 빠뜨려도 게이트는 초록이다** — 이 저장소에는 하네스 자동 게이트가 없으므로 잡는 것은 다음
실행에서 나온 `plan.md`를 사람이 보는 것뿐이다.

**이 밀도는 실측에서 왔다 — 되돌리기 전에 읽는다.** 근거는 자매 저장소 `aic-api`의 세 표본이다.
코드 전문(4,056행)·계약만(1,539행)·계약+보정(1,296행) 순으로 갈수록 짧아졌는데 ⑥의 설계 지적은 줄지
않았고, 범위가 가장 큰 세 번째 계획이 가장 짧았다. **이 밀도는 ⑥의 §"⑥ 전용 체크 축"과 한 쌍이다** —
계획이 짧아진 만큼 계획의 단언 하나가 구현·문서 여러 곳으로 퍼지며, 그것을 잡는 것은 밀도가 아니라
그 축이다. 한쪽만 되돌리지 않는다.

## ⑥ 스펙 리뷰

**기본(Claude):** `spec-reviewer` (Opus) + `superpowers:brainstorming`의 Spec Self-Review
**codex를 명시했을 때만:** `codex-spec-review`

`spec-reviewer`를 **fresh context**로 띄운다. 작성자와 같은 세션에서 리뷰하면 빠진 것을 못 본다.
대상은 design.md + plan.md 두 문서이며 전달은 `task-packet` 형식이다.

추가로 본다: placeholder 잔존, 문서 간 모순, 두 갈래로 해석되는 요구, plan 태스크의 구체성,
**dod의 검증 명령이 이 저장소에 실재하는 명령인지**(`lint` 태스크는 없다).

추가로 아래 §"⑥ 전용 체크 축"을 적용한다. 이 축은 `.claude/agents/spec-reviewer.md`에 없다 —
`spec-reviewer`를 호출하는 `task-packet`의 Constraints에 이 축의 표를 그대로 넣어 전달한다. 넣지 않으면
이 축은 문서에만 있고 실행되지 않는다. codex 경로를 함께 돌릴 때도 같은 표를 넘긴다.

ACCEPT/REBUT 루프 후 문서를 갱신한다. 유지된 이슈가 사용자 합의 스코프를 바꾸면 ⑦에 명시 보고한다.

### ⑥ 전용 체크 축 — 계획의 단언 ↔ 기존 코드

`spec-reviewer`의 체크 축은 **규칙 준수**를 보고, 위 추가 항목은 **문서 내부 정합**을 본다. 둘 다
**계획이 기존 코드에 대해 하는 주장이 참인지**는 보지 않는다. 이 축이 그것을 본다. ⑨에 넣지 않는 이유는
⑨ 시점의 대조 대상이 계획이 아니라 코드라 의미가 흐려지기 때문이다.

> **계획이 "이렇게 동작한다"고 단언하는 것 중 기존 코드가 이미 정한 것은 그 코드를 열어 대조한다.**
> 특히 **계획이 "문서·http 샘플·KDoc에 반드시 담으라"고 지시하는 문구**는 전부 대조 대상이다 — 그것이
> 틀리면 구현자가 지시대로 넣고 태스크 리뷰가 통과시킨다.

| 대조 대상 (계획의 단언) | 여는 원본 |
|---|---|
| 응답 필드가 **언제 null인가** / 무엇이 응답에 **포함되는가** | 기존 Entity의 필드 대입, 기존 `*Result`·`*Response`·`*Snapshot`의 `from` |
| 기존 도메인 메서드의 **검증 순서와 던지는 예외** | 그 메서드 본문과 기존 단위 테스트 이름 |
| 기존 **제약·인덱스가 무엇을 강제하는가** | 기존 `V*__*.sql` (append-only이므로 과거 버전도 유효한 원본이다) |
| 기존 **상태 전이와 진입 조건** | Entity 전이 메서드와 그 테스트 |
| 기존 **Redis key·TTL·payload**와 캐시/DB 우선순위 | `modules:redis`, `docs/runbooks/redis-contract.md`, 해당 어댑터 |
| 기존 **공개 endpoint 여부** (method+path 조합) | `PublicEndpointPolicy`와 그 contract test |
| 기존 **시간 계약** (UTC 버킷 vs `aggregation.zone`, `[from,to)`) | 해당 집계·조회 코드와 그 테스트 |
| 계획이 **"문서·http 샘플에 반드시 담으라"**고 지시하는 문구 전부 | 그 문구가 서술하는 위 원본 중 해당하는 것 |

**범위 한정 — 이 축은 무한히 늘지 않는다.** 대조 대상은 계획이 **기존 코드**에 대해 하는 단언이다.
**새로 만드는 것에 대한 서술은 대조할 원본이 없다** — 그것은 ⑨가 구현과 대조한다. 계획이 기존 코드를
**바꾸는 태스크**를 두고 그 바뀐 뒤의 동작을 서술하면 그것도 대조 대상이 아니다. 바꾸는 태스크가 없는데
코드와 다르게 단언할 때만 위반이다.

**원본의 우선순위는 코드 → 그 코드의 기존 테스트 → 같은 브랜치의 design.md다.** design.md와 plan.md가
갈리면 문서 내부 모순이고, 코드와 plan.md가 갈리면 이 축이다.

**보고 형식.** `plan.md:행` ↔ `코드 파일:행` 쌍과, 어느 쪽이 맞는지의 근거(기존 테스트 이름)를 적는다.
계획이 틀렸으면 계획을 고치고, 코드가 같은 브랜치 design.md의 의도와 어긋나면 그것은 계획 오류가 아니라
코드 결함이므로 ⑦에 별도 보고한다.

**왜 이 축이 필요한가.** 자매 저장소 `aic-api`에서 계획이 "문서에 반드시 담을 문구"로 지시한 한 문장이
실제 도메인 메서드의 동작과 **정반대**였던 사건이 근거다. 구현자는 지시대로 넣었고(구현자 잘못이 아니다),
태스크 단위 리뷰는 "브리프대로 했는가"만 보므로 통과시켰으며, 브랜치 전체를 보는 ⑨에서야 잡혀 문서·주석·
테스트 **아홉 곳**을 정정했다. 계획을 코드 전문으로 써도 잡히지 않는다 — 전문도 같은 오해에서 나온다.
이것은 밀도의 문제가 아니라 "계획의 단언을 누가 기존 코드와 대조하는가"의 문제이고, 그 자리는 계획을
처음 읽는 ⑥이다. **이 축을 지우면 §"⑤ 코드 계약의 밀도"를 함께 되돌려야 한다.**

## ⑦ 사용자 리뷰 + DoD 동결

design·plan·dod를 함께 공유하고 AskUserQuestion으로 승인받는다. 승인 시 dod에 `status: FROZEN`과
`frozen_at`을 적는다.

**동결 마커만 담은 커밋을 즉시 만든다.** 코드·문서 변경과 섞지 않는다.

```bash
git add docs/work/{slug}/dod.md
git commit -m "docs: DoD 계약서 동결"
```

git 히스토리만으로 "동결이 구현보다 먼저였다"가 증명돼야 동결 게이트의 감사 가치가 남는다. 첫 구현
커밋에 함께 실으면 기준 본문이 바이트 동일이어도 순서를 증명할 수 없다. **선행 조건:** dod.md가 이미
추적 중이어야 이 커밋의 diff가 `status`·`frozen_at` 두 줄이 된다. 아직 추적되지 않았으면 ⑤의 초안
커밋을 먼저 만든다.

**동결 전에는 ⑧로 가지 않는다.** 이후 기준을 바꾸려면 구현을 멈추고 `## 변경 요청`에 적어 재승인받는다.
조용한 수정 금지.

**`## 변경 요청` 개정도 같은 규칙이다.** 개정만 담은 단독 커밋을 재작업 커밋보다 **앞에** 넣는다. 순서가
뒤집히면 "재작업이 개정에 맞춘 것"이 아니라 "개정이 재작업을 사후 정당화한 것"으로 읽힌다.

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

서로의 범위를 `task-packet`으로 공유해 중복 지적을 줄인다.

**판정이 갈릴 때.** 같은 대상에 두 리뷰어가 반대 방향을 낼 수 있다. **코드가 이미 동결된 dod 기준을
충족하고 있고 지적대로 고치면 그 기준이 깨지는 경우, `spec-reviewer` 판정을 우선한다.** 그때 물을 것은
"코드가 틀렸다"가 아니라 "계약을 고칠 것인가"이고, 그 결정은 사용자 승인 사안이다. 코드를 먼저 고치면
이번 PR의 판정 근거가 사라진다. 순서는 **사용자 승인 → 계약 개정 단독 커밋 → 재작업**이며 뒤집지
않는다(⑦ 참조).

**발동 조건을 "기준이 하나라도 걸린 지적"으로 넓히지 않는다.** 변경 파일을 재는 기준은 대개 하나쯤
있으므로, 넓히면 거의 모든 지적이 계약 논쟁으로 넘어가 `code-reviewer` 축이 접힌다.

**기준이 걸렸지만 코드가 그 기준을 위반한 지적은 계약 논쟁이 아니라 코드 수정이다.** 위 우선 규칙은
코드가 기준을 **충족한** 상태를 전제하므로 이 구간에 닿지 않는다. 계약은 그대로 두고 코드를 고친다 —
여기서 계약 개정을 꺼내면 기준을 구현에 맞춰 낮추는 것이 된다.

동결 기준이 걸리지 않은 지적(버그·회귀·성능·가독성)은 `code-reviewer` 판정을 따른다.

결과를 통합해 `implementer`에게 재작업을 요청하되 **최대 2회 순환**, 초과 시 사용자에게 올린다.
`superpowers:receiving-code-review`에 따라 지적을 무비판 수용하지 않는다 — 기술적으로 틀린 지적은 근거를
들어 반박하고, 반박이 받아들여지면 그대로 둔다.

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
| 동결 마커가 첫 구현 커밋에 섞임 | 단독 커밋이어야 순서가 증명된다 (⑦) |
| `FROZEN`인데 구현 미완인 작업을 "새 실행"으로 판정 | ⓪ 2행이다. 동결 계약을 두고 구현만 이어서 한다 |
| 한 메시지의 범위 안 수정과 범위 밖 새 요구를 한 slug로 처리 | ⓪에서 요구 단위로 분해해 각각 판정한다 |
| `architect` 호출에 ⑤ 밀도 표를 싣지 않음 | 싣지 않으면 밀도 기준이 없다. 게이트가 잡아주지 않는다 |
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
