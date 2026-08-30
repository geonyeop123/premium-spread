# 하네스 aic-api 정합 재구성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** premium-spread의 `.claude/` 하네스를 aic-api와 같은 구조(에이전트 6종 · 스킬 11종 · 단일 진입 `orchestrator` · CLAUDE.md 포인터)로 교체하고, 내용은 이 저장소의 실제 계약으로 재작성한다.

**Architecture:** 산출물은 전부 마크다운이다. 애플리케이션 코드는 건드리지 않는다. 구 자산(에이전트 8·스킬 9)을 먼저 지우고 새 자산을 얹는 대신, **새 자산을 먼저 만들고 마지막에 구 자산을 지운다** — 중간 상태에서도 하네스가 최소한 하나는 온전하도록 하기 위함이다. 각 태스크는 커밋 하나로 끝난다.

**Tech Stack:** Markdown + YAML frontmatter. 검증은 셸(`diff`, `grep`, `test -e`)과 기존 `docs/check-documentation.sh`.

**Spec:** `docs/work/harness-aic-api-alignment/design.md`

## Global Constraints

- 하네스 문서가 언급하는 저장소 경로는 실재해야 한다. 부재가 문서 본문에 명시된 경로(`_workspace/`)만 예외다.
- 에이전트 frontmatter는 `name` · `description` · `tools` · `model` 4키를 모두 갖는다. `name`은 파일명(확장자 제외)과 같다.
- 스킬 frontmatter는 `name` · `description` 2키를 갖는다. `name`은 디렉터리명과 같다.
- 검증 명령은 이 저장소에 실재하는 것만 쓴다: `./gradlew test architectureTest --offline --no-daemon`, `./gradlew :{module}:integrationTest --offline --no-daemon`, `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon`, `bash docs/check-documentation.sh`. **`./gradlew lint`은 이 저장소에 없다 — 쓰지 않는다.**
- Flyway 마이그레이션 경로는 `infrastructure/common/src/main/resources/db/migration/`이다. **다음 버전 번호를 문서에 상수로 박지 않는다** — 하네스 문서는 "구현 시점에 `ls infrastructure/common/src/main/resources/db/migration/ | sort -V | tail -1`로 최신을 확인하고 +1"이라고 지시한다. (2026-08-30 `origin/dev` 기준 최신은 `V15__add_tracking_close_snapshot.sql`)
- 범위·회귀 판정의 baseline은 **`origin/dev`** 다. 로컬 `dev`는 `b877d42`로 100 커밋 stale이므로 `dev...HEAD`를 쓰면 남의 작업 120개 파일이 딸려 들어온다.
- 명명은 `.ai/rules/architecture.md`를 따른다: 포트는 `{Domain}Repository`/`*Port`, 기술 구현은 `*Adapter`, Spring Data 인터페이스는 `SpringData*Repository`. `{Name}RepositoryImpl` 고정 금지.
- DTO 6단은 `Request → Criteria → Command → Snapshot → Result → Response`다 (`.ai/rules/naming.md`).
- 벤더링 사본 2종은 원본과 byte-identical해야 한다. 저장소 안에서 개별 수정하지 않는다.
- 애플리케이션 코드·테스트·Gradle 스크립트·`.ai/rules/*`는 수정하지 않는다.

---

### Task 1: 벤더링 스킬 2종 복사

**Files:**
- Create: `.claude/skills/definition-of-done/` (원본 디렉터리 통째 복사)
- Create: `.claude/skills/explain-pr/` (원본 디렉터리 통째 복사)

**Interfaces:**
- Consumes: 없음
- Produces: `orchestrator` 스킬이 ④⑦⑩에서 `definition-of-done`을, ⑪-b에서 `explain-pr`을 저장소 경로로 호출할 수 있게 된다.

- [ ] **Step 1: 원본을 그대로 복사**

```bash
cp -r /home/yeop/.claude/skills/definition-of-done .claude/skills/definition-of-done
cp -r /home/yeop/.claude/skills/explain-pr .claude/skills/explain-pr
```

- [ ] **Step 2: byte-identical 확인 (AC4·AC5)**

