# Infrastructure Boundary Refactoring Progress

> 갱신: 2026-07-15 KST

| Phase | 상태 | Commit | Push | 검증 |
|---|---|---|---|---|
| 0. 기준선/운영 데이터 점검 | COMPLETE | `cc7030e` | `origin/refactor/infrastructure-boundary` | unit 443 green, Batch integration 64개 중 25개 known failures 승인 |
| 1. 긴급 운영 안전장치 | COMPLETE | `e1f1487` | `origin/refactor/infrastructure-boundary` | unit 473 green, API integration 91개 중 90 green/1 approved disabled |
| 2. Gradle 모듈 경계/Architecture Test | COMPLETE | `8268148` | `origin/refactor/infrastructure-boundary` | unit 505 green, architecture 11 green, Batch integration은 Phase 3 retention 2건만 실패 |
| 3. 공통 Domain/계산/시간 정책 | COMPLETE | `937e092` | `origin/refactor/infrastructure-boundary` | Domain 103/API unit 240/Batch unit 184/Redis 8/Architecture 11/API integration 96 green+1 approved disabled/Batch integration 68 green |
| 4. 공통 Persistence/Redis Infrastructure | COMPLETE | `ccd5952` | `origin/refactor/infrastructure-boundary` | JPA 2, Redis 13, Common unit 40/integration 5, API unit 168/integration 108 green+1 approved disabled, Batch unit 196/integration 68, Architecture 12 green |
| 5. API Facade/인증 세션 | COMPLETE | `78e9239` | `origin/refactor/infrastructure-boundary` | Infrastructure API 29, API unit 78/integration 116, Architecture 17, Web lint/build green, disabled 0 |
| 6. Batch Port/외부 Adapter | COMPLETE | `193cf35` | `origin/refactor/infrastructure-boundary` | Domain 109, Email 7, Monitoring 7, Infrastructure Batch 34, Batch unit 38/integration 62, Architecture 21 green |
| 7. Durable Notification Delivery | COMPLETE | `6d46477` | `origin/refactor/infrastructure-boundary` | Domain 114, Email 12, Common unit 40/integration 14, Infrastructure Batch 36, API unit 83/integration 117, Batch unit 52/integration 69, Architecture 21 green |
| 8. 설정·시간·관측성·배포 | COMPLETE | `efff300` | `origin/refactor/infrastructure-boundary` | unit 454, Common integration 14, API integration 118, Batch integration 69 green |
| 9. Quality Gate/CI/문서 SSOT | COMPLETE | `ed1855b` | `origin/refactor/infrastructure-boundary` | run `29395537342`, 7/7 jobs success |
| 10. 최종 E2E/완료 Push | CLOSING | closing docs commit | push/CI 대기 | 결과·상태 문서 반영 후 동일 7-job CI로 최종 판정 |

## Phase 0 실행 기록

- `origin/dev` `a0a59ee`에서 전용 worktree와 `refactor/infrastructure-boundary` 브랜치를 생성했다.
- 원본 workspace의 사용자 변경 `.ai/instructions.md`, `.claude/worktrees/`는 수정하지 않았다.
- 최초 계획 문서를 동일 SHA-256으로 worktree에 복제한 뒤, 스펙 리뷰와 사용자 승인 내용을 실행본에 반영했다.
- default test 443개는 성공했다.
- API integration은 Docker API 1.44 보정 후 성공했다.
- Batch integration 전체 64개를 Docker API 보정 후 재실행해 25개 실패를 확인했다: retention 2건은
  Phase 3, test fixture schema drift 23건은 Phase 2 owner가 해결한다.
- Web production build는 성공했지만 lint 1건은 실패했다.
- 로컬 V12는 APPLIED/position 0건이다.
- 운영/스테이징 환경은 없으며 `NOT_DEPLOYED`로 분류했다.
- 의미가 불명확한 로컬 timestamp는 변환하지 않고 volume을 보존한 뒤 UTC 기반 새 volume/fixture를 사용한다.
- offline cache 부재로 coverage/ktlint/detekt/OWASP 결과를 만들 수 없다.
- 사용자가 Batch retention 2건(Phase 3), schema fixture drift 23건(Phase 2), Web lint 1건(Phase 5), quality tool 부재(Phase 9 CI)를
  `baseline-known-failures`로 승인했다.

## Phase 1 실행 기록

