# Premium Spread 개발 지침

> 이 문서는 저장소 작업의 entry point다. 비즈니스 의미는 `context/project-overview.md`, 구조는
> `architecture/ARCHITECTURE_DESIGN.md`, 세부 규칙은 `rules/`가 정본이다.

## 기술과 실행 경계

- Kotlin 2.0 / Java 21 / Spring Boot 3.4
- MySQL 8 / Redis 7 / Testcontainers
- API `8080`, Batch `8081`; management는 각각 `9080`, `9081`
- DataSource는 `spring.datasource`, Redis는 `spring.data.redis`가 설정 SSOT다.
- Flyway 실행 owner는 API 하나이며 Batch migration은 비활성이다.
- 시간 저장은 UTC, day bucket과 cron zone은 `aggregation.zone`(기본 `Asia/Seoul`)을 사용한다.

## 모듈과 의존 방향

```text
HTTP/Schedule → apps interfaces → apps application → domain ← infrastructure adapters
                                                       ↑
                                      modules foundations / supports
```

| 모듈 | 책임 |
|---|---|
| `apps:api` | REST Controller, Request/Response, Application Facade/DTO |
| `apps:batch` | thin Scheduler, Application Job, delivery orchestration |
| `domain` | Entity, Value, Policy, Service, Port, Snapshot |
| `infrastructure:common` | JPA/JDBC, Redis business cache, Flyway V1~V14 |
| `infrastructure:api` | JWT/cookie/refresh session, API Security |
| `infrastructure:batch` | 외부 거래소/FX/WebSocket, Redis job/cache, SMTP adapter |
| `modules:jpa`, `modules:redis` | 기술 foundation과 공통 설정/key/TTL |
| `supports:*` | logging, monitoring, email의 재사용 auto-configuration |

- 앱 main source에는 JDBC/Redis/WebClient/MeterRegistry 같은 기술 구현을 두지 않는다.
- Controller는 Application Facade 하나, Scheduler는 Application Job 하나를 호출한다.
- Application은 Domain service/port만 의존한다. Infrastructure concrete type을 import하지 않는다.
- Infrastructure는 앱을 참조하지 않고 Domain port를 구현한다.
- `domain`의 허용된 framework 계약은 JPA API와 Spring Context/Tx/Data Commons뿐이다.
- Facade는 선택 사항이 아니다. HTTP 유스케이스의 interfaces/application 경계를 항상 유지한다.

세부 기준은 `rules/architecture.md`와 `rules/batch.md`를 따른다.

## Domain과 데이터 계약

- Premium, Position, Notification은 `MarketPair(Symbol, KoreaExchange, ForeignExchange)`를 identity에 포함한다.
- 기존 symbol-only 호출만 `BITHUMB/BINANCE` 기본 pair로 호환한다.
- Premium 계산은 Domain `PremiumPolicy`만 사용한다.
- cache→DB fallback과 cache/DB 병합은 infrastructure가 소유한다.
- 범위 query는 `[from,to)`이고 손상 cache는 부분 성공 대신 전체 miss다.
- DB+cache write는 DB-first 또는 transaction after-commit을 사용한다.
- Redis business payload는 `schema_version=2`; writer는 pair-aware premium v2만 기록한다.
- notification은 MySQL durable queue의 at-least-once 전달이며 exactly-once를 문서나 API에서 주장하지 않는다.

## DTO와 naming

- interfaces: `*Request`, `*Response`
- application: `*Criteria`, `*Result`
- domain input: `*Command`; read model: `*Snapshot`
- JPA Entity: suffix 없음, 일반 class, enum은 `EnumType.STRING`
- 기술 구현은 `*Adapter`, Spring Data interface는 `SpringData*Repository`, JDBC query/write 역할을 이름으로 구분한다.

## 테스트와 검증

새 Gradle 배포본/의존성/cache를 검증 도중 다운로드하지 않는다. 로컬 Gradle 명령은
`--offline --no-daemon`을 사용한다.

```bash
./gradlew compileKotlin --offline --no-daemon
./gradlew test architectureTest --offline --no-daemon
./gradlew :infrastructure:common:integrationTest --offline --no-daemon
./gradlew :apps:api:integrationTest --offline --no-daemon
./gradlew :apps:batch:integrationTest --offline --no-daemon
./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon
bash docs/check-documentation.sh
```

- unit은 외부 I/O를 mock/fake로 격리한다.
- integration은 `@Tag("integration")`과 Testcontainers를 사용한다.
- test profile에서 실제 거래소, 실제 SMTP, 실제 Slack endpoint를 사용하지 않는다.
- architecture test가 모듈 dependency, 금지 import, source/bytecode debt를 검증한다.
- disabled test로 계약을 미루지 않는다.
- 테스트 task의 timeout/resource leak/coverage 정책은 `rules/testing.md`를 따른다.

Web 검증:

```bash
npm --prefix apps/web ci
npm --prefix apps/web run lint
npm --prefix apps/web run build
```

## Runtime과 운영 변경

- local/test/prd 설정 역할은 `docs/runbooks/configuration-profiles.md`가 정본이다.
- Redis 변경은 `docs/runbooks/redis-contract.md`, 인증 변경은 `docs/runbooks/auth-security.md`를 함께 갱신한다.
- migration은 append-only다. V12 파일/checksum은 변경하지 않는다.
- 배포 단위는 commit SHA image이며 API migration/readiness 뒤 Batch를 시작한다.
- rollback은 이전 image만 재기동하고 DB down migration을 수행하지 않는다.
- metric tag에 email/member/token/cookie/delivery ID 같은 PII·고 cardinality 값을 넣지 않는다.

## HTTP와 Git

- endpoint를 추가/변경하면 `http/api/{domain}.http`를 함께 갱신하고 `http/README.md`를 따른다.
- branch: `<type>/<short-description>` (`feat|fix|refactor|docs|test|chore`)
- commit: `<type>: <한국어 subject>`와 필요한 검증 bullet
- 기능/리팩터링 계획과 진행 증거는 `.ai/planning/{topic}/`에 둔다.
- 과거 계획 문서는 당시 경로를 보존하는 역사 기록이다. 현재 구조 설명으로 사용하지 않는다.

## 문서 정본

| 문서 | 책임 |
|---|---|
| `architecture/ARCHITECTURE_DESIGN.md` | 최종 모듈 구조와 데이터 흐름 |
| `context/project-overview.md` | MarketPair, 계산, 알림 보장 |
| `PROJECT_STATUS.md` | migration 상태와 known issue |
| `rules/architecture.md` | source/module boundary |
| `rules/http.md` | endpoint/public policy 변경 절차 |
| `rules/batch.md` | Job/port/외부 adapter 규칙 |
| `rules/testing.md` | test 격리와 quality gate |
| `../docs/runbooks/` | 운영·복구 절차 |
