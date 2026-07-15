# Premium Spread 시스템 아키텍처

> 구현 기준(As-Is), 2026-07-15. 과거 이슈 계획은 역사 기록이며 이 문서와 실제 코드가 현재 구조의 SSOT다.

## 시스템 경계

Premium Spread는 한국 현물과 해외 헤지 거래소의 시세, USD/KRW 환율을 수집해 `MarketPair`별
프리미엄을 계산한다. API는 조회·회원·포지션·알림 구독을 제공하고, Batch는 시세/환율 수집,
프리미엄 계산, 집계, 내구성 이메일 전달을 수행한다.

- API application port: `8080`, management port: `9080`
- Batch application port: `8081`, management port: `9081`
- 저장 시각과 minute/hour DB bucket: UTC `Instant`/`DATETIME(6)`
- day bucket과 집계 cron: `aggregation.zone`(기본 `Asia/Seoul`)
- API만 Flyway migration을 수행하고 Batch는 `spring.flyway.enabled=false`다.

## 모듈 구조와 책임

```text
premium-spread/
├── apps/
│   ├── api/                    # interfaces/api + application, REST 조립 루트
│   ├── batch/                  # interfaces/scheduling + application/job, 스케줄 조립 루트
│   └── web/                    # Next.js 클라이언트
├── domain/                     # Entity, Value, Policy, Service, Port; 기술 adapter 없음
├── infrastructure/
│   ├── common/                 # JPA/JDBC, Redis cache, Flyway V1~V14
│   ├── api/                    # Security/JWT/refresh session, cache warmup
│   └── batch/                  # 거래소/FX/WebSocket, cache orchestration, lock/metric/email adapter
├── modules/
│   ├── jpa/                    # DataSource/JPA foundation, auditing
│   └── redis/                  # Redis/Redisson foundation, key/TTL/time-series 공통 정책
├── supports/
│   ├── logging/                # correlation ID, MDC 전파, 구조화 로그와 masking
│   ├── monitoring/             # Actuator, Prometheus, readiness, 운영자 alert
│   └── email/                  # 조건부 JavaMail SMTP adapter
└── architecture-tests/         # 모듈/소스/bytecode 경계와 dependency graph 회귀 방지
```

앱에는 기술 adapter를 두지 않는다. API Controller는 하나의 Application Facade를 호출하고,
Batch Scheduler는 하나의 Application Job을 호출한다. Application은 Domain service/port만 의존하고,
`infrastructure:*`가 port를 구현한다. 앱은 자기 infrastructure를 `runtimeOnly`로 소비하며 Spring Boot
auto-configuration으로 조립한다.

```text
HTTP/Schedule → apps interfaces → apps application → domain ← infrastructure adapters
                                                       ↑
                                      modules foundations / supports
```

`domain`은 JDBC, Redis, HTTP/WebSocket, Security/JWT, SMTP, Micrometer, Jackson/Hibernate 구현을
참조하지 않는다. 영속 Entity 계약 때문에 허용한 직접 외부 경계는 JPA API, Spring Context/Tx,
Spring Data Commons뿐이며 Architecture Test가 exact dependency graph와 금지 import를 검사한다.

## 핵심 식별자: MarketPair

`MarketPair = Symbol + koreaExchange + foreignExchange`가 프리미엄, 구독, position의 canonical identity다.

- 한국 거래소는 `ExchangeRegion.KOREA`여야 한다.
- 해외 거래소는 거래 가능한 `ExchangeRegion.FOREIGN`이어야 하고 `FX_PROVIDER`는 거절한다.
- canonical key는 `{symbol}:{KOREA_EXCHANGE}:{FOREIGN_EXCHANGE}`다.
- 기존 symbol-only API는 `BITHUMB/BINANCE` 기본 pair로 호환한다.
- Batch runtime은 현재 `batch.market`에 설정한 한 pair를 수집한다. 저장/조회 모델은 다중 pair를 구분한다.

## 데이터 흐름

### 실시간 시세와 프리미엄

```text
Binance/Bithumb WebSocket
  → infrastructure:batch WebSocket client/ingestion buffer
  → ticker latest Hash 갱신
  → apps:batch TickerFlushJob (1초, owner-token lock/timeout)
  → ticker seconds ZSet

FX API (30분)
  → FxIngestionJob → MySQL 먼저 commit → Redis FX cache

PremiumRealtimeJob (1초)
  → Domain TickerReadPort + FxRateReadPort
  → Domain PremiumPolicy
  → pair-aware Premium current/seconds/history cache
  → PremiumThresholdEvaluator
```

WebSocket connection은 first-message timeout, idle watchdog, generation fencing, exponential reconnect를
사용한다. ticker flush는 관측값이 10초보다 오래되면 기록을 중단한다. 외부 거래소/FX 구현과 Redis/JDBC
구현은 모두 infrastructure에 있고 Batch Application Job은 port만 호출한다.

### 집계와 조회