- refresh 공개 matcher를 exact POST path로 제한하고 실제 로그인 refresh cookie만으로 재발급되는 E2E를 추가했다.
- JWT secret/issuer/audience/access TTL/refresh TTL/clock skew는 local/test 외 환경에서 필수이며, 값과 서명 claim을 검증한다.
- API 공통 JPA는 `validate`, local/test만 `create-drop`이고 Batch Flyway는 비활성화했다.
- V12 외부 preflight와 API Flyway callback이 APPLIED/PENDING_EMPTY/PENDING_WITH_DATA를 같은 정책으로 차단한다.
- V11 MySQL에 position 데이터가 있는 통합 테스트에서 V12 실행 전 차단, 기존 row 보존, V12 이력 미생성을 검증했다.
- 이메일 비활성 시 SMTP/EmailSender/listener/service bean이 없고, 활성화 시 발신자와 SMTP 설정을 fail-fast 검증한다.
- 공개 Actuator는 liveness/readiness GET으로 제한하고 Batch management port는 내부 9081로 분리했다.
- Phase 1 전체 단위 테스트는 API 290 + Batch 176 + Email 7 = 473개가 모두 성공했다.
- API integration은 91개 중 90개 성공, 기존 JWT blacklist 미구현 테스트 1개는 계획된 Phase 5 항목으로 disabled 상태다.
- shell syntax, V12 분류 script, `git diff --check`가 성공했다.
- 스펙 리뷰와 코드 리뷰는 BLOCKER/MAJOR 없이 PASS했다. 리뷰 중 발견한 workflow fail-stop, 안전하지 않은
  JWT template placeholder, Boot 기본 SMTP auto-configuration 우회 경로를 수정하고 재검증했다.

## Phase 2 실행 기록

- offline cache에 없던 Kotlin DSL convention artifact를 다운로드하지 않고, `java-gradle-plugin` binary convention
  3종(`kotlin-library`, `spring-library`, `spring-boot-application`)으로 build-logic을 구성했다.
- `domain`, `infrastructure:{common,api,batch}`, `architecture-tests` 모듈을 추가하고 API/Batch는 Domain을
  compile 의존, 해당 Infrastructure와 logging을 runtime 의존으로 연결했다.
- root 전역 Spring/Jackson/Testcontainers/KAPT 의존과 container task-disable hack을 제거하고 각 모듈이 실제
  사용하는 의존성을 직접 선언했다. 미사용 QueryDSL/KAPT/`QueryDslConfig`도 제거했다.
- Kotlin 2.0/JVM 21, Docker API 1.44, JaCoCo 0.8.15와 공통 Test 정책을 convention의 단일 설정으로 통합했다.
- Domain JPA no-arg/all-open, Infrastructure `AutoConfiguration.imports`, API/Batch thin JAR 소비를 테스트했다.
- Architecture Test 11개가 Domain forbidden, Infrastructure -> apps 금지, 실제 main class-count guard,
  API bytecode 36개/Batch bytecode 31개 exact debt edge와 Gradle dependency snapshot을 검증한다.
- Kotlin const/inline으로 bytecode에서 사라지는 의존도 놓치지 않도록 실제 source root를 함께 검사하며,
  API source 27개/Batch source 20개 debt를 exact 고정하고 Domain source debt는 0개로 강제한다.
- forbidden 기술군은 JDBC/Redis/Redisson/HTTP/WebSocket/Security/JWT/SMTP/Micrometer/Jackson/Hibernate를
  계층별로 구분하고, Domain direct external dependency는 허용된 JPA/Context/Tx/Data Commons 4종만 exact 허용한다.
- logging MVC interceptor 소유권을 supports auto-configuration으로 옮기고 no-MVC Batch backoff를 검증했다.
- Boot JAR 이름을 `app.jar`로 고정하고 Docker가 exact artifact만 복사하도록 변경했다. `.dockerignore`와 신규
  모듈 build metadata cache layer를 추가해 thin JAR/host 산출물 충돌 및 dependency cache fail-open을 제거했다.
- `batch-schema.sql`의 currency/fx_rate drift를 수정했다. Repository 32개와 scheduler E2E 24개가 성공했고,
  Batch integration 64개 중 Phase 3 owner인 retention 2개만 실패했다.
- shell 환경 보정 없이 API integration 91개 중 90개 성공/기존 승인 disabled 1개, Batch Testcontainers 기동에 성공했다.
- 최신 root `compileKotlin test`가 성공했고 unit/architecture 합계 505개가 모두 성공했다. API/Batch `bootJar`와
  Dockerfile 정적 검사도 성공했으며 두 `app.jar`에서 Boot main class를 확인했다.
- local JaCoCo line baseline은 API 93.54%, Batch 92.94%, 논리 Domain 96.52%, 논리 Application 91.58%,
  API+Batch+Domain overall 93.27%로 현재 threshold deficit은 0이다. Domain 코드는 Phase 3 이동 전 API package를
  포함해 계산했으며, required CI workflow가 아직 없으므로 Phase 9 CI의 후보 SHA 결과를 최종 권위값으로 사용한다.
- 기존 Domain data class copy visibility 경고 4건은 계획대로 Phase 3 owner가 제거한다.
- 스펙 리뷰와 코드 리뷰에서 발견한 edge 단위 allowlist, source inline 우회, forbidden 기술군 누락, Domain 외부
  의존 누락, Docker dual-JAR 문제를 모두 수정했고 최종 리뷰는 BLOCKER/MAJOR/MINOR 없이 PASS했다.

