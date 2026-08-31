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
| 산출물 위치 | `docs/work/{slug}/` — `design.md`, `plan.md`, `dod.md`, `understanding.md` |
| 정합성 검사 | **자동 게이트 없음.** 하네스 파일을 고쳤으면 문서가 가리키는 경로가 실재하는지 직접 확인하고 아래 변경 이력에 한 행을 남긴다 |

`docs/check-documentation.sh`(CI `quality-gate.yml`에서 실행)는 정본 문서(`AGENTS.md`·`.ai/*`·
`docs/runbooks/*`)만 검사하며 `.claude/` 하네스 파일은 대상이 아니다.

### 변경 이력

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-31 | aic-api 하네스 구조로 재구성 — 에이전트 6축(architect·implementer·spec-reviewer·code-reviewer·qa-agent·tech-docs), 스킬 11종(orchestrator + 패턴 8 + 벤더링 2), CLAUDE.md 포인터 신설 | `.claude/agents/**`, `.claude/skills/**`, `CLAUDE.md` | 2026-04 구성 하네스가 모듈 재편·Flyway 소유권 이동·MarketPair 재구조화를 반영하지 못해 잘못된 경로(`apps/api/.../db/migration`)와 금지된 앱 내부 기술 패키지를 지시하고 있었다 |

## 관련 문서

| 문서 | 용도 |
|------|------|
| `.ai/PROJECT_STATUS.md` | 현재 상태, TODO, 진행 상황 |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | 시스템 아키텍처, 데이터 흐름 |
| `.ai/context/project-overview.md` | 비즈니스 도메인 설명 |
| `.ai/planning/` | 작업별 계획 문서 디렉터리 (진행 기록과 완료된 작업의 동결 산출물) |
| `docs/work/{slug}/` | `feature-workflow` 산출물 — `design.md`, `plan.md`, `dod.md`, `understanding.md` |
