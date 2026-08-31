# 하네스 aic-api 정합 재구성 — 설계

- 작업 슬러그: `harness-aic-api-alignment`
- 브랜치: `chore/harness-aic-api-alignment` (base `dev`)
- 작성일: 2026-08-30

## 1. 배경

현재 하네스는 2026-04-04 커밋 `039c9ee`("chore: 하네스 구성 — 에이전트 8개 + 스킬 9개 + 오케스트레이터")
이후 갱신되지 않았다. 그 사이 저장소는 `:domain` / `:infrastructure:{common,api,batch}` 분리,
Flyway 소유권 이동, MarketPair 재구조화(V12~V14), durable notification 도입을 거쳤다. 감사에서 확인한
불일치는 다음과 같다.

| # | 불일치 | 실제 |
|---|--------|------|
| 1 | `db-migration` 스킬이 마이그레이션 경로를 `apps/api/src/main/resources/db/migration/`로 안내 | `infrastructure/common/src/main/resources/db/migration/` |
| 2 | 같은 스킬이 "V1~V9 기준"이라 서술하고 없어진 `origin/feature/premium` 브랜치를 조회 | `origin/dev` 기준 최신 마이그레이션은 `V15__add_tracking_close_snapshot.sql` |
| 3 | `api-feature` 스킬이 `domain/`·`infrastructure/`·`application/`을 apps/api **내부 패키지**로 안내하고 `{Name}RepositoryImpl`·`Jpa{Name}Repository` 네이밍을 지시 | `.ai/rules/architecture.md`는 앱 내부 infrastructure/repository 패키지를 금지하고 `*Adapter`·`SpringData*Repository`를 요구 |
| 4 | `batch-job` 스킬이 apps/batch 안에 `client/`·`cache/`·`repository/`를 만들라고 안내 | `.ai/rules/batch.md`가 명시적으로 금지 |
| 5 | `qa-validator` 스킬의 검증 명령이 `./gradlew test` / `:apps:api:integrationTest`뿐 | `.ai/rules/testing.md`는 `architectureTest`, `--offline --no-daemon`, `:infrastructure:common:integrationTest`, `verifyMigrations`를 요구 |
| 6 | 스킬 8개의 `references/` 디렉터리가 전부 비어 있고 본문에서 참조하는 링크도 0건 | progressive disclosure 미구현 |
| 7 | 에이전트 frontmatter에 `tools`·`model` 없음 | 자동 배정·도구 제한이 걸리지 않음 |
| 8 | `CLAUDE.md`에 하네스 포인터·변경 이력 섹션 없음 | 새 세션이 하네스의 존재와 진입점을 알 근거가 CLAUDE.md에 없음 |

같은 계보에서 출발한 `aic-api`(`gitlab.saycore.kr/standard/backend_solution`)의 하네스는 그 사이
역할 6축 + 단일 진입 오케스트레이터 + 패턴 스킬 세트로 정리됐다. 이 저장소를 그 구조에 맞춘다.

## 2. 목표와 비목표

**목표**

- 에이전트 역할 축을 aic-api와 동일한 6종으로 교체한다.
- 스킬 구성을 aic-api와 1:1로 맞춘다 — 진입 스킬 `orchestrator` + 패턴 스킬 8 + 벤더링 스킬 2.
- 각 파일의 **내용은 premium-spread의 실제 계약**(Kotlin / MySQL·Redis / 포트-어댑터 / MarketPair /
  WebSocket ingestion / durable notification)으로 재작성한다. aic-api 문장을 그대로 옮기지 않는다.
- `CLAUDE.md`에 하네스 포인터와 변경 이력을 등록한다.

**비목표 (이번 작업에서 하지 않는 것)**

- `scripts/harness-check.sh` 자기검증 게이트 이식 — 사용자가 ③에서 **생략**으로 결정했다.
- 애플리케이션 코드·테스트 변경. 이번 변경은 `.claude/`, `CLAUDE.md`, `docs/work/` 안에서 끝난다.
- `.ai/rules/*`·`.ai/architecture/*` 규칙 문서 수정. 하네스는 규칙을 **참조**하되 규칙의 정본이 아니다.
- ControllerDocs/Swagger 어노테이션을 코드에 도입하는 일 (§7 참조).