```bash
diff -r /home/yeop/.claude/skills/definition-of-done .claude/skills/definition-of-done
diff -r /home/yeop/.claude/skills/explain-pr .claude/skills/explain-pr
```

Expected: 두 명령 모두 출력 없음 + exit 0

- [ ] **Step 3: 커밋**

```bash
git add .claude/skills/definition-of-done .claude/skills/explain-pr
git commit -m "chore: definition-of-done·explain-pr 스킬을 저장소에 벤더링"
```

---

### Task 2: 패턴 스킬 4종 — 구조·DTO·엔티티·API 문서

**Files:**
- Create: `.claude/skills/module-layout/SKILL.md`
- Create: `.claude/skills/dto-pattern/SKILL.md`
- Create: `.claude/skills/jpa-entity-pattern/SKILL.md`
- Create: `.claude/skills/swagger-interface-pattern/SKILL.md`

**Interfaces:**
- Consumes: 없음
- Produces: `architect`·`implementer`·`spec-reviewer`가 ④⑤⑧⑥⑨에서 참조할 설계 규약. `orchestrator` 단계표가 이 네 이름을 가리킨다.

- [ ] **Step 1: `module-layout` 작성**

담을 내용:
- 모듈 트리 — `apps/{api,batch,web}`, `domain`, `infrastructure/{common,api,batch}`, `modules/{jpa,redis}`, `supports/{logging,monitoring,email}`, `architecture-tests`
- 의존 방향 — 앱은 infrastructure를 `runtimeOnly`로 소비하고, infrastructure는 어떤 `apps:*`에도 의존하지 않는다
- 절대 하지 않는 것 — 앱 내부 `infrastructure`/`cache`/`repository`/`client` 패키지 생성 금지, Flyway를 `apps/`에 두지 않는다
- 명명표 — 포트 `{Domain}Repository`·`*Port`, JPA adapter `*Adapter`, Spring Data `SpringData*Repository`
- 새 도메인 추가 파일 목록 (domain → infrastructure adapter → application facade → interfaces controller → migration → 테스트)
- 근거 링크: `.ai/rules/architecture.md`

- [ ] **Step 2: `dto-pattern` 작성**

담을 내용:
- 계층별 컨테이너 표 (`*Request`/`*Response` · `*Criteria`/`*Result` · `*Command` · `*Snapshot`)
- Kotlin inner class 패턴 예시 (`class PositionCriteria private constructor() { data class OpenManual(...) }`)
- 변환 흐름 `Request → Criteria → Command → Snapshot → Result → Response`
- MarketPair를 요청/응답에 명시하는 규칙과 symbol-only 호환의 한계(BITHUMB/BINANCE default pair만)
- 시간은 ISO-8601 Instant, 범위는 `[from,to)`
- 근거 링크: `.ai/rules/naming.md`, `.ai/rules/http.md`

- [ ] **Step 3: `jpa-entity-pattern` 작성**

담을 내용:
- JPA Entity는 `data class`가 아니다 — 영속 identity equality + protected mutation
- `@Enumerated(EnumType.STRING)` 필수, `@Column` 길이/nullable 명시
- 다른 Aggregate는 FK 값만 보유, 같은 Aggregate 내부만 LAZY 연관 허용
- MarketPair identity 보존 — non-default pair를 symbol-only row로 fallback 금지
- 시간·범위 계약, DB-first(캐시는 정본 아님)
- 근거 링크: `.ai/rules/naming.md`, `.ai/rules/architecture.md`

- [ ] **Step 4: `swagger-interface-pattern` 작성 (design §7 단서 필수)**

담을 내용:
- **현재 상태 명시** — 이 저장소에는 `@Operation`·`*ControllerDocs` 코드가 없고 springdoc 의존만 `apps/api/build.gradle.kts`에 있다
- 실효 API 계약은 `http/api/{domain}.http` + contract/integration 테스트이며 endpoint 변경 시 함께 갱신한다 (`.ai/rules/http.md`)
- ControllerDocs를 도입할 경우의 규약 — 문서 어노테이션은 인터페이스, 라우팅 어노테이션은 Controller
- **이 스킬은 ControllerDocs 도입을 강제하지 않는다**는 문장을 명시