## Phase 3 실행 기록

- API 내부 Domain 53개 main source와 관련 테스트를 독립 `domain` 모듈로 이동하고, 앱의 기존 Domain source는
  0개로 만들었다. `BaseEntity`도 Domain common으로 이동해 `Instant`, Clock 기반 JPA auditing,
  명시적 `delete(now)`와 proxy-safe 영속 identity equality를 적용했다.
- Entity data class를 일반 class로 바꾸고 `Symbol`, `Quote`, `MarketPair` value contract를 정리했다.
  `MarketPair`는 symbol + 한국/해외 거래소를 canonical identity로 사용하며 기존 API는
  BITHUMB/BINANCE default pair로 호환한다.
- API와 Batch의 Premium 계산을 단일 `PremiumPolicy`로 통합하고 계산/저장/entity/display scale을 분리했다.
  Batch의 `PremiumCalculator`와 복제 equivalence test는 제거하고 양 앱이 Domain policy를 직접 검증한다.
- Premium/Ticker/FX snapshot과 Port에 pair, FX source/observedAt을 보존한다. Redis payload는 요청 key와
  payload identity를 검증하며 legacy metadata 전체 부재만 default로 허용하고 부분/손상 payload는 miss 처리한다.
- Redis aggregation 범위를 `[from,to)`로 통일하고, 손상 row가 섞이면 부분 결과 대신 cache miss로 처리한다.
  coverage marker가 없는 부분 cache는 DB와 `observedAt` 기준 병합하고 동일 bucket은 DB를 정본으로 삼아
  eviction/rebuild 중 차트가 잘리지 않게 했다. nullable FX trailing-blank writer payload도 `null`로 복원한다.
- API/Batch의 직접 현재시각 사용을 Clock 또는 명시적 Instant로 교체했다. minute/hour JDBC는 UTC
  `Instant`/`Timestamp`, day는 configured business zone의 `LocalDate`로 통일하고 cron zone과 bounded metric을
  같은 `aggregation.zone`에서 파생한다.
- `TickerCacheService` retention을 저장 데이터의 score/명시적 기준시각으로 수정해 Phase 0의 2개 회귀를
  해소했고 Batch integration 68개 전체가 성공했다.
- MySQL connector/session/Hibernate timezone을 UTC로 고정하고 JVM timezone 독립 `DATETIME(6)` round-trip을
  검증했다. Testcontainers MySQL은 로컬 production 정책과 동일하게 TLS를 끄도록 `sslMode=DISABLED`를
  명시해 Docker/WSL 시각 보정 중 생성 인증서의 `NotYetValid` 비결정 실패를 제거했다.
- 최종 검증 결과: Domain 103, API unit 240, Batch unit 184, Redis 8, Architecture 11, Batch integration 68은
  전부 green이다. API integration은 97개 중 96개 green이고 기존 승인된 JWT blacklist 1개만 disabled다.
- Domain forbidden import, 앱/Domain direct `now`/system default scan, API legacy Domain source, dependency graph
  snapshot, `git diff --check`가 모두 clean이다. Architecture/spec/code 리뷰 최종 판정은 각각
  BLOCKER 0 / MAJOR 0 / MINOR 0이다.
- D-14는 contract-ready 상태다. 실제 pair-aware Redis v2 write/legacy dual-read와 production DB pair
  column/backfill/unique migration은 계획 소유권에 따라 Phase 4에서 최종 완료한다.

## Phase 4 실행 기록

- API의 JPA/JDBC adapter, Batch의 공유 repository, 양 앱의 Ticker/FX/Premium cache reader·writer를
  `infrastructure:common` persistence/cache package로 통합했다. Spring Data repository와 Domain adapter 이름을
  분리하고 marker 기반 Entity/Repository scan과 명시적 adapter import로 auto-configuration 범위를 고정했다.
- API main의 `modules:jpa`, `modules:redis`, Flyway 직접 의존을 제거하고 common이 JPA/Redis foundation과
  migration resource를 소유한다. 의존 그래프 snapshot과 아키텍처 exact debt allowlist를 새 경계로 갱신했다.
- V1~V12 migration byte를 common으로 이동하고 V12 checksum/immutable allowlist를 유지했다. V13에서
  premium snapshot/minute/hour/day에 pair 컬럼을 추가하고 BITHUMB/BINANCE backfill, NOT NULL,
  pair-aware index/unique를 적용했다. 빈 DB→V13과 V12 현재 schema→V13 통합 테스트, 번호 충돌/
  destructive SQL gate, API Flyway enabled/Batch disabled 소유권 테스트가 통과했다.