## 3. ③에서 합의한 결정

| 결정 | 선택 | 결과 |
|------|------|------|
| 에이전트 구성 | aic-api와 동일한 6종 | 기존 8종 삭제, batch·frontend·migration 특수성은 패턴 스킬로 흡수 |
| harnessCheck 게이트 | 생략 | 재발 방지 자동화 없음 → §9 리스크로 기록 |
| `definition-of-done`·`explain-pr` | 저장소에 벤더링 | 개인 환경 의존 제거, 원본과 byte-identical 유지 계약 |
| 패턴 스킬 세트 | aic-api와 1:1 (8종 그대로) | `swagger-interface-pattern` 포함 — §7의 단서를 달아 작성 |

## 4. 타깃 구조

```text
.claude/
├── agents/                          # 6종 (기존 8종 삭제)
│   ├── architect.md                 # 설계·계획·문서 선작성 (Opus, 코드 미수정)
│   ├── implementer.md               # TDD 구현 (Sonnet 기본, escalate 시 Opus)
│   ├── spec-reviewer.md             # 계약·규칙 준수 리뷰 (Opus, read-only)
│   ├── code-reviewer.md             # 버그·회귀 리뷰 (Opus, read-only + Bash)
│   ├── qa-agent.md                  # 빌드·테스트 실행과 경계면 교차 검증 (Sonnet)
│   └── tech-docs.md                 # 문서 동기화 (Sonnet)
├── skills/                          # 11종 (기존 9종 삭제)
│   ├── orchestrator/SKILL.md        # 진입점. ⓪~⑪ 파이프라인
│   ├── module-layout/SKILL.md       # 모듈 배치와 포트/어댑터 명명
│   ├── dto-pattern/SKILL.md         # Request/Criteria/Command/Snapshot/Result/Response
│   ├── jpa-entity-pattern/SKILL.md  # Entity 규칙, MarketPair, 식별자 동등성
│   ├── swagger-interface-pattern/SKILL.md  # API 문서 계약 (§7)
│   ├── test-strategy/SKILL.md       # test / architectureTest / integrationTest 4계층
│   ├── qa-verification/SKILL.md     # 검증 명령과 판정
│   ├── tech-docs-sync/SKILL.md      # 변경 → 문서 매핑
│   ├── task-packet/SKILL.md         # 에이전트 간 전달 포맷
│   ├── definition-of-done/          # 벤더링 (SKILL.md + template.dod.md)
│   └── explain-pr/                  # 벤더링 (SKILL.md + template.md + scripts/)
└── rules/                           # 변경 없음 (.ai/rules/* @import 6종)

CLAUDE.md                            # `## 하네스` 섹션 신설 (포인터 + 변경 이력)
```

에이전트 frontmatter는 `name` · `description` · `tools` · `model` 네 키를 모두 갖는다. aic-api와 같이
각 에이전트 문서는 `## 재호출 시`(이전 산출물이 있을 때의 행동)와 호출한 쪽으로의 **완료 보고 의무**를
포함한다.

## 5. 내용 매핑 — aic-api 계약을 premium-spread 계약으로 치환

하네스 파일의 문장은 저장소의 실제 계약을 가리켜야 한다. 치환 표는 다음과 같다.