- [ ] **Step 5: frontmatter 검증**

```bash
head -4 .claude/skills/module-layout/SKILL.md .claude/skills/dto-pattern/SKILL.md .claude/skills/jpa-entity-pattern/SKILL.md .claude/skills/swagger-interface-pattern/SKILL.md
```

Expected: 각 파일이 `---` / `name: <디렉터리명>` / `description: "..."` / `---` 로 시작

- [ ] **Step 6: 커밋**

```bash
git add .claude/skills/module-layout .claude/skills/dto-pattern .claude/skills/jpa-entity-pattern .claude/skills/swagger-interface-pattern
git commit -m "chore: 설계 패턴 스킬 4종 추가"
```

---

### Task 3: 패턴 스킬 4종 — 테스트·검증·문서·전달

**Files:**
- Create: `.claude/skills/test-strategy/SKILL.md`
- Create: `.claude/skills/qa-verification/SKILL.md`
- Create: `.claude/skills/tech-docs-sync/SKILL.md`
- Create: `.claude/skills/task-packet/SKILL.md`

**Interfaces:**
- Consumes: Task 2가 만든 네 스킬 이름 (상호 참조 시 정확히 그 이름을 쓴다)
- Produces: `implementer`·`qa-agent`·`tech-docs`가 ⑧⑩⑪에서 참조할 실행 규약

- [ ] **Step 1: `test-strategy` 작성**

담을 내용:
- 4계층 — Domain 순수 단위 / adapter·slice / `integrationTest`(Testcontainers) / contract·E2E
- 태스크 구분: 각 모듈 `test`(integration 태그 제외), 루트 `architectureTest`, `*:integrationTest`
- 실행 명령 (로컬은 기존 캐시만): `./gradlew test architectureTest --offline --no-daemon`
- 고정 `Clock`·명시 zone 사용, 실제 거래소/FX/SMTP 주소 금지(local mock server)
- `@Disabled` 금지, flaky를 retry로 숨기지 않기
- 근거 링크: `.ai/rules/testing.md`

- [ ] **Step 2: `qa-verification` 작성**

담을 내용:
- 검증 순서와 명령
  ```
  ./gradlew test architectureTest --offline --no-daemon
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon
  ./gradlew :apps:api:integrationTest --offline --no-daemon
  ./gradlew :apps:batch:integrationTest --offline --no-daemon
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
  bash docs/check-documentation.sh
  ```
- 경계면 교차 검증 — Controller 반환 타입 ↔ Response 필드, Request→Criteria→Command 변환 누락, Entity ↔ migration 스키마, `http/api/*.http` ↔ 실제 endpoint
- Docker 부재 등으로 실행 못한 항목을 green으로 기록하지 않는다 (`.ai/rules/testing.md`)
- 보고 형식 — 실행한 명령·실측 수치·미실행 항목과 사유

- [ ] **Step 3: `tech-docs-sync` 작성**

담을 내용:
- 변경 → 문서 매핑표
  | 변경 | 문서 |
  |---|---|
  | 모듈/경계 변경 | `.ai/architecture/ARCHITECTURE_DESIGN.md`, `AGENTS.md`·`CLAUDE.md` 모듈 트리 |
  | endpoint 추가/변경 | `http/api/{domain}.http` |
  | Redis key/TTL/payload | `docs/runbooks/redis-contract.md` |
  | notification 재시도·redrive·PII | `docs/runbooks/durable-notification-delivery.md` |
  | 공개 endpoint 목록 | `docs/runbooks/auth-security.md` |
  | 진행 상황 | `.ai/PROJECT_STATUS.md` |
- 최소 변경 원칙, 사실 기반(코드에서 읽은 것만)
- 갱신 후 `bash docs/check-documentation.sh` 실행

- [ ] **Step 4: `task-packet` 작성**

담을 내용: From/To/Type · Goal · Constraints · Context · Review Stage · Notes 포맷과 사용 예시 2개(구현 요청, 리뷰 피드백)

- [ ] **Step 5: frontmatter 검증**

