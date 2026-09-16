# Premium Spread

> 비즈니스 도메인은 `.ai/context/project-overview.md` 참조

## Tech Stack

- Kotlin 2.x, Java 21, Spring Boot 3.x
- MySQL 8, Redis 7, Testcontainers
- Gradle 멀티모듈 (정확한 버전은 `gradle.properties`/`build.gradle.kts` 참조)

## Module Structure

```
apps/
├── api/          # REST API 서버 (Port 8080)
└── batch/        # 배치 스케줄러 (Port 8081, WebSocket 실시간 시세 수집 + FX 30분 수집 + 1분/1시간/1일 집계)

modules/
├── jpa/          # JPA 공통 설정, BaseEntity
└── redis/        # Redis, Redisson 분산 락

supports/
├── logging/      # 구조화 로깅, 민감정보 마스킹
├── monitoring/   # Micrometer 메트릭, 헬스체크
└── email/        # JavaMail 기반 이메일 발송 (이슈 #27)
```

## Quick Commands

```bash
./gradlew compileKotlin                  # 빌드
docker compose -f docker/infra-compose.yml up -d  # 인프라 실행
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun &   # API 서버 (8080)
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun & # Batch 서버 (8081)
./gradlew test                           # Unit tests
./gradlew :apps:api:integrationTest      # Integration tests (Docker 필요)
```

## Coding Guidelines

1. **Kotlin 불변 우선** - `val`, `data class`
2. **순수 함수** - 도메인 계산은 부작용 최소화
3. **과도한 추상화 금지** - 필요할 때만 인터페이스
4. **컴파일 가능 + 테스트 통과** 상태 유지

## 하네스

`.claude/` 아래에 에이전트와 스킬 하네스를 둔다. **목록과 디렉터리 구조는 파일 시스템에서 직접
확인한다 — 여기에 옮겨 적지 않는다.** 옮겨 적으면 두 곳이 갈린다.

| 항목 | 내용 |
|------|------|
| 목표 | 설계·구현·리뷰·검증·문서화를 고정된 단계와 검증 가능한 완료 기준으로 수행한다 |
| 코드 변경 작업 | `orchestrator` 스킬 (`.claude/skills/orchestrator/`) |
| 하네스 파일 변경 작업 | `orchestrator` 스킬 — 하네스 파일(`.claude/**`, `CLAUDE.md`) 변경도 코드 변경 작업이다. 의미를 바꾸지 않는 소규모 문구 수정의 스킵 경로는 그 스킬의 스킵 규칙 표가 소유한다 |
| 산출물 위치 | `docs/work/{slug}/` — `design.md`, `plan.md`, `dod.md`, `understanding.md` |
| 정합성 검사 | `./gradlew harnessCheck` — 하네스 파일을 고쳤으면 커밋 전에 직접 한 번 돌리고 아래 변경 이력에 한 행을 남긴다 |

`harnessCheck`(`docs/check-harness.sh`)는 하네스 파일 **본문**(펜스 코드블록과 frontmatter 제외)이
언급하는 경로와 `@import` 대상이 실재하는지, agent/skill frontmatter가 유효한지, 그리고 이 저장소가
정한 하네스 계약(필수 절·단계 순서·스킬 디렉터리 형태)을 검사한다. 게이트가 빨간데 죽은 참조가 없으면
계약 단정 쪽을 본다. `harnessCheckTest`(`docs/test-check-harness.sh`)는 그 검사기가 위반을 실제로
검출하는지 픽스처로 확인한다 — 검사기만 있고 자가검증이 없으면 조용히 무력해진 게이트를 알아챌 수 없다.
둘 다 `check` 태스크와 CI `quality-gate.yml`에 걸려 있다.

`docs/check-documentation.sh`(같은 워크플로에서 실행)는 정본 문서(`AGENTS.md`·`.ai/*`·
`docs/runbooks/*`)만 검사한다. 두 검사는 대상이 겹치지 않는다.

### 변경 이력

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-09-16 | 하네스 자동 게이트 신설 — `harnessCheck`·`harnessCheckTest` 를 `check` 와 CI 에 배선 | `docs/check-harness.sh`, `docs/test-check-harness.sh`, `build.gradle.kts`, `.github/workflows/quality-gate.yml`, `CLAUDE.md`, `.claude/skills/orchestrator/SKILL.md` | 하네스 정합성을 "사람이 직접 확인한다"로 두고 있었는데, 같은 규약을 둔 자매 저장소에서 앱 rename 때 참조 4곳이 9일간 죽은 채 남았다. 수동 갱신으로는 막히지 않는다. 자매 저장소가 tech-debt 로 등록해 둔 **경로 화이트리스트 공백(`apps/`·`modules/` 미검사)은 처음부터 포함**했고, `.claude/rules/*.md` 의 `@import` 상대 경로 해석과 `SKILL.md` 없는 스킬 디렉터리 검출을 추가했다. 자가검증 픽스처 24종(정상 5 · 위반 19)이 검사기가 실제로 검출하는지를 증명한다 |
| 2026-09-16 | aic-api 하네스의 2026-09 진화분 이식 — ⓪ 판정표를 2축 망라·배타로 재작성, ⑤ 코드 계약의 밀도(전문 칸/요건 칸)와 초안 커밋, ⑥ 전용 체크 축(계획의 단언↔기존 코드), ⑦ 동결 마커 단독 커밋, ⑨ 리뷰어 판정 충돌 우선순위, `--design-model` 옵션, 형제 스킬 소유 범위, 하네스 파일 변경 진입점 | `.claude/skills/orchestrator/SKILL.md`, `.claude/agents/architect.md`, `CLAUDE.md` | 2026-08-31 이식 이후 자매 저장소가 2026-09-03~09-15 실측으로 고친 것들이 이쪽에 오지 않았다. 가장 무거운 것은 ⓪ 판정표다 — 2행이 dod `DRAFT`만 받아 **`FROZEN` 이후 구현이 중단된 상태가 네 행 어디에도 걸리지 않았고**, 그 요청은 4행(새 실행)으로 튕겨 이미 동결된 계약과 진행된 구현이 새 slug로 버려진다. 에이전트 6종과 패턴 스킬 8종은 이 저장소 도메인(MarketPair·캐시정합·fencing·배치)에 맞게 이미 재작성돼 있어 이식 대상이 아니다 |
| 2026-08-31 | aic-api 하네스 구조로 재구성 — 에이전트 6축(architect·implementer·spec-reviewer·code-reviewer·qa-agent·tech-docs), 스킬 11종(orchestrator + 패턴 8 + 벤더링 2), CLAUDE.md 포인터 신설 | `.claude/agents/**`, `.claude/skills/**`, `CLAUDE.md` | 2026-04 구성 하네스가 모듈 재편·Flyway 소유권 이동·MarketPair 재구조화를 반영하지 못해 잘못된 경로(`apps/api/.../db/migration`)와 금지된 앱 내부 기술 패키지를 지시하고 있었다 |

## 관련 문서

| 문서 | 용도 |
|------|------|
| `.ai/PROJECT_STATUS.md` | 현재 상태, TODO, 진행 상황 |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | 시스템 아키텍처, 데이터 흐름 |
| `.ai/context/project-overview.md` | 비즈니스 도메인 설명 |
| `.ai/planning/` | 작업별 계획 문서 디렉터리 (진행 기록과 완료된 작업의 동결 산출물) |
| `docs/work/{slug}/` | `feature-workflow` 산출물 — `design.md`, `plan.md`, `dod.md`, `understanding.md` |
