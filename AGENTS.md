# Premium Spread

> 비즈니스 도메인은 `.ai/context/project-overview.md`, 현재 구조는
> `.ai/architecture/ARCHITECTURE_DESIGN.md`를 참조한다.

## Tech Stack

- Kotlin 2.0, Java 21, Spring Boot 3.5.16
- MySQL 8, Redis 7, Testcontainers
- Gradle 멀티모듈, Next.js web

## Module Structure

```text
apps/
├── api/                    # REST interfaces/application (8080, management 9080)
├── batch/                  # scheduler/application jobs (8081, management 9081)
└── web/                    # Next.js
domain/                     # Entity, Value, Policy, Service, Port
infrastructure/
├── common/                 # JPA/JDBC, Redis cache, Flyway
├── api/                    # Security/JWT/refresh session
└── batch/                  # exchange/FX/WebSocket/cache/lock/email adapters
modules/
├── jpa/                    # DataSource/JPA foundation
└── redis/                  # Redis/Redisson, key/TTL foundation
supports/
├── logging/                # correlation ID, structured logging, masking
├── monitoring/             # Actuator, Prometheus, readiness/alerts
└── email/                  # JavaMail adapter
architecture-tests/         # module/layer/dependency boundary tests
```

## Architecture Boundary

- `apps:*`에는 기술 adapter를 두지 않는다.
- API: `interfaces → application → domain`; Controller는 Application Facade 하나를 호출한다.
- Batch: `interfaces/scheduling → application job → domain port`; Scheduler는 Job 하나만 호출한다.
- `infrastructure:*`는 Domain port를 구현하며 `apps:*`를 참조하지 않는다.
- 공통 JPA/JDBC/Redis business adapter와 Flyway는 `infrastructure:common`이 소유한다.
- Security는 `infrastructure:api`, 외부 거래소/FX/WebSocket/SMTP 조합은 `infrastructure:batch`가 소유한다.
- Premium/position/notification identity는 `MarketPair(symbol, koreaExchange, foreignExchange)`다.

자세한 규칙은 `.ai/rules/architecture.md`, `.ai/rules/batch.md`, `.ai/rules/testing.md`를 따른다.

## Quick Commands

Gradle 검증은 기존 cache를 사용해 `--offline --no-daemon`으로 실행한다.

```bash
./gradlew compileKotlin --offline --no-daemon
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon

docker compose -f docker/infra-compose.yml up -d
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun --offline --no-daemon
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:batch:bootRun --offline --no-daemon

npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run build

bash docs/check-documentation.sh
```

통합 테스트는 Docker가 필요하다. 테스트에서 실거래소, 실제 SMTP, 실제 Slack으로 연결하지 않는다.

## Coding Guidelines

1. Kotlin은 불변 `val`과 순수 계산을 우선한다.
2. JPA Entity는 `data class`로 만들지 않는다.
3. 필요가 입증된 port만 만들고 과도한 추상화를 피한다.
4. 시간은 주입한 `Clock` 또는 명시적 `Instant`를 사용한다.
5. Redis key/TTL/payload를 바꿀 때 `modules:redis`와 `docs/runbooks/redis-contract.md`를 함께 갱신한다.
6. migration은 append-only이며 V12 immutable 예외를 수정하지 않는다.
7. 변경 후 compile, 관련 unit/integration, `architectureTest`를 green으로 유지한다.

## 관련 문서

| 문서 | 용도 |
|---|---|
| `.ai/PROJECT_STATUS.md` | 현재 상태, migration, known issue |
| `.ai/architecture/ARCHITECTURE_DESIGN.md` | 시스템 구조와 데이터 흐름 |
| `.ai/context/project-overview.md` | 비즈니스 규칙과 보장 수준 |
| `.ai/instructions.md` | 개발·검증 전체 지침 |
| `.ai/planning/infrastructure-boundary/progress.md` | Phase별 실행 증거 |
| `docs/runbooks/` | 운영 계약과 장애 대응 |