```bash
head -4 .claude/skills/test-strategy/SKILL.md .claude/skills/qa-verification/SKILL.md .claude/skills/tech-docs-sync/SKILL.md .claude/skills/task-packet/SKILL.md
```

Expected: 각 파일이 `---` / `name: <디렉터리명>` / `description: "..."` / `---`

- [ ] **Step 6: 커밋**

```bash
git add .claude/skills/test-strategy .claude/skills/qa-verification .claude/skills/tech-docs-sync .claude/skills/task-packet
git commit -m "chore: 실행·검증 패턴 스킬 4종 추가"
```

---

### Task 4: 에이전트 6종 작성

**Files:**
- Create: `.claude/agents/architect.md`
- Create: `.claude/agents/implementer.md`
- Create: `.claude/agents/spec-reviewer.md`
- Create: `.claude/agents/code-reviewer.md`
- Create: `.claude/agents/qa-agent.md`
- Create: `.claude/agents/tech-docs.md`

> 기존 동명 파일(`implementer.md`, `code-reviewer.md`)은 이 태스크에서 **덮어쓴다.** 나머지 구 에이전트 6개는 Task 6에서 지운다.

**Interfaces:**
- Consumes: Task 2·3이 만든 스킬 8종 이름
- Produces: `orchestrator` 단계표가 `subagent_type`으로 부를 이름 6개

- [ ] **Step 1: 공통 골격 확정**

모든 에이전트 파일은 아래 순서를 지킨다.

```markdown
---
name: <파일명>
description: "<역할 한 줄 + 언제 쓰는지 + 무엇을 하지 않는지>"
tools: <필요한 도구만>
model: <opus|sonnet>
---

# <Agent 이름>

## 핵심 역할
## 작업 원칙
## 입력/출력
## 재호출 시
## 에러 핸들링
## 팀 통신 프로토콜   ← 마지막 행은 반드시 "호출한 오케스트레이터에게 완료 보고"
```

- [ ] **Step 2: `architect` (tools: Read, Grep, Glob, Write / model: opus)**

- 역할: 요구사항 → `docs/work/{slug}/design.md` + `dod.md` 작성, 모듈 배치·포트 정의·마이그레이션 필요 여부 판단. **코드 미수정.**
- 참조 스킬: `module-layout`, `dto-pattern`, `jpa-entity-pattern`, `swagger-interface-pattern`, `definition-of-done`
- 설계 필수 항목 체크리스트: MarketPair identity, `[from,to)` 범위, DB-first/after-commit 순서, Flyway 필요 여부(`infrastructure/common/src/main/resources/db/migration/`, **다음 번호는 `sort -V | tail -1` 결과 +1 — 문서에 상수로 적지 않는다**), 배치면 `JobExecutor` lock key·lease·timeout, 알림이면 claim/fencing
- 재호출 시: 기존 `docs/work/{slug}/`가 있으면 새로 쓰지 않고 지적된 부분만 보완

- [ ] **Step 3: `implementer` (tools: Read, Write, Edit, Grep, Glob, Bash / model: sonnet)**

- 역할: plan.md 태스크를 TDD로 구현
- 구현 순서: Domain 불변식 → 포트·Service → infrastructure adapter → Criteria/Result/Facade → Controller 또는 Scheduler→Job → integration → architectureTest
- 모듈별 검증: `./gradlew :{module}:test --offline --no-daemon`
- Opus escalate 조건: durable notification claim/fencing, Redis 분산 락 owner token·renew, WebSocket generation fencing·watchdog, premium 계산 정확성, plan 모호
- 금지: 앱 내부 `infrastructure`/`cache`/`repository`/`client` 패키지 생성, immutable 마이그레이션(V12 등) 수정

- [ ] **Step 4: `spec-reviewer` (tools: Read, Grep, Glob / model: opus)**

- 역할: 계약 준수만 본다. 위반은 `파일:라인`으로 보고. **코드 미수정.**
- 체크 축 표 (근거 컬럼에 `.ai/rules/*` 문서와 절 번호를 적는다): Controller→Facade 단일 주입 / Facade가 infrastructure 타입 참조 금지 / DTO 6단 네이밍 / MarketPair 보존 / `[from,to)` / Flyway append-only·V12 불변 / Scheduler→Job 단일 호출 / Job이 기술 구현 미참조 / bounded metric tag만 사용 / 공개 endpoint는 method+path 조합