- Premium Redis는 `premium:{korea}:{foreign}:{symbol}` v2 key와 `schema_version=2`로 전환했다.
  write는 v2로만 수행하고 default pair만 legacy main/history/seconds/aggregation/summary를 dual-read하며, legacy hit은 5초
  cutover TTL로 축소한다. non-default pair는 legacy symbol-only key로 fallback하지 않고 손상 payload는
  현재시각을 합성하지 않은 채 miss/corrupt/legacy-hit bounded metric으로 기록한다. Redis 장애는 error로 기록한 뒤
  DB fallback을 허용하고, ZSet row 하나라도 손상되면 부분 집계 대신 전체 cache miss로 처리한다.
- `AfterCommitCacheExecutor`를 도입하고 실제 MySQL transaction + Redis Testcontainers 통합 테스트로
  rollback 시 DB/Redis 모두 미변경, commit 후에만 Redis 기록을 검증했다. Batch FX/집계는
  DB-first/cache-second로 통일하고 DB 실패 시 cache 미호출 회귀 테스트를 추가했다.
- active-only ID/list/exists, Position summary count query, 알려진 email unique만 Domain conflict로 변환,
  알 수 없는 DB 오류 rethrow, premium 범위 `[from,to)`, pair 별 집계 격리를 repository contract로 고정했다.
- JPA/Redis/common은 Boot auto-configuration imports와 조건부 JDBC/JPA/cache 구성을 사용한다. datasource와
  Redis 활성 플래그, 사용자 bean backoff, third-party Redisson auto-configuration 제외를 context test로 고정해
  Redis-only 및 Redis-disabled 소비자가 불필요한 DataSource/Redisson을 생성하지 않게 했다.
- 최종 검증은 JPA 2, Redis 13, Common unit 40/integration 5, API unit 168, Batch unit 196,
  Architecture 12, Batch integration 68이 전부 green이다. API integration은 109개 중 108개 green이고 기존 승인된
  JWT blacklist 1개만 disabled다. `verifyMigrations`, `git diff --check`도 green이다.
- 최종 독립 spec/code/auto-configuration 리뷰는 모두 BLOCKER 0 / MAJOR 0 / MINOR 0이다.
- Phase 4 계획의 임시 예외로 Batch main에 아래 기술 adapter 파일과 common concrete adapter 직접 edge가
  exact allowlist로 남아 있다. 이 debt는 Phase 6 commit `refactor: 배치 포트 경계와 외부 어댑터 분리`에서
  Port로 전환하고 `apps:batch -> infrastructure:common/modules:redis` compile edge와 함께 제거한다.

### Phase 6 제거 allowlist

- `application/common/JobExecutor.kt`
- `application/job/fx/FxIngestionJob.kt`
- `application/notification/PremiumThresholdNotificationService.kt`
- `cache/FxCacheService.kt`
- `cache/NotificationCooldownStore.kt`
- `cache/PremiumCacheService.kt`
- `cache/TickerCacheService.kt`
- `infrastructure/ingestion/binance/BinanceFlushJob.kt`
- `infrastructure/ingestion/bithumb/BithumbFlushJob.kt`
- `scheduler/ExchangeRateScheduler.kt`
- `scheduler/PremiumAggregationScheduler.kt`
- `scheduler/PremiumScheduler.kt`
- `scheduler/TickerAggregationScheduler.kt`
- 위 목록은 `architecture-tests/src/architectureTest/resources/batch-phase6-technical-adapter-files.allowlist`와 exact
  일치하며, common concrete adapter와 modules Redis 직접 import가 추가되면 Architecture Test가 실패한다.

## Phase 5 실행 기록

- 여섯 HTTP Controller를 각각 하나의 Application Facade만 주입하는 구조로 통일했다. Request/Response와
  Criteria/Result 경계를 분리하고 Facade가 Domain 예외를 안정된 Application error로 변환하도록 해
  interfaces의 Domain/Infrastructure import와 application의 Infrastructure import를 모두 0건으로 만들었다.
- Security/JWT/refresh cookie/cache warmup 구현을 `infrastructure:api`로 이동하고 API 앱은 이를 `runtimeOnly`로
  소비한다. public endpoint는 method+path SSOT를 사용하며 Premium/Ticker 공개 범위는 GET으로 제한했다.
- JWT issuer/audience/type/TTL/clock-skew와 validated secret을 적용하고, cookie refresh/logout에는 명시적
  Origin/Sec-Fetch-Site 검증을 추가했다. local cookie는 secure=false, prd는 secure=true와 기본 secret/HMAC key
  금지를 startup policy로 검증한다.
- Refresh 원문 대신 HMAC-SHA-256 hash, jti, memberId, expiry, familyId, generation을 Redis에 저장한다. 로그인은
  회원별 세션을 교체하고 Lua CAS rotation은 같은 family의 동시 loser만 401로 거부하며 승자 세션을 보존한다.
  grace 이후 old-token 재사용은 해당 family를 revoke하고 이전 login family는 현재 family를 건드리지 않는다.