```text
seconds → minute → hour → day
 Redis      Redis     Redis    MySQL
   └──────── 각 bucket MySQL 영속화 ────────┘

API Controller → Facade → Domain Service/Port
                              → pair-aware Redis hit
                              → miss/error 시 MySQL fallback
```

- 모든 범위는 `[from, to)`다.
- Redis row 하나라도 손상되면 부분 결과를 반환하지 않고 cache miss로 처리한다.
- coverage가 불완전한 cache와 DB를 합칠 때 `observedAt`을 기준으로 병합하며 같은 bucket은 DB가 정본이다.
- DB write와 cache write가 함께 필요한 경로는 DB-first 또는 transaction after-commit을 사용한다.

### 알림 구독과 전달

```text
API subscription CRUD → notification_subscription (MarketPair + revision + optimistic lock)

PremiumRealtimeJob
  → 활성 구독 threshold 평가
  → 같은 DB transaction에서 notification_delivery PENDING enqueue
  → poller: FOR UPDATE SKIP LOCKED + row별 claim token
  → transaction commit 후 SMTP
  → SENT 또는 지수형 retry → max attempts 후 FAILED
```

`event_key v2`는 subscription ID/revision, canonical pair, direction, 정규화 threshold, cooldown 기간과
window start를 포함하고 unique 제약으로 중복 enqueue를 막는다. 전달은 **at-least-once**다. SMTP 수락 후
`markSent` DB transaction이 실패하면 재전송될 수 있으므로 exactly-once를 주장하지 않는다. claim token은
stale worker의 상태 전이를 차단하고, `delivery_id` 기반 고정 `Message-ID`는 추적 보조 수단일 뿐
메일 제공자의 dedupe를 가정하지 않는다.

FAILED redrive는 공개 HTTP가 아니라 인증된 운영 DB 절차이며 actor/reason audit를 남긴다. SENT의 이메일,
제목, 본문 PII는 기본 30일 뒤 bounded batch로 scrub한다. 자세한 계약은
[`docs/runbooks/durable-notification-delivery.md`](../../docs/runbooks/durable-notification-delivery.md)를 따른다.

## 인증 경계

- Access/Refresh JWT는 issuer, audience, token type, jti, expiry를 검증한다.
- Access Token은 `Authorization: Bearer`로 전달하며 서버 blacklist에 저장하지 않는다.
- Refresh Token 원문은 HttpOnly cookie에만 두고 Redis에는 HMAC hash와 family/generation만 저장한다.
- refresh는 Redis Lua CAS로 회전하며 동시 loser를 401로 거절한다. grace 이후 재사용은 해당 family를 revoke한다.
- logout은 refresh family/cookie만 폐기한다. 이미 발급된 Access Token은 만료까지 유효하다.
- cookie endpoint(`/refresh`, `/logout`)는 Origin/Sec-Fetch-Site를 검증한다.
- method+path 공개 목록은 `PublicEndpointPolicy`가 Security와 contract test에 함께 제공한다.

전체 cookie/public endpoint 계약은
[`docs/runbooks/auth-security.md`](../../docs/runbooks/auth-security.md)를 따른다.

## Redis와 영속성

Business cache payload version은 `schema_version=2`다. Premium writer는 pair-aware v2 key만 기록한다.
기본 `BITHUMB/BINANCE` pair만 symbol-only legacy key를 읽을 수 있고, legacy hit은 TTL을 최대 5초로
줄이기만 한다. non-default pair는 legacy fallback을 사용하지 않는다. 상세 key/TTL/payload는
[`docs/runbooks/redis-contract.md`](../../docs/runbooks/redis-contract.md)가 SSOT다.

Flyway migration은 `infrastructure:common`이 소유한다. V12는 position을 비우는 immutable destructive
migration이므로 preflight 승인 없이는 실행하지 않는다. V13은 premium 저장소를 MarketPair로 확장하고,
V14는 구독 pair/revision과 durable delivery queue를 추가한다. V12 checksum/파일은 수정하지 않으며
[`docs/runbooks/v12-migration.md`](../../docs/runbooks/v12-migration.md)의 cutover 절차를 따른다.

## 관측성과 배포

- `X-Request-Id`를 응답/MDC/async 작업에 전파하고 비밀·토큰·cookie·이메일을 masking한다.
- API readiness는 MySQL/Redis, Batch readiness는 MySQL/Redis와 필수 ingestion freshness를 포함한다.
- Prometheus와 health는 별도 management port의 내부 network에서만 노출한다.
- metric tag는 bounded allowlist를 사용하고 이메일/member/token/delivery ID를 tag로 만들지 않는다.
- 배포 단위는 40자리 commit SHA image다. API가 migration/readiness를 통과한 뒤 Batch를 시작한다.
- rollback은 이전 application image로만 수행하며 DB down migration은 하지 않는다.

운영 절차는 [`docs/runbooks/observability-readiness.md`](../../docs/runbooks/observability-readiness.md),
[`docs/runbooks/metrics-alerting.md`](../../docs/runbooks/metrics-alerting.md),
[`docs/runbooks/deployment.md`](../../docs/runbooks/deployment.md)를 따른다.