- [ ] **Step 5: `code-reviewer` (tools: Read, Grep, Glob, Bash / model: opus)**

- Phase 1: `./gradlew test architectureTest --offline --no-daemon` 실행. **`lint` 태스크는 이 저장소에 없다.** architectureTest가 커버하는 항목(모듈 의존, import 경계, 바이트코드 경계)은 수동 리뷰에서 제외
- Phase 2 판단 영역: null/경계, 트랜잭션 경계와 after-commit 순서, N+1, 캐시-DB 정합(부분 캐시 결과 반환 금지), 시간대(UTC 버킷 vs `aggregation.zone`), 동시성(claim fencing·락 lease), 시크릿/PII 로깅, 테스트 누락
- 출력: Critical / Major / Minor + `[POSSIBLE]` 태그 규칙

- [ ] **Step 6: `qa-agent` (tools: Read, Grep, Glob, Bash / model: sonnet)**

- 역할: `qa-verification` 스킬 절차를 실제로 실행하고 실측 수치를 보고. 점진 검증(모듈 완료 즉시) 원칙
- Docker 미가용 시 `[ENV_ISSUE]`로 보고하고 green으로 기록하지 않는다

- [ ] **Step 7: `tech-docs` (tools: Read, Edit, Write, Grep, Glob / model: sonnet)**

- 역할: `tech-docs-sync` 매핑표대로 문서 동기화 + `bash docs/check-documentation.sh` 통과 확인
- 없는 경로(`.ai/diagrams/` 등)를 갱신했다고 보고하지 않는다

- [ ] **Step 8: frontmatter 4키 검증 (AC1)**

```bash
head -6 .claude/agents/architect.md .claude/agents/implementer.md .claude/agents/spec-reviewer.md .claude/agents/code-reviewer.md .claude/agents/qa-agent.md .claude/agents/tech-docs.md
```

Expected: 각 파일에 `name`·`description`·`tools`·`model` 네 줄이 모두 보이고 `name`이 파일명과 일치

- [ ] **Step 9: 커밋**

```bash
git add .claude/agents
git commit -m "chore: 하네스 에이전트를 역할 6축으로 재작성"
```

---

### Task 5: `orchestrator` 진입 스킬 작성

**Files:**
- Create: `.claude/skills/orchestrator/SKILL.md`

**Interfaces:**
- Consumes: Task 1~4의 스킬 10종·에이전트 6종 이름 전부
- Produces: 새 세션의 단일 진입점. `CLAUDE.md`가 이 이름을 가리킨다.

- [ ] **Step 1: frontmatter — 트리거 경계를 좁게**

`description`에 담을 것: 코드 변경을 만드는 작업(기능 추가·도메인 추가·배치 Job·버그픽스·리팩터링)에 사용, **그리고 재실행·보완·업데이트 같은 후속 요청에도 사용**. 단순 질문·단일 파일 사소한 수정은 트리거하지 않는다.

- [ ] **Step 2: 단계 정의 ⓪~⑪**

| 단계 | 내용 | 스킬 | 에이전트 |
|---|---|---|---|
| ⓪ | 컨텍스트 확인 (읽기 전용, 스킵 불가) — `docs/work/{slug}/` 존재 여부로 초기/후속/부분 재실행/새 실행 4갈래 판정 | — | — |
| ① | worktree 격리 (스킵 불가, 항상 최초) | `superpowers:using-git-worktrees` | — |
| ② | 브레인스토밍 | `superpowers:brainstorming` | — |
| ③ | 사용자 합의 | AskUserQuestion | — |
| ④ | design.md + dod.md(DRAFT) | `superpowers:brainstorming`, `definition-of-done` | `architect` |
| ⑤ | plan.md | `superpowers:writing-plans` | `architect` |
| ⑥ | 스펙 리뷰 | 기본 Claude(`spec-reviewer`), 명시 시 `codex-spec-review` | `spec-reviewer` |
| ⑦ | 사용자 리뷰 + DoD 동결(FROZEN) | AskUserQuestion, `definition-of-done` | — |
| ⑧ | 구현 | `superpowers:subagent-driven-development`, `superpowers:test-driven-development`, `test-strategy` | `implementer` |
| ⑧′ | 실패 시 원인 규명 | `superpowers:systematic-debugging` | `implementer` |
| ⑨ | 병렬 리뷰 (계약 ∥ 버그) | `superpowers:dispatching-parallel-agents`, `task-packet` | `spec-reviewer` ∥ `code-reviewer` |
| ⑩ | 검증 + DoD 판정 | `superpowers:verification-before-completion`, `qa-verification`, `definition-of-done` | `qa-agent` |
| ⑪ | 마무리 — ⑪-a 문서 동기화 / ⑪-b `explain-pr` / ⑪-c finalize(커밋+PR) / ⑪-d 피드백 수집 (스킵 불가) | `tech-docs-sync`, `explain-pr`, `finalize` | `tech-docs` |