- logout은 현재/직전 refresh proof만 원자 revoke하고 cookie를 만료시키며 204를 반환한다. Access Token은
  blacklist하지 않아 TTL까지 유효한 계약을 E2E로 고정했고 기존 disabled blacklist 테스트를 제거했다.
- Web client는 Access Token을 메모리+sessionStorage에 보관하고 Bearer/credentials를 적용한다. 초기 `/me` 401은
  공유 중인 refresh 요청 한 번으로 복구하며 204/빈 응답을 안전하게 처리한다. Position polling lint 회귀도 제거했다.
- `apps/api`와 `infrastructure/api`가 동일한 기본 Gradle `group:name`을 가져 runtime dependency가 앱 자신으로
  치환되는 문제를 발견했다. `infrastructure:api` group을 고유하게 분리하고 실제 test runtime classpath와 전체
  Spring context에서 보안 자동 설정 bean이 로드됨을 검증했다.
- Refresh grace 시간은 API JVM Clock이 아니라 Lua 내부 Redis `TIME`으로 판정해 node clock skew가 승자 family를
  잘못 revoke하지 않도록 했고, 서로 60초 어긋난 요청 시각에서도 승자 후속 회전이 유지되는 통합 테스트를 추가했다.
  Redis 시각 역행 흔적은 grace로 인정하지 않고 fail-closed 처리한다.
- 최종 검증은 Infrastructure API 29, API unit/controller 78, API integration 116, Architecture 17이 모두
  failure/error/skip 0으로 green이다. Web lint와 production build, 금지 import scan, `git diff --check`도 green이다.
- 최종 독립 spec/code 리뷰는 보정 후 BLOCKER 0 / MAJOR 0 / MINOR 0으로 PASS했다.

## Phase 6 실행 기록

- Batch Application Job을 `JobLock`, `JobRunRecorder`, `OperatorAlert`, market/FX/ticker/premium/aggregation Port만
  의존하도록 재구성하고 Scheduler는 Job 하나를 호출하는 thin interface로 이동했다. Batch 앱의 Infrastructure,
  Redis/JDBC/WebClient/MeterRegistry compile 참조와 기술 debt allowlist는 모두 0건으로 닫았다.
- 외부 FX/WebSocket, ingestion buffer, Redis time-series/aggregation 조합, 운영 metric/alert adapter를
  `infrastructure:batch`로 이동했다. 공유 cache reader/writer와 JDBC repository는 `infrastructure:common`을
  정본으로 유지하고 Batch 전용 seconds/aggregation orchestration만 batch adapter가 소유한다.
- job 설정의 fixed-rate/cron/zone/enabled와 lock key/lease/execution timeout을 validated property로 외부화했다.
  canonical `batch.scheduling.enabled`와 legacy `scheduling.enabled` 중 어느 하나라도 false이면 모든 Scheduler와
  scheduling infrastructure가 비활성화되며, scheduler/aggregation zone 불일치는 startup에서 차단한다.
- Job timeout은 virtual-thread future에 실제로 집행한다. owner-token Redis lock은 SET NX와 Lua renew/release로
  원자화했고 lease/3마다 갱신한다. timeout 취소를 I/O가 무시해도 실제 action 종료 전 lock을 해제하지 않으며,
  timeout 실패 metric/alert는 즉시 남기고 종료 뒤에만 release한다.
- WebSocket generation fencing, first-message/idle watchdog, exponential reconnect, client ping, stop/start race,
  alert hang 비차단과 Binance/Bithumb parser·timestamp·out-of-order 전체 회귀를 Infrastructure 테스트로 복원했다.
  Application flush 경로는 `TickerFlushObserver`를 통해 기존 `ws.stale`/`ticker.flush` metric을 유지하며 dead
  Infrastructure FlushJob은 제거했다.
- FX timeout/retry/status, alert queue saturation, raw USDT/canonical USD pair, flat-price seconds score/retention,
  중복 scheduler lock과 owner-token renew/release를 단위/MockWebServer/실제 Redis 통합 테스트로 고정했다.
- 정적 검증은 Batch Application 기술 구현 참조 0건, Interfaces cache/repository/client 참조 0건,
  `git diff --check` clean이다. 최종 독립 spec/code review는 BLOCKER 0 / MAJOR 0 / MINOR 0이다.
- 최종 검증은 Domain 109, Email 7, Monitoring 7, Infrastructure Batch 34, Batch unit 38,
  Batch integration 62, Architecture 21이 failure/error/skip 0으로 전부 green이다.

## 승인 및 재개 상태

2026-07-14 사용자 결정: 운영/스테이징 없음, 불명확한 로컬 timestamp는 변환하지 않는 추천안 채택,
스펙 리뷰 보정안 전체 승인. Phase 0~6 commit/push를 완료했고 Phase 7 구현·검증과 독립 리뷰를 완료해
commit/push한다.