| 축 | aic-api | premium-spread |
|----|---------|----------------|
| 언어·런타임 | Java 21, Lombok, record DTO | Kotlin 2.0, `data class`, JPA Entity는 `data class` 아님 |
| 저장소 | PostgreSQL, `TIMESTAMPTZ` | MySQL 8 + Redis 7, UTC minute/hour 버킷, `aggregation.zone` day 버킷 |
| 비동기 | Kafka Outbox/Inbox/DLT | WebSocket ingestion + durable notification (`FOR UPDATE SKIP LOCKED`, claim token fencing) |
| 모듈 | `domain` / 단일 `infrastructure` / `modules/*` / `apps/{6}` | `domain` / `infrastructure:{common,api,batch}` / `modules:{jpa,redis}` / `supports:{logging,monitoring,email}` / `apps:{api,batch,web}` / `architecture-tests` |
| Flyway 소유 | `modules/database/src/main/resources/db/migration/` | `infrastructure/common/src/main/resources/db/migration/` (API만 실행, Batch 비활성) |
| DTO 6단 | Request→Criteria→Command→Info→Result→Response | Request→Criteria→Command→**Snapshot**→Result→Response (`.ai/rules/naming.md`) |
| 명명 | `Jpa{X}RepositoryAdapter`, `{X}SpringDataRepository` | `*Adapter`, `SpringData*Repository` (`.ai/rules/architecture.md`) |
| 검증 명령 | `./gradlew lint` (Checkstyle+SpotBugs+ArchUnit) | `./gradlew test architectureTest --offline --no-daemon`, `:{module}:integrationTest`, `:infrastructure:common:verifyMigrations` |
| 도메인 불변식 | Aggregate 경계, FK 참조 | 위에 더해 **MarketPair identity 보존**, `[from,to)` 범위, DB-first(캐시는 정본 아님) |
| 회귀 비용 최대 지점 | Outbox/Inbox 트랜잭션, DLT offset | premium 계산 경로(current/seconds 필수·history 비필수), notification claim fencing, Redis 락 owner token |

`lint` 태스크는 이 저장소에 없다. 검증은 루트 `build.gradle.kts`가 등록한 `unitTest` ·
`architectureTest` · `verifyTestIsolationPolicy` · `verifyCoverageExclusions` ·
`verifySecurityDependencyVersions`와 `check`가 담당한다. 하네스 문서는 이 이름들만 쓴다.

## 6. 삭제 대상

| 대상 | 경로 | 사유 |
|------|------|------|
| 에이전트 8종 | `.claude/agents/{analyzer,planner,implementer,db-migrator,frontend-dev,qa-validator,code-reviewer,tech-writer}.md` | 6축으로 교체. `implementer`·`code-reviewer`는 같은 이름으로 재작성한다 |
| 스킬 9종 | `.claude/skills/{planning,api-feature,batch-job,db-migration,frontend,qa-validator,code-review,tech-docs,premium-orchestrator}/` | 새 11종으로 교체. 빈 `references/` 디렉터리도 함께 사라진다 |

`.ai/skills/tdd-workflow/SKILL.MD`는 `.claude/skills/` 밖에 있어 로딩되지 않는 고아 자산이다. 이번
스코프(하네스 구조 정합) 밖이므로 **삭제하지 않고** §9에 후속 항목으로 기록만 한다.

## 7. `swagger-interface-pattern`을 어떻게 쓸 것인가

이 저장소에는 `@Operation`·`*ControllerDocs`를 쓰는 코드가 **없다.** springdoc 의존만
`apps/api/build.gradle.kts:62`에 선언돼 있고 OpenAPI 설정 클래스도 없다. 실효 API 계약은
`http/api/*.http` 6개 파일과 contract/integration 테스트이며, `.ai/rules/http.md`가 endpoint 변경 시
이 둘을 함께 갱신하도록 요구한다.

사용자가 스킬 세트를 aic-api와 1:1로 맞추기로 했으므로 이 스킬은 만들되, **현재 상태를 사실대로 적고**
다음 세 가지를 담는다.

1. 현재 이 저장소의 API 문서 계약은 `http/api/{domain}.http` + contract test다 — 변경 시 함께 갱신한다.
2. springdoc 의존이 이미 있으므로 ControllerDocs를 도입할 때의 규약(문서 어노테이션은 인터페이스,
   라우팅 어노테이션은 Controller)을 미리 정의해 둔다.
3. **이 스킬이 ControllerDocs 도입을 강제하지 않는다.** 도입은 별도 결정이며, 도입 전까지 1번이 정본이다.

이 단서가 없으면 스킬이 코드에 없는 패턴을 "규칙"으로 서술하게 되어 감사에서 확인한 것과 같은
종류의 drift를 새로 만든다.

## 8. 검증 방법

자동 게이트가 없으므로 이번 MR에서는 아래를 수동으로 1회 실행해 증거를 남긴다.