- [ ] **Step 3: 저장소 고유 사항 반영**

- MR이 아니라 **PR**이다 — 원격은 GitHub(`geonyeop123/premium-spread`), `gh pr create --base dev`
- 브랜치 규칙 `<type>/<short-description>`, 커밋 `<type>: <subject>` + 한글 bullet, **`Co-Authored-By: Claude` 금지** (`.ai/rules/git.md`)
- 검증 명령은 Global Constraints의 목록만 사용. `lint` 금지
- ⑪-d 피드백 반영 대상표: 역할 판단 기준 → `.claude/agents/{agent}.md` / 작성 패턴 → `.claude/skills/{skill}/SKILL.md` / 단계·게이트 → `orchestrator` / 전역 계약 → `CLAUDE.md`+`.ai/rules/*`
- **이 저장소에는 자동 하네스 정합성 게이트가 없다**는 사실과, 하네스 파일을 고쳤으면 `CLAUDE.md ## 하네스` 변경 이력에 한 행을 남긴다는 규칙을 명시

- [ ] **Step 4: Red Flags 표 작성**

최소 포함: ① 없이 ②부터 진행 / `dev` 트리에 산출물 생성 / `dev`·`main` 직접 커밋 / DoD 동결 없이 ⑧ / ③⑦⑩ 승인 없이 진행 / 앱 내부 기술 패키지 생성 / immutable 마이그레이션 수정 / MarketPair fallback / 부분 캐시 결과 반환 / PR 머지 전 worktree 삭제 / ⑪ 생략

- [ ] **Step 5: 참조 경로 실재 확인 (AC3 예비)**

```bash
grep -oE '(\.ai|docs|scripts|apps|domain|infrastructure|modules|supports|http|\.claude)/[A-Za-z0-9_./{}*-]+' .claude/skills/orchestrator/SKILL.md
```

Expected: 출력된 경로 중 `{slug}`·`{module}` 같은 플레이스홀더를 제외한 실경로가 모두 저장소에 존재

- [ ] **Step 6: 커밋**

```bash
git add .claude/skills/orchestrator
git commit -m "chore: orchestrator 진입 스킬 추가"
```

---

### Task 6: 구 하네스 자산 삭제

**Files:**
- Delete: `.claude/agents/{analyzer,planner,db-migrator,frontend-dev,qa-validator,tech-writer}.md`
- Delete: `.claude/skills/{planning,api-feature,batch-job,db-migration,frontend,qa-validator,code-review,tech-docs,premium-orchestrator}/`

**Interfaces:**
- Consumes: Task 4·5 완료 (새 하네스가 자리를 잡은 뒤에 지운다)
- Produces: 에이전트 6개·스킬 11개만 남은 상태

- [ ] **Step 1: 삭제**

```bash
git rm -r .claude/agents/analyzer.md .claude/agents/planner.md .claude/agents/db-migrator.md .claude/agents/frontend-dev.md .claude/agents/qa-validator.md .claude/agents/tech-writer.md
git rm -r .claude/skills/planning .claude/skills/api-feature .claude/skills/batch-job .claude/skills/db-migration .claude/skills/frontend .claude/skills/qa-validator .claude/skills/code-review .claude/skills/tech-docs .claude/skills/premium-orchestrator
```