## Phase 7 실행 기록

- Spring in-memory event/`@Async`/Redis cooldown 전달을 MySQL `notification_delivery` 큐로 교체하고,
  threshold 평가와 enqueue를 같은 transaction으로 묶었다. event key v2는 subscription ID/revision,
  canonical MarketPair, normalized direction/threshold, cooldown duration/window start를 포함한다.
- MySQL 8 `FOR UPDATE SKIP LOCKED`, row별 UUID claim token, `lockedBy + claimToken` fencing, stale recovery,
  retry/backoff/max-attempt/FAILED/redrive audit를 구현했다. concurrent enqueue/claim, rollback, stale owner,
  V13→V14 migration/backfill과 subscription optimistic lock을 실제 MySQL 통합 테스트로 검증했다.
- SMTP 전에 claim을 commit하고 stable delivery UUID를 MIME `Message-ID`로 사용한다. deadline interrupt를
  무시하는 SMTP는 실제 종료까지 concurrency permit을 점유해 다음 row를 PROCESSING으로 고립시키지 않는다.
  SMTP 성공 후 mark 실패 시 중복 가능성은 at-least-once runbook에 명시했다.
- API create/update/result에 MarketPair를 연결하되 기존 요청은 BITHUMB/BINANCE 기본값으로 호환한다.
  잘못된 enum/거래소 지역 조합은 안정된 422 Domain error로 변환한다.
- SENT PII는 30일 뒤 bounded 반복 drain으로 scrub하고 event/dedupe/audit은 보존한다. 이메일 전송을 꺼도
  retention transaction/job은 계속 동작하며 FAILED PII는 redrive/acknowledge 전 보존한다.
- delivery lifecycle metric은 bounded outcome tag만 사용하고 모든 상태 변화는 transaction after-commit에
  기록한다. worker ID는 DB `locked_by VARCHAR(100)` 한계를 넘지 않게 고정했다.
- 기존 event/async/cooldown, Batch Application 기술 구현 참조, legacy worker, `@Disabled` scan은 모두 0건이고
  `verifyMigrations`, `git diff --check`가 성공했다. 최종 spec/code review는 BLOCKER 0 / MAJOR 0 / MINOR 0이다.
- 최종 검증은 Domain 114, Email 12, Common unit 40/integration 14, Infrastructure Batch 36,
  API unit 83/integration 117, Batch unit 52/integration 69, Architecture 21이 failure/error/skip 0으로 전부 green이다.

## Phase 8 실행 기록

- datasource/Redis 설정을 각각 표준 `spring.datasource`, `spring.data.redis` SSOT로 통합하고 Hikari, Redisson,
  scheduler, notification deadline 설정을 typed properties와 교차 validation으로 fail-fast 처리했다. local/test/prd
  profile 역할과 운영 기본값 금지 정책을 문서 및 startup test로 고정했다.
- 모든 실행 시각은 `Clock` 또는 명시적 입력으로 통제하고 aggregation cron zone을 `Asia/Seoul` 정책과 일치시켰다.
  UTC 저장/KST bucket 경계와 cache timestamp 손상 시 전체 폐기 및 bounded corrupt metric을 검증했다.
- correlation ID의 요청/응답/MDC 및 async 전파, 구조화 로그 민감정보 masking, metric 이름/tag allowlist와 cardinality
  제한을 적용했다. API readiness는 DB/Redis, Batch readiness는 DB/Redis/필수 ingestion 상태를 반영한다.
- API/Batch 관리 포트를 9080/9081로 고정하고 health/Prometheus를 애플리케이션 ingress와 분리했다. 실제 별도 관리
  포트 HTTP 통합 테스트로 readiness 200/UP, Prometheus 200, 애플리케이션 포트의 actuator 404를 확인했다.
- SHA image 배포, API readiness 후 Batch 시작, migration 실패 시 중단, smoke 실패 시 이전 SHA rollback을 구현했다.
  배포 workflow는 unit뿐 아니라 migration/API/Batch/common integration과 compose 계약을 모두 선행한다.
- 운영 Hibernate SQL logger는 WARN이고 local/test에서만 DEBUG다. Prometheus scrape target, 고정 관리 포트,
  배포 workflow task를 `docker/deploy-contract-test.sh`로 회귀 방지한다.
- 독립 spec/code review의 최초 Major 5건과 Minor 2건은 운영 SQL logger, Prometheus 실제 노출, 관리 포트 원자성,
  deploy integration gate, profile 표와 실제 management HTTP 테스트로 보완했다. 최종 판정과 전체 검증 결과는
  각각 BLOCKER 0 / MAJOR 0 / MINOR 0이다.