| 티어 | 대상 | 명령/방법 |
|------|------|----------|
| 구조 | 에이전트·스킬 frontmatter | 각 파일 앞 `---` 블록에 `name`이 디렉터리/파일명과 일치하는지 확인 |
| 참조 | **새로 쓴 하네스 문서 전체**(`.claude/agents/*.md` 6개 + 자체 작성 스킬 9종의 `SKILL.md` + `CLAUDE.md`) 본문이 언급하는 저장소 경로 | 본문에서 경로 토큰을 뽑아 `test -e`로 존재 확인 (1회성 셸 루프). 벤더링 사본 2종은 upstream 서술이라 이 축에서 제외한다 |
| 문서 | `CLAUDE.md`·`AGENTS.md` 등 정본 문서 | `bash docs/check-documentation.sh` |
| 회귀 | 앱 코드 무변경 | `git diff --stat origin/dev...HEAD`가 `.claude/`·`CLAUDE.md`·`docs/work/` 밖 파일을 포함하지 않을 것 |

**baseline은 `origin/dev`다.** 이 worktree는 `origin/dev@4560124`에서 분기했고 로컬 `dev`는 `b877d42`로
100 커밋 뒤져 있다. `dev...HEAD`로 범위를 재면 남의 작업 120개 파일이 딸려 들어와 판정이 무의미해진다.

## 9. 리스크와 후속 항목

| 항목 | 내용 | 처리 |
|------|------|------|
| 게이트 부재 | 하네스 문서가 코드 이동을 따라가지 못해도 아무도 실패시키지 않는다. 이번 drift 8건이 4개월간 방치된 원인이 그것이다 | 사용자 결정으로 이번 스코프 제외. 필요해지면 `scripts/harness-check.sh` 이식을 별도 작업으로 연다 |
| 트리거 충돌 | 유저 레벨 스킬(`feature-workflow`, `codex-*`, `analysis-report` 등)과 프로젝트 `orchestrator`가 겹칠 수 있다 | `orchestrator` description에 "코드 변경을 만드는 작업"으로 경계를 좁히고, 단순 질문·단일 파일 수정은 제외한다고 명시 |
| 벤더링 동기화 | 원본(`~/.claude/skills/`) 갱신 시 사본이 낡는다 | 두 SKILL.md에 "원본 갱신 시 통째로 재복사, 저장소 안에서 개별 수정 금지"를 명시 |
| 고아 스킬 | `.ai/skills/tdd-workflow/SKILL.MD` | 이번 스코프 밖. 후속 정리 대상으로만 기록 |
| 벤더링 검증의 환경 의존 | AC4·AC5는 `/home/yeop/.claude/skills/`를 기준으로 비교하므로 CI나 다른 작업자 머신에서는 재현되지 않는다 | 이번 MR에서는 복사 충실도 증거로 충분하다고 보고, 재현 가능성을 위해 사본 파일의 `sha256sum`을 dod 증거 로그에 함께 남긴다. 저장소 내부 manifest 도입은 게이트 생략 결정과 함께 후속으로 넘긴다 |

## 10. 스펙 리뷰(⑥) 반영

codex adversarial-review 1라운드에서 4건을 받아 **전부 ACCEPT**했다. 반박한 항목은 없다.

| 이슈 | 지적 | 반영 |
|------|------|------|
| high | 최신 Flyway 버전을 V14로 서술 — 실제로는 `V15__add_tracking_close_snapshot.sql`이 존재. 하네스가 다음 번호를 V15로 지시하면 충돌 | §1 표 정정. plan은 정적 번호 대신 "구현 시점에 디렉터리 최신 버전을 확인하고 +1"로 지시 |
| high | AC8의 baseline `dev`가 로컬에서 stale해 판정이 항상 실패 | 전 문서의 범위 기준을 `origin/dev...HEAD`로 교체(§8) |
| medium | AC3(경로 실재)를 orchestrator 한 파일에만 적용 | 검사 대상을 새로 쓴 하네스 문서 전체로 확대(§8), 벤더링 사본 제외 근거 명시 |
| medium | AC4·AC5가 개인 홈 의존 · AC6이 변경 이력 표를 검증하지 않음 | §9에 환경 의존 한계와 sha256 증거를 기록. AC6 검증 명령을 `## 하네스` 섹션 **안에** 변경 이력 표가 있는지까지 보도록 강화 |