- [ ] **Step 2: 개수 확인 (AC1·AC2·AC8)**

```bash
ls .claude/agents
ls .claude/skills
```

Expected: 에이전트 6개(architect·code-reviewer·implementer·qa-agent·spec-reviewer·tech-docs), 스킬 11개(definition-of-done·dto-pattern·explain-pr·jpa-entity-pattern·module-layout·orchestrator·qa-verification·swagger-interface-pattern·task-packet·tech-docs-sync·test-strategy)

- [ ] **Step 3: 커밋**

```bash
git add -A .claude
git commit -m "chore: 구 하네스 에이전트 8종·스킬 9종 제거"
```

---

### Task 7: `CLAUDE.md` 하네스 포인터 등록

**Files:**
- Modify: `CLAUDE.md` (`## 관련 문서` 앞에 `## 하네스` 섹션 삽입)

**Interfaces:**
- Consumes: Task 5의 `orchestrator` 이름
- Produces: 새 세션이 하네스 진입점을 아는 경로

- [ ] **Step 1: 섹션 작성**

담을 내용 (aic-api와 같은 형태):
- 목표 한 줄
- 표: 코드 변경 작업 → `orchestrator` 스킬(`.claude/skills/orchestrator/`)
- "에이전트·스킬 목록과 디렉터리 구조는 파일 시스템에서 직접 확인한다 — 여기에 옮겨 적지 않는다" (두 곳이 갈리는 것을 막는다)
- 자동 하네스 정합성 게이트는 **없다**는 사실 명시
- 변경 이력 표 — `2026-08-30 | aic-api 구조로 재구성(에이전트 6·스킬 11·orchestrator 진입) | .claude/**, CLAUDE.md | 4월 하네스가 모듈 재편·Flyway 이동·MarketPair 재구조화를 반영하지 못해 잘못된 경로를 지시`

- [ ] **Step 2: 검증 (AC6·AC7)**

```bash
grep -Pzoq '## 하네스[\s\S]*?### 변경 이력' CLAUDE.md
bash docs/check-documentation.sh
```

Expected: 첫 명령 exit 0 (= `## 하네스` 섹션이 있고 그 **뒤에** `### 변경 이력`이 온다), 두 번째 `documentation check passed ...` 출력 + exit 0

GNU grep의 `-P`가 없는 환경이면 대체 명령을 쓴다: `awk '/^## 하네스/{a=1} a&&/^### 변경 이력/{found=1} END{exit !found}' CLAUDE.md`