- 최종 검증은 unit/architecture 454, Common integration 14, API integration 118, Batch integration 69가
  failure/error/skip 0으로 green이다. `verifyMigrations`, 실제 management HTTP, deploy/compose 계약,
  production direct-now/`@Value` scan과 `git diff --check`도 성공했다.
- 최초 묶음 회귀 실행에서 기존 동시 refresh E2E가 한 차례 winner 후속 회전 401을 반환했으나, 해당 경합 테스트를
  clean rerun하고 API 전체 118개를 다시 실행해 모두 성공했다. Phase 9에서 flaky 진단/반복 정책의 입력으로 보존한다.

## Phase 9 실행 기록

- Kotlin/Java warning을 오류로 처리하고 Unit, Architecture, Common/API/Batch Integration을 각각
  `src/test`, `src/architectureTest`, `src/integrationTest` source set과 timeout이 있는 독립 task로 분리했다.
  Testcontainers reuse를 끄고 외부 HTTP/WebSocket/SMTP endpoint, 승인 없는 `@Disabled`, 숨은 test retry를 정적
  gate로 차단했다. 테스트 worker의 비데몬 thread detector는 이름 allowlist 없이 Spring cached context를 실제 close한
  뒤 검사하며, Batch test context도 class 종료 시 닫아 MockWebServer/Redisson 자원을 회수한다.
- aggregate JaCoCo와 고정 exclusion allowlist를 구성했다. unit coverage 입력은 이전 integration 실행 이력에
  오염되지 않도록 각 모듈의 `test.exec`로 한정했다. clean unit-only line coverage는 overall 72.82%, Domain 93.63%,
  Application 85.80%로 각각 70%/85%/80% gate를 통과했다. cache hit/miss/legacy/corrupt/error와 Auth
  login/rotation/logout 계약 테스트를 보강했으며 Unit 476개가 failure/error/skip 0으로 green이다.
- 14개 Gradle dependency lock, Gradle 8.14.3 distribution checksum, standalone ktlint/detekt/OWASP checksum lock,
  npm high/critical audit gate와 suppression schema를 추가했다. verification metadata는 offline에서 생성하지 않고
  후보 SHA CI가 artifact를 생성한 뒤 사람이 검토·커밋해야 다음 strict CI가 진행되는 fail-closed 절차로 고정했다.
  root resolver는 compile/test/integration/runtime artifact 237개를, build-logic resolver는 자체 compile/test artifact를
  실제 materialize하며 OWASP는 테스트 의존성을 제외한 API/Batch production runtime 외부 JAR 155개를 검사한다.
- Quality Gate는 compile/architecture, unit/coverage, API integration, Batch integration, static analysis,
  dependency/security, Docker build 7개 job을 exact candidate SHA에서 실행한다. 모든 third-party action은 immutable
  SHA로 고정했고 base image도 digest로 고정했다. Docker job이 생성한 SHA-tagged API/Batch/Web archive를 해당
  workflow run artifact로 보존하고 Deploy는 재빌드 없이 같은 archive만 load/push한다. Deploy는 동일 SHA의 Quality
  Gate 성공, `main` push, `production` environment 승인 없이는 실행되지 않는다.
- 구조/도메인/개발 규칙 SSOT와 Auth, Redis, durable notification, V12, 배포/rollback, metric/alert runbook을
  현재 코드에 맞췄으며 문서 path/placeholder 계약 검사가 통과했다.
- 첫 후보 `fb202a6`의 격리 CI에서 Linux 실행 권한이 없는 `gradlew`와 기존 Kotlin formatting debt 673건을 확인했다.
  wrapper mode를 `100755`로 고정하고 ktlint 1.8로 전체 소스를 정리했으며, detekt가 검출한 22건은 테스트 source-set
  exclude 교정과 Position input value object, cache/readiness/lock 책임 분리로 해소했다. 최종 standalone ktlint와
  detekt 410파일/0 smell 결과를 확보했고 빈 detekt baseline과 전역 threshold를 유지했다.
- 로컬 최종 검증은 Unit 476, Architecture 25, Common Integration 14, API Integration 120,
  Batch Integration 69가 failure/error/skip 0으로 green이다. migration gate, test isolation/coverage exclusion,
  CI/deploy/documentation contract, Web Node 20 `npm ci`/lint/production build와 npm high/critical 0건도 통과했다.
- 최초 독립 spec/code review에서 확인한 metadata runtime 누락, OWASP scan/cache, required 계약 미연결,
  source set 분리, thread lifecycle, endpoint allowlist, deploy 재빌드 문제를 모두 보완했다. 최종 독립 spec/code
  재리뷰는 각각 BLOCKER 0 / MAJOR 0 / MINOR 0이다.
- 저장소 기본 branch는 `dev`이고 `main` branch, branch protection, `production` Environment는 현재 없다.
  운영/스테이징도 `NOT_DEPLOYED`이므로 외부 보호 설정 완료를 주장하지 않는다. 후보 SHA push 후 verification metadata
  검토 커밋과 strict GitHub Quality Gate 결과를 Phase 9 최종 증거로 추가한다.
- 보완 후보 `514c2a1`의 Quality Gate run `29384982990`에서 static analysis는 성공했고, compile bootstrap은
  verification metadata artifact를 생성·업로드한 뒤 후속 검토 커밋을 요구하며 의도대로 fail-closed했다. 검토한
  root metadata는 575 components/1,006 artifacts/1,006 SHA-256이며 파일 SHA-256은
  `6ed8aec8f3830854863d1d0793e25f2a2880ebca57d9a320edf40fbbb8dab2f3`, build-logic metadata는
  69 components/123 artifacts/123 SHA-256이며 파일 SHA-256은
  `6ec8815a4f7d5398b91da18f7df994b071bde5fb15685e9df4715188799522a1`이다. 두 파일 모두 trusted/ignored artifact,
  SHA-1/MD5, HTTP/file repository, reason 기반 우회, PGP 예외가 없고 `verify-metadata=true`이다. 로컬 strict offline
  실행은 현재 로컬 캐시에 Kotlin Gradle plugin/allopen/noarg 2.0.20 artifact가 없어 materialize 전에 중단됐으며,
  metadata mismatch는 관찰되지 않았다. 추가 다운로드는 하지 않고 후속 커밋의 격리 CI strict 실행을 최종 권위값으로
  사용한다.
- metadata 후속 후보 `988774a`의 strict Quality Gate run `29385215551`에서 compile/architecture, unit/coverage,
  API integration, ktlint/detekt가 성공했다. 격리 runner가 추가로 검출한 실패는 (1) Docker dependency layer의 coverage
  exclusion 파일 누락, (2) 분리한 Premium cache operation 구현체와 facade 사이의 자기주입 순환 참조, (3) 빈 NVD
  DB를 GitHub 공유 IP에서 API key 없이 초기화할 때 발생한 NVD 429 세 건이다. API/Batch image에는 설정 시점 입력을
  첫 Gradle 실행 전에 복사하고 이를 contract로 고정했으며, 세 operation 구현체를 auto-configuration에 등록하고
  명시적 primary로 지정했다. OWASP는
  fail-open 또는 update 생략 없이 NIST CVE 2.0 static datafeed로 초기 DB를 만들고 완성된 H2 data directory를 다음
  실행이 복원하도록 cache namespace를 교체했다. 후속 후보 CI에서 세 gate를 다시 판정한다.

- 이후 격리 CI에서 확인된 runtime artifact/checksum, Docker context, Spring context, NVD datafeed 및 CVE
  좌표 문제를 각각 재현 가능한 계약 테스트로 보완했다. 최종 root verification metadata는 967 artifacts,
  build-logic metadata는 143 artifacts를 각각 artifact별 SHA-256 하나로 고정한다. 14개 dependency lock과 wrapper,
  standalone ktlint/detekt/OWASP checksum도 strict 검증한다.
- OWASP suppression은 production runtime의 정확한 좌표/버전과 실제 오탐 CVE/CPE에만 적용한다. Tomcat 계열은
  확인된 19개 CVE를 열거해 새 CVE가 자동으로 숨겨지지 않으며, 모든 suppression은 reason/owner/expires/until을
  가진다. NVD API key 없이 NIST CVE 2.0 static datafeed로 격리 DB를 구성하고 update/scan 실패는 fail-closed다.
- 코드 후보 `ed1855b91dd4a228bb2d96d6ee0f11c2ca98b580`의 GitHub Quality Gate run
  `29395537342`은 7개 job을 모두 성공했다. Unit 476, Architecture 25, Common Integration 14,
  API Integration 120, Batch Integration 69가 failure/error/skip 0이며 coverage는 overall 72.82%,
  Domain 93.63%, Application 85.80%, 미해결 CVSS 7 이상은 0건이다.

## Phase 10 실행 기록

- 완료 조건 A/D/S/N/O/Q를 구현 파일, 검증, 결과, 대표 commit에 연결한
  `docs/superpowers/plans/2026-07-14-infrastructure-boundary-refactoring-result.md`를 작성했다.
- 외부 저장소 상태를 다시 확인한 결과 default branch는 `dev`, `main` 조회는 404, GitHub Environment는 0개다.
  따라서 Q-11은 `NOT_CONFIGURED`, 운영/스테이징은 `NOT_DEPLOYED`로 유지하며 내부 구현 완료로 대체하지 않는다.
- 결과·상태 문서를 포함하는 closing commit을 push한 뒤 동일 Quality Gate 7개 job이 모두 성공해야 Phase 10의
  저장소 내부 완료를 확정한다. 결과 문서에 그 run 번호를 다시 쓰는 재귀 commit은 만들지 않고, 최종 원격 SHA와
  run URL을 인수인계 시 외부 증거로 남긴다.