- [ ] **Step 3: 커밋**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md에 하네스 포인터와 변경 이력 등록"
```

---

### Task 8: 전체 검증과 DoD 증거 기록

**Files:**
- Modify: `docs/work/harness-aic-api-alignment/dod.md` (증거 로그·최종 판정)

**Interfaces:**
- Consumes: Task 1~7 전부
- Produces: AC1~AC8 판정

- [ ] **Step 1: T1 4건 실행**

```bash
diff -r /home/yeop/.claude/skills/definition-of-done .claude/skills/definition-of-done
diff -r /home/yeop/.claude/skills/explain-pr .claude/skills/explain-pr
grep -Pzoq '## 하네스[\s\S]*?### 변경 이력' CLAUDE.md
bash docs/check-documentation.sh
```

Expected: 네 명령 모두 exit 0

- [ ] **Step 2: T3 4건 관찰 기록**

- AC1: `head -6 .claude/agents/*.md` 출력에서 frontmatter 4키(`name`·`description`·`tools`·`model`) 확인
- AC2: `head -3 .claude/skills/*/SKILL.md` 출력에서 `name` ↔ 디렉터리명 일치 확인
- AC3: **새로 쓴 하네스 문서 전체**를 대상으로 경로 토큰을 추출해 존재를 확인한다. 대상은 `.claude/agents/*.md` 6개 + 자체 작성 스킬 9종의 `SKILL.md` + `CLAUDE.md`이며, 벤더링 사본 2종(`definition-of-done`·`explain-pr`)은 upstream 서술이라 제외한다. 아래 루프 결과에서 죽은 참조 0건이어야 한다.

```bash
for f in .claude/agents/*.md .claude/skills/module-layout/SKILL.md .claude/skills/dto-pattern/SKILL.md .claude/skills/jpa-entity-pattern/SKILL.md .claude/skills/swagger-interface-pattern/SKILL.md .claude/skills/test-strategy/SKILL.md .claude/skills/qa-verification/SKILL.md .claude/skills/tech-docs-sync/SKILL.md .claude/skills/task-packet/SKILL.md .claude/skills/orchestrator/SKILL.md CLAUDE.md; do
  for p in $(grep -oE '(\.ai|docs|scripts|apps|domain|infrastructure|modules|supports|http|\.claude|\.github)/[A-Za-z0-9_./-]+' "$f" | sort -u); do
    case "$p" in *"{"*|*"}"*|_workspace/*) continue ;; esac
    test -e "$p" || echo "DEAD $f -> $p"
  done
done
```

- AC8: `git diff --stat origin/dev...HEAD`가 `.claude/`·`CLAUDE.md`·`docs/work/` 밖 파일을 포함하지 않음. **로컬 `dev`가 아니라 `origin/dev`를 쓴다** (Global Constraints 참조)
- AC4·AC5 보강: 벤더링 사본의 체크섬을 증거로 남긴다 — `find .claude/skills/definition-of-done .claude/skills/explain-pr -type f -exec sha256sum {} +`

- [ ] **Step 3: 회귀 방어선 R2**

```bash
./gradlew compileKotlin --offline --no-daemon
```

Expected: `BUILD SUCCESSFUL` (코드 무변경이므로 up-to-date)

- [ ] **Step 4: dod.md 증거 로그와 최종 판정 채우기**

각 AC 아래 코드블록에 실행 날짜·명령·출력 요약을 append. 최종 판정 블록의 `<p>`·`<q>`·`<k>`·SHA를 실제 값으로 치환.

- [ ] **Step 5: 커밋**

```bash
git add docs/work/harness-aic-api-alignment/dod.md
git commit -m "docs: 하네스 재구성 DoD 증거 로그와 판정 기록"
```

---

## Self-Review

**1. Spec coverage**

| design.md 요구 | 태스크 |
|---|---|
| §4 에이전트 6종 + frontmatter 4키 | Task 4 |
| §4 스킬 11종 (패턴 8 + orchestrator + 벤더링 2) | Task 1·2·3·5 |
| §4 CLAUDE.md 포인터 + 변경 이력 | Task 7 |
| §5 내용 매핑 (Kotlin·MySQL/Redis·포트어댑터·Flyway 경로·검증 명령·MarketPair) | Global Constraints + Task 2·3·4·5 각 Step |
| §6 구 자산 삭제 | Task 6 |
| §7 swagger 스킬 단서 | Task 2 Step 4 |
| §8 검증 4티어 | Task 8 |
| §2 비목표(게이트·코드·규칙문서 미변경) | Global Constraints + Task 8 Step 2 AC8 |

누락 없음.

**2. Placeholder scan**

`{slug}`·`{module}`·`{domain}`·`{agent}`·`{skill}`은 문서 템플릿 변수이며 플레이스홀더가 아니다. "TBD/TODO/나중에" 없음. 각 Step은 작성할 내용을 항목으로 지정하고, 셸 단계는 실제 명령을 담았다.

**3. Type consistency**

스킬 이름 11개와 에이전트 이름 6개가 Task 2·3·4·5·6·7에서 동일한 철자로 쓰였는지 확인함 — `qa-verification`(스킬) vs `qa-agent`(에이전트), `tech-docs-sync`(스킬) vs `tech-docs`(에이전트)의 구분을 유지한다. 삭제 목록(Task 6)의 구 이름 `qa-validator`·`tech-docs`(구 스킬)와 새 이름이 겹치는 지점은 Task 6이 Task 3·5 뒤에 오도록 순서를 잡아 해소했다 — 단, 구 스킬 `tech-docs`와 신규 스킬 `tech-docs-sync`는 이름이 다르므로 삭제가 신규 자산을 건드리지 않는다.
