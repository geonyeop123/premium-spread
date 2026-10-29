# 테스트 실행 시간 단축 (issue #104) — 설계

> dod: `docs/work/test-time-reduction/dod.md` · plan: `docs/work/test-time-reduction/plan.md`
> 브랜치 `chore/issue-104-test-time-reduction` (base `origin/dev`)
> **비즈니스 코드·API·스키마 변경 없음.** 바뀌는 것은 테스트 소스, 빌드 스크립트, CI workflow와 그 계약 테스트,
> 하네스·정본 문서뿐이다. 앱·도메인·infrastructure `src/main` 은 한 줄도 고치지 않는다.

## 0. 배경과 측정 근거 (2026-10-28)

| 구간 | 현재 | 원인 |
|---|---|---|
| CI 가장 긴 경로 | `1. Compile`(~2m) → `3. API integration`(6.4~7.3m) = PR당 8.5~10m | 모든 job이 `needs: compile-architecture`로 직렬. API job 안에서 `:infrastructure:common:integrationTest` 약 3m12s |
| common IT (CI) | 3m12s 중 테스트 합 80s | 16개 클래스가 각자 `MySQLContainer("mysql:8.0")` 기동 |
| common IT (로컬) | 585s, 그중 `JdbcAccountPnlAlertStoreIntegrationTest` 24건 163s | adapter 테스트가 **테스트마다** `flyway.clean()`+`migrate()` (약 6.8s) |
| batch IT (CI) | 3.3m, 테스트 합 30s · Spring context 23회 기동 합 77.5s | `BatchIntegrationTestBase` 의 `@DirtiesContext(AFTER_CLASS)` — `@Nested` 클래스도 각자 닫힌다 |
| unit (CI) | 3.2~4.8m, 테스트 합 108s (`infrastructure:batch` 46.5s) | MockWebServer 2초 지연이 timeout 200ms 테스트에서 그대로 sleep. 모듈 직렬 실행 |
| setup-gradle post | job마다 20~35s | 캐시 저장 |
| 로컬 orchestrator ⑩ | 직렬 5회 Gradle 호출 합 ~17m | test+architectureTest 149s · common IT 585s · api IT 152s · batch IT 133s · verifyMigrations 5s |

## 1. 범위

**포함** — 사용자 합의 ③의 P1~P6 그대로.

| ID | 내용 | 결정 |
|---|---|---|
| P1 | common IT: 모듈 공용 MySQL 컨테이너 + 클래스별 database, adapter 테스트는 migrate 1회 + 테스트마다 데이터 초기화 | D6, D7 |
| P2 | batch IT: `@DirtiesContext` 제거, 공용 MockWebServer 매 테스트 초기화, FixedClock 설정 통합 | D5 |
| P3 | orchestrator ⑩ 를 Gradle 한 번 호출 + `--parallel` 로 | D8 |
| P4 | CI: job 간 `needs` 제거, common IT 를 별도 job 으로 분리 | D1 |
| P5 | CI: PR 은 Gradle cache 읽기 전용 | D2 |
| P6 | unit: MockWebServer 2초 지연 단축 + 모듈 병렬 실행 | D3, D4 |

**제외**

- `apps/api` 의 전용 컨테이너 2개(`TradePreparationConcurrencyIntegrationTest`, `TradePreparationActiveTrackingContractTest`) —
  create-drop이 Flyway 제약을 지우는 문제 때문에 의도된 설계다. 손대지 않는다.
- batch 커스텀 context(`@TestPropertySource`·`@MockkBean`·`@SpykBean`·전용 `@Import`) 6개의 통합.
- WebSocket 회귀 테스트의 sleep/backoff, `BinanceFuturesManualAccountAdapterTest` 의 1초 지연(재측정만 한다).
- 테스트 retry, run 간 container reuse, `@Disabled`, leak-detection 완화, Gradle build cache 재활성화(Kotlin CVE).
- 테스트 JVM 내부 병렬(`maxParallelForks=1`, JUnit parallel) — 유지한다.
- `apps/api` 통합 테스트 구조 변경.

## 2. 설계 체크리스트 (architect 필수 항목)

| 항목 | 이번 작업 |
|---|---|
| Facade / Domain port / infrastructure 배치 | 해당 없음 — 프로덕션 코드 무변경 |
| MarketPair identity | 해당 없음 — 테스트 픽스처가 pair 데이터를 바꾸지 않는다 |
| 시간·범위 계약 | 해당 없음. 단, 공용 JDBC URL은 기존 테스트와 같은 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` 를 유지한다(D6) |
| DB·캐시 순서 | 해당 없음 |
| Flyway | 새 migration 없음. 테스트가 migration 을 적용하는 방식만 바뀐다(D7) |
| 배치 lock/lease/JobResult | 해당 없음 |
| 알림 event identity | 해당 없음 |
| 공개 endpoint | 해당 없음 |
| 테스트 전략 | §5 |

## 3. 결정

### D1. CI job 병렬화와 common IT 분리 (P4)

- **모든 job에서 `needs: compile-architecture` 를 제거한다** — test/integration 계열뿐 아니라 `static-analysis`·
  `dependency-security`·`docker-build` 도 포함한다.
  - 근거: Docker build(2.9~3.8m)에 `needs` 를 남기면 경로가 compile ~2m + 3.8m ≈ 5.8m 로 목표(6m)에 붙는다.
    `static-analysis`·`dependency-security` 는 Gradle을 쓰지 않아 compile 결과와 무관하다.
  - `needs` 가 지키던 것은 "strict dependency verification 이 끝나기 전에 미검증 의존성으로 빌드하지 않는다"였다.
    이 성질은 job별로 이미 보장된다: Quality Gate의 **모든** `run: ./gradlew` 는 `--dependency-verification strict`
    이고 계약 테스트가 이를 강제한다(`ci/quality-gate-contract-test.sh` 의 strict 루프). 미검증 artifact 가 있으면
    각 job이 스스로 실패한다(fail-closed). 캐시 오염 경로는 D2 가 막는다 — bootstrap marker 는 `pull_request`
    에서만 허용되고(`ci/check-dependency-bootstrap-request.sh`), PR은 cache 를 쓰지 않는다.
  - 비용: compile 이 실패해도 다른 job이 끝까지 돈다(runner 분). 같은 SHA 재실행은 `concurrency` 가 취소한다.
- **`common-integration` job 을 새로 만든다.** `:infrastructure:common:verifyMigrations :infrastructure:common:integrationTest`
  를 여기서 돌리고, `api-integration` 은 `:apps:api:integrationTest` 만 돈다.
- **기존 job id·name 은 바꾸지 않는다.** 새 job 은 YAML **맨 끝**에 `8. Common integration` 으로 붙인다.
  - [ASSUMPTION] 저장소 branch protection 의 required status check 가 기존 job name(`3. API integration` 등)을
    가리킬 수 있다. 번호를 다시 매기면 required check 가 영원히 pending 이 된다. 그래서 번호를 유지하고 새 job 에
    8 을 준다.
  - [DECISION_NEEDED] required status check 를 쓰고 있다면 저장소 관리자가 `8. Common integration` 을 required 에
    **추가해야** common IT 실패가 merge 를 막는다. 저장소 밖 설정이라 이 PR이 바꿀 수 없다.
- 증거 artifact 는 job 별로 나눈다: `common-integration-${{ github.sha }}`(common 리포트),
  `api-integration-${{ github.sha }}`(api 리포트만). upload 수 6 → 7.

변경 후 job 그래프(전부 동시 시작):

```text
1. Compile + architecture   2. Unit + coverage   3. API integration   4. Batch integration
5. ktlint + detekt          6. Dependency + security scan            7. Docker image build
8. Common integration
```

예상 wall-clock = max(각 job) ≈ Docker build 3.8m 또는 unit/API job ≈ 3.5~4.5m (목표 ≤ 6m).

### D2. PR 은 Gradle cache 읽기 전용 (P5)

모든 `gradle/actions/setup-gradle` step 에 아래 입력을 둔다.

```yaml
          cache-read-only: ${{ github.ref != 'refs/heads/dev' }}
```

- `dev` push 만 cache 를 쓴다. PR(`refs/pull/N/merge`)과 `main` push 는 읽기만 한다. PR 은 base(`dev`) 의
  cache 를 읽을 수 있다.
- compile job 의 기존 `cache-disabled: ${{ steps.bootstrap-request.outputs.requested == 'true' }}` 는 그대로
  두고 `cache-read-only` 를 한 줄 추가한다. disabled 가 true 면 read-only 는 의미가 없으므로 충돌하지 않는다.
- [ASSUMPTION] setup-gradle v4.4.1 의 `cache-read-only` 기본값은 "기본 브랜치가 아니면 true" 다. 저장소 기본
  브랜치가 `dev` 가 아니면 PR 은 이미 읽기 전용이었고, 측정된 post 단계 20~35s 의 일부는 저장이 아닌 다른 작업일 수
  있다. 명시 설정은 의도(dev 만 cache 를 쓴다)를 고정하는 것이 목적이며, 효과는 D9 의 PR run 측정으로 확인한다.

### D3. unit 병렬은 `--parallel` CLI 플래그로 준다 (P6)

- `gradle.properties` 에 `org.gradle.parallel=true` 를 **넣지 않는다.** CI unit 명령과 ⑩ 명령, code-reviewer 의
  unit 게이트 명령에 `--parallel` 을 명시한다.
- 근거: `gradle.properties` 는 Dockerfile 이 dependency layer 로 복사하고(`apps/api/Dockerfile`·`apps/batch/Dockerfile`
  의 `gradle.properties gradle.lockfile ./`), `ci/generate-dependency-bootstrap.sh` 의 `resolveAndLockAll --write-locks`
  도 같은 설정을 읽는다. 전역화하면 Docker 빌드와 lock 생성 경로까지 병렬로 바뀌어 이번 범위 밖의 표면이 움직인다.
  플래그는 로컬 ⑩·CI unit 두 곳에서 같은 문자열로 쓰므로 일관성도 유지된다.
- 영향 확인: `ci/local-offline-contract-test.sh` 는 `gradlew help` 만 부르므로 무관하다. `ci/quality-gate-contract-test.sh`
  는 unit 명령 문자열을 단언하지 않았다 — 이번에 정확한 문자열 단언을 추가한다(D1 계약 테스트).
- 테스트 JVM 내부 `setMaxParallelForks(1)`(`build-logic/.../KotlinLibraryConventionPlugin.java:40`)은 유지한다.

CI unit 명령:

```text
./gradlew test jacocoTestReport jacocoTestCoverageVerification --parallel --dependency-verification strict --no-daemon
```

### D4. MockWebServer 2초 지연을 1초로 (P6)

MockWebServer 는 지연을 응답 스레드의 sleep 으로 구현하고 `shutdown()` 이 그 스레드를 기다린다. timeout 을
줄여도 지연이 끝날 때까지 테스트가 묶인다. 그래서 지연 자체를 줄인다.

| 파일:라인 | 현재 | 변경 |
|---|---|---|
| `infrastructure/batch/src/test/.../exchange/bithumb/BithumbPrivateAccountAdapterTest.kt:101` | `setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)` (timeout 200ms, :102) | `setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS)` |
| 같은 파일 `:345` | `setBodyDelay(2, ...)` (timeout 200ms, :347) | `setBodyDelay(1, ...)` |
| `infrastructure/batch/src/test/.../exchange/ExchangeRateClientTest.kt:168` | `.setBodyDelay(2, TimeUnit.SECONDS)` ×2 응답 (readTimeout 200ms, :172) | `.setBodyDelay(1, TimeUnit.SECONDS)` |
| 같은 파일 `:180` | `setHeadersDelay(2, TimeUnit.SECONDS)` ×2 응답 (readTimeout 200ms, :183) | `setHeadersDelay(1, TimeUnit.SECONDS)` |

- 값 1초의 근거: timeout 200ms 의 5배라 지연이 timeout 보다 먼저 끝날 수 없다(지연은 하한이다). 같은 모듈의
  `BinanceFuturesManualAccountAdapterTest.kt:189`, `DiscordWebhookSenderAdapterTest.kt:237·262·318` 이 이미 1초
  지연 + 짧은 timeout 조합을 쓴다 — 저장소 선례와 같은 값이다.
- 기대 절감은 **지연을 쓰는 메서드 4개 각각 약 1초**다. 응답 2개를 enqueue 하는 ExchangeRate 두 테스트도 메서드당 약
  1초만 준다 — `maxRetries=1` 의 재시도는 첫 응답 지연이 끝나기 전에 새 연결에서 시작되어 두 응답의 지연이 서로 겹치므로,
  메서드 시간은 지연 2회의 합이 아니라 대략 지연 1회분이 줄어든다. Bithumb 두 테스트도 각 1회분이다. 판정은 클래스 합계가
  아니라 메서드별 testcase time 으로 한다(dod AC10: 각 메서드 기준선 대비 ≥ 0.8s 감소).
- `BinanceFuturesManualAccountAdapterTest`(4.9s 케이스)는 변경하지 않고 D9 측정에서 클래스 시간만 기록한다.

### D5. batch 통합 테스트 context 재사용 (P2)

1. `apps/batch/src/integrationTest/kotlin/io/premiumspread/support/BatchIntegrationTestBase.kt:21` 의
   `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)` 와 그 import 를 제거한다.
2. **공용 MockWebServer 초기화.** `BatchTestConfig.exchangeRateMockServer()`(`BatchTestConfig.kt:64-65`)는 context 당
   하나인 singleton bean 이다. context 가 재사용되면 이전 클래스가 남긴 enqueue 응답과 recorded request 가 다음
   클래스로 넘어간다. base 가 매 테스트 전에 초기화한다.
   - 응답 큐: `dispatcher = QueueDispatcher()` 로 새 dispatcher 를 끼운다(MockWebServer 4.12 의 `var dispatcher`).
     기본 dispatcher 가 `QueueDispatcher` 이므로 새 인스턴스는 기동 직후 상태와 같다.
   - recorded request: `takeRequest(0, TimeUnit.MILLISECONDS)` 가 `null` 이 될 때까지 비운다(4.12 에 큐를 비우는
     API 가 없다. `ExchangeRateSchedulerE2ETest.kt:35-39` 가 같은 방식을 이미 쓴다).
   - `requestCount` 는 누적값이라 되돌릴 수 없다. 공용 `exchangeRateMockServer` 의 `requestCount` 를 읽는 곳은 0건이다
     (`OwnerAlertDeliveryIntegrationTest` 의 `requestCount` 4건은 그 클래스의 자체 private `MockWebServer` 를 읽는다) —
     이후에도 공용 서버의 `requestCount` 를 단언하지 않는다는 제약을 base KDoc 에 적는다.
   - 하위 클래스 `ExchangeRateSchedulerE2ETest` 가 이미 `exchangeRateMockServer` 라는 public 프로퍼티를 가지므로,
     base 의 필드는 **이름이 다른 private** 필드로 둔다(같은 이름이면 "hides member of supertype" 컴파일 오류).
3. **FixedClock 설정 통합.** `ExchangeRateRepositoryTest.FixedClockConfig`(:23-28)와
   `TickerCacheServiceScoreTest.FixedClockConfig`(:20-25)는 내용이 같다(`Clock.fixed(Instant.parse("2026-05-12T00:00:10Z"), ZoneOffset.UTC)`,
   `@Primary`). `io.premiumspread.support.FixedClockTestConfig` 하나로 옮기고 두 클래스가 그것을 `@Import` 한다.
   `@Import` 대상 클래스 집합이 같으면 Spring Boot `ImportsContextCustomizer` key 가 같아 context 가 하나로 묶인다.
4. **테스트 JVM heap.** context 가 닫히지 않으므로 최대 8개 context(각자 Redis 컨테이너·Hikari pool·Redisson 포함)가
   세션 끝까지 동시에 산다. Gradle 기본 test heap(512m)에서 GC 압박·OOM 위험이 있어 `apps/batch/build.gradle.kts`
   의 `integrationTest` task 에 `maxHeapSize = "1g"` 를 준다.
   [ASSUMPTION] 1g 면 충분하다 — Task 4 실행에서 OOM/과도한 GC 가 없음을 확인한다.
   Hikari 연결 수: 8 context × 기본 10 = 80 < MySQL 기본 `max_connections` 151.

기동 횟수 예상: 기본 context 1 + FixedClock 1 + 커스텀 6(`NotificationDisabledIntegrationTest`,
`DurableNotificationDeliveryIntegrationTest`, `TradePreparationEvaluationJobIntegrationTest`,
`TradePreparationReconcileJobIntegrationTest`, `TradePreparationReconcileRollbackIntegrationTest`,
`TradePreparationReconcileWithoutBalanceSourceIntegrationTest`) = **8** (목표 ≤ 9, 기준 23).

공유 상태 점검 결과:

| 상태 | 이전 | 이후 | 처리 |
|---|---|---|---|
| MySQL | JVM singleton(`MySqlTestContainersConfig` companion) — context 와 무관하게 이미 클래스 간 공유 | 같음 | base `@BeforeEach` 가 `BatchTestConfig.BATCH_TABLES` truncate. owner 계열 테이블은 각 클래스 `@BeforeEach` 가 이미 지운다(`OwnerMarketAlertIntegrationTest.cleanMarketAlertState`, `OwnerOpsAlertIntegrationTest.cleanOwnerOpsTables`) — 변화 없음 |
| Redis | context 마다 새 컨테이너(`RedisTestContainersConfig` bean) | context 를 공유하는 클래스끼리 공유 | base `@BeforeEach` `flushAll` 이 이미 매 테스트 비운다 |
| MockWebServer bean | context 마다 새 서버 | 공유 | 2. 의 초기화 |
| Micrometer 카운터 | context 마다 새 registry | 공유 | 카운터를 읽는 `DurableNotificationDeliveryIntegrationTest` 는 자체 context 라 다른 클래스와 섞이지 않는다 |
| springmockk spy/mock | 커스텀 context 에만 존재 | 같음 | 변화 없음 |

공유 때문에 특정 클래스가 실패하면 **그 테스트의 상태 초기화를 고친다.** 실패 클래스에 `@DirtiesContext` 를 다시
붙이는 것은 마지막 수단이며, 붙이면 사유를 해당 클래스 KDoc 과 dod 변경 요청에 남긴다(기동 횟수 기준이 걸리므로).

**자가 검증 테스트**: `io.premiumspread.support.SharedContextIsolationIntegrationTest`(기본 context) — 앞 테스트가
공용 서버에 남긴 응답·요청이 다음 테스트에 보이지 않음을 증명한다(§5).

### D6. common IT 모듈 공용 MySQL 컨테이너 + 클래스별 database (P1)

`infrastructure/common/src/integrationTest/kotlin/io/premiumspread/infrastructure/common/support/` 에 둔다.

- JVM 당 컨테이너 1개를 `object` 초기화에서 `start()` 한다. 명시적 stop 은 하지 않고 Ryuk 가 JVM 종료 시 정리한다.
  - 정책 확인: `verifyTestIsolationPolicy`(`build.gradle.kts:142-161`)가 막는 것은 run 간 reuse
    (`testcontainers.reuse.enable=false`)·leak detection 비활성·retry 다. 같은 JVM 안의 singleton 은 run 간 reuse 가
    아니다. 선례: `modules/jpa/src/testFixtures/.../MySqlTestContainersConfig.kt` 가 api/batch 에서 같은 방식(companion
    `start()`, stop 없음)으로 쓰이며 `NonDaemonThreadLeakListener` 를 통과한다.
- 클래스마다 `CREATE DATABASE` 로 자기 database 를 받는다. 이전에 컨테이너가 주던 "클래스마다 빈 서버" 격리를
  "클래스마다 빈 database" 로 바꾸는 것이다.
  - 근거: 16개 테스트가 쓰는 서버 상태는 전부 자기 schema 안이다. `information_schema` 질의는 전부
    `TABLE_SCHEMA = DATABASE()` 이고, JDBC metadata 호출은 전부 catalog 로 `mysql.databaseName` 을 넘긴다(V16·V17·V19·
    PremiumPair·DurableNotification). `flyway.clean()` 은 URL 의 schema 만 지운다. 서버 전역 설정(`SET GLOBAL`)을
    바꾸는 테스트는 없다.
  - 기존 16개 database 이름은 서로 다르다. 같은 이름을 두 클래스가 요청하면 즉시 실패시켜 공유 사고를 막는다.
  - 공용 컨테이너의 부트스트랩 database 이름은 `shared_bootstrap` 이다. `PremiumCacheTransactionIntegrationTest` 가
    `premiumspread` 를 쓰므로 그 이름을 피한다.
- 계정: 테스트는 지금처럼 `test`/`test` 로 접속한다. database 생성과 권한 부여만 root 로 한다.
  - Testcontainers `MySQLContainer` 는 `MYSQL_ROOT_PASSWORD` 를 지정한 password(`test`)로 설정한다.
  - `GRANT ALL PRIVILEGES ON \`{name}\`.* TO 'test'@'%'` — mysql 이미지 entrypoint 가 `MYSQL_DATABASE` 에 주던 권한과
    같은 범위다. **`FLUSH PRIVILEGES` 를 실행하지 않는다** — `caching_sha2_password` 의 인증 캐시를 비워, 이후
    `sslMode=DISABLED` 연결이 "Public Key Retrieval is not allowed" 로 실패한다. (지금 테스트가 `sslMode=DISABLED`
    로 접속되는 것은 Testcontainers 기동 확인 연결이 SSL 로 `test` 의 캐시를 채워 두기 때문이다.)
  - root 관리 연결은 sslMode 를 지정하지 않는다(기본 PREFERRED, SSL 로 full auth).
- JDBC URL 은 `jdbc:mysql://{host}:{mappedPort}/{name}` — query string 없음. 현재 Testcontainers 1.21.4 의
  `MySQLContainer.getJdbcUrl()` 도 query 가 없다(`PremiumCacheTransactionIntegrationTest.kt:34` 가 `+ "?..."` 로 바로
  붙여 쓰고 통과한다). 공용 컨테이너 초기화에서 `container.jdbcUrl` 에 `?` 가 있으면 즉시 실패시켜 이 전제가
  깨지는 것을 드러낸다.
- 컨테이너 설정은 기존과 같다: `mysql:8.0`, `withCommand` 없음(서버 기본 charset·collation 유지), `test`/`test`.

전환 대상 16개 클래스(데이터베이스 이름은 기존 `withDatabaseName` 값 그대로):

| 분류 | 클래스 | database |
|---|---|---|
| adapter (D7 적용) | `JdbcAccountPnlAlertStoreIntegrationTest` | `premiumspread_account_pnl` |
| | `JdbcOwnerAlertOutboxIntegrationTest` | `premiumspread_owner_alert` |
| | `JdbcAccountPnlLatestStoreIntegrationTest` | `premiumspread_account_latest` |
| | `JdbcNotificationDeliveryRepositoryIntegrationTest` | `premiumspread_notification` |
| | `NotificationSubscriptionOptimisticLockIntegrationTest` | `premiumspread_notification_lock` |
| | `JdbcOwnerMarketAlertStoreIntegrationTest` | `premiumspread_owner_market` |
| cache (컨테이너만) | `PremiumCacheTransactionIntegrationTest` — Redis 컨테이너는 클래스 전용 `@Container` 유지 | `premiumspread` |
| migration 경로 (컨테이너만, clean+targeted migrate 유지) | `V12MigrationSafetyIntegrationTest` | `premiumspread_v12_safety` |
| | `V16TradePreparationMigrationIntegrationTest` | `premiumspread_trade_preparation_migration` |
| | `V17TradePreparationIndexMigrationIntegrationTest` | `premiumspread_trade_preparation_index_migration` |
| | `V19OwnerAlertChannelMigrationIntegrationTest` | `premiumspread_owner_alert_channel_migration` |
| | `V20OwnerAccountAlertStateMigrationIntegrationTest` | `premiumspread_owner_account_alert_migration` |
| | `V21OwnerAlertSignalStateMigrationIntegrationTest` | `premiumspread_owner_alert_signal_migration` |
| | `V22OwnerMarketAlertStateMigrationIntegrationTest` | `premiumspread_owner_market_alert_migration` |
| | `PremiumPairMigrationIntegrationTest` | `premiumspread_migration` |
| | `DurableNotificationMigrationIntegrationTest` | `premiumspread_notification_migration` |

각 클래스는 `mysql.jdbcUrl`·`mysql.username`·`mysql.password`·`mysql.databaseName` 만 쓴다(grep 확인). 그래서 companion 의
`val mysql: MySQLContainer<*>` 를 같은 멤버 이름을 가진 `TestDatabase` 로 바꾸면 테스트 본문은 고치지 않는다.
`@Testcontainers`·`@Container`·`@JvmStatic`(mysql 에 붙은 것)과 미사용이 된 import 는 지운다(ktlint
`no-unused-imports` 가 CI 에서 잡는다). `PremiumCacheTransactionIntegrationTest` 는 Redis 때문에 `@Testcontainers` 와
redis 의 `@Container` 를 유지한다.

### D7. adapter 테스트: migrate 1회 + 지문 확인 후 TRUNCATE (P1)

adapter 6개 클래스의 `@BeforeEach` 에서 `flyway.clean()`+`migrate()` 를 `LatestSchemaFixture.reset()` 한 줄로 바꾼다.

`reset()` 의 동작:

| 상황 | 동작 |
|---|---|
| 그 fixture 의 첫 호출 | Flyway `clean()` + `migrate()`(locations `classpath:db/migration`, `cleanDisabled(false)`) 후 스키마 지문을 저장 |
| 이후 호출, 지문이 저장값과 같음 | 한 JDBC 연결에서 `SET FOREIGN_KEY_CHECKS = 0` → `flyway_schema_history` 를 뺀 모든 base table `TRUNCATE` → `SET FOREIGN_KEY_CHECKS = 1`(finally) |
| 이후 호출, 지문이 다름 | `clean()` + `migrate()` 후 지문 재저장 |

- **TRUNCATE 대상은 하드코딩하지 않는다.** 매 호출 `information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND
  TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history'` 로 조회한다. 새 migration 이 테이블을 더해도
  누락되지 않는다.
- **같은 연결**이어야 한다. `FOREIGN_KEY_CHECKS` 는 세션 변수이고 `DriverManagerDataSource` 는 호출마다 새 연결을
  준다. FK 로 참조되는 InnoDB 테이블은 `FOREIGN_KEY_CHECKS = 0` 인 세션에서만 TRUNCATE 된다.
- TRUNCATE 는 AUTO_INCREMENT 도 1 로 되돌린다 — rollback 테스트가 counter 를 올려도 다음 테스트는 새 schema 와
  같은 상태에서 시작한다.
- **지문이 필요한 이유**: `JdbcOwnerMarketAlertStoreIntegrationTest.kt:174` 는 손상 상황을 만들려고
  `ALTER TABLE owner_market_alert_state DROP CHECK ck_owner_market_alert_state_zone` 을 실행한다. TRUNCATE 만 하면
  이후 테스트가 CHECK 없는 스키마에서 돈다. 지문 비교가 이런 스키마 변경을 자동으로 감지해 재구성한다 — 어느
  테스트가 스키마를 바꾸는지 사람이 표시할 필요가 없다.
- 지문 = 아래 질의 결과 행을 문자열로 정렬한 목록(모두 `TABLE_SCHEMA`/`CONSTRAINT_SCHEMA` = `DATABASE()`):
  `TABLES`(name, type) · `COLUMNS`(table, column, ordinal, column_type, is_nullable, column_default, extra) ·
  `STATISTICS`(table, index, seq, column, non_unique) · `TABLE_CONSTRAINTS`(table, name, type) ·
  `CHECK_CONSTRAINTS`(name, clause) · `REFERENTIAL_CONSTRAINTS`(name, table, referenced table, update_rule, delete_rule) ·
  `TRIGGERS`(name) · `VIEWS`(name) · `flyway_schema_history`(installed_rank, version, checksum, success).
- 비용 추정: 지문 질의 ~9회 + TRUNCATE ~25회 ≈ 1s 미만/테스트(현재 6.8s). 스키마를 바꾼 테스트 직후 1회만 6.8s.
- 상태(마이그레이션 여부·지문)는 클래스 companion 의 fixture 인스턴스가 가진다. JUnit 이 테스트마다 인스턴스를 새로
  만들어도 companion 은 클래스당 하나다.
- 접속 URL 은 `TestDatabase.utcJdbcUrl` — 6개 adapter 테스트가 각자 계산하던 문자열
  (`?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`)과 같다. 테스트의 기존 `jdbcUrl`
  getter 는 그대로 둔다(본문 무변경).
- 데이터 시드: migration 에 `INSERT` 는 없다(`grep -i insert db/migration` 결과 V20 주석 1줄뿐). TRUNCATE 가 migration
  시드를 지울 위험이 없다.

**자가 검증 테스트**: `io.premiumspread.infrastructure.common.support.LatestSchemaFixtureIntegrationTest`(§5).

### D8. orchestrator ⑩ 을 Gradle 한 번 호출로 (P3)

```bash
./gradlew test architectureTest :infrastructure:common:verifyMigrations :infrastructure:common:integrationTest :apps:api:integrationTest :apps:batch:integrationTest --parallel --continue --offline --no-daemon
bash docs/check-documentation.sh
```

- `--parallel`: 서로 다른 프로젝트의 task 가 동시에 돈다. `integrationTest` 의 `shouldRunAfter(test)` 는 병렬
  실행에서 다른 의존이 모두 충족되면 무시되므로 통합 task 가 unit 과 겹쳐 돌 수 있다. 각 task 는 별도 JVM·별도
  컨테이너를 쓰므로 상태를 공유하지 않는다.
- `--continue`: 한 task 가 실패해도 나머지를 끝까지 돌려, 실패 전체를 한 번에 보고한다(직렬 5회 호출이 주던
  "모든 결과를 본다" 성질을 유지).
- 검증 범위(전 모듈 전 테스트 + verifyMigrations + 문서 검사)는 그대로다.
- 결과 보고는 한 호출이라도 task 별(unit 합계, common/api/batch integration, verifyMigrations)로 실행 수·실패 수를
  나눠 적는다. 실패한 task 만 `.ai/rules/testing.md` 의 개별 명령으로 다시 돌린다 — 이 문구를 모든 갱신 대상에서 통일한다.

갱신 대상 — 같은 명령을 적던 곳은 전부 바꾼다. 옮겨 적은 곳이 갈리지 않게 한다.

| 파일 | 현재 위치 | 변경 |
|---|---|---|
| `.claude/skills/orchestrator/SKILL.md` | ⑩ 블록 463-467 | 위 명령 + 설명 2문장 |
| `.claude/agents/qa-agent.md` | 실행 명령 21, 23-26 · 에러 핸들링 79 "전체 실행이 오래 걸리면 모듈별로 나눠 돌리고 소요 시간을 함께 보고한다." | 실행 명령은 위 명령 한 줄(점진 검증 22행 유지). 79행은 실패 task 개별 재실행 문구로 교체 |
| `.claude/skills/qa-verification/SKILL.md` | 실행 순서 13-17 | 위 명령 한 줄 + task 별 보고 규칙 |
| `.claude/skills/test-strategy/SKILL.md` | 태스크 구분 19-23 | 위 명령 한 줄 + 실패 task 개별 재실행 안내 |
| `.claude/agents/code-reviewer.md` | 15행 `./gradlew test architectureTest --offline --no-daemon` | `--parallel` 추가 |
| `.ai/rules/testing.md` | Test task 명령 8-12, "통합 test task는 unit 뒤에" 문장, 격리 규칙 | 전체 명령 + 개별 재실행 명령, 병렬 문장 교체, 공용 컨테이너 규칙 추가 |
| `AGENTS.md` | Quick Commands 52-56 | 다섯 줄을 위 명령 한 줄로 |
| `.ai/instructions.md` | 테스트와 검증 69-73 | 다섯 줄을 위 명령 한 줄로 |
| `docs/harness-changelog.md` | 표 맨 위 | 한 행 |

`docs/check-harness.sh`·`docs/check-documentation.sh` 는 이 명령 문자열을 단언하지 않는다(grep 확인) — 문서만 바꾸면
게이트는 변하지 않는다. `docs/harness-changelog.md`·`CLAUDE.md` 는 CRLF 줄바꿈이다 — 편집 시 기존 줄바꿈을 유지한다.

### D9. 측정 방법

| 기호 | 정의 |
|---|---|
| 집계 C(dir) | `awk -F'"' '/<testsuite /{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ skipped=$/)s+=$(i+1);if($i~/ failures=$/)f+=$(i+1);if($i~/ errors=$/)e+=$(i+1)}} END{printf "tests=%d skipped=%d failures=%d errors=%d\n",t,s,f,e}' <dir>/TEST-*.xml` |
| context 기동 수 K | `grep -ho 'Started [^ ]* in [0-9.]* seconds' apps/batch/build/test-results/integrationTest/TEST-*.xml \| wc -l` (test profile root INFO 라 Spring Boot 기동 로그가 XML system-out 에 남는다) |
| 메서드 시간 | 결과 XML 의 `<testcase name=... classname=... time=...>` 속성. 지연을 쓰는 4개 메서드(Bithumb `네트워크 실패와 타임아웃 예외에도 secret 이 없다`·`응답 시간이 초과되면 NETWORK`, ExchangeRate `read timeout 은 설정한 횟수까지만 재시도한다`·`응답 헤더 지연으로 Netty responseTimeout 이 이기면 WebClientRequestException 으로 재시도한다`) |
| 콜드 worktree | 새로 만든 `git worktree`(빌드 산출물 없음). Gradle 의존성 cache(`~/.gradle`)와 Docker 이미지는 공유한다 |
| ⑩ wall-clock | 콜드 worktree 에서 ⑩ 명령(D8)을 `time` 으로 1회. 기준선은 같은 방식으로 `origin/dev` 콜드 worktree 에서 직렬 5개 명령 |
| common IT 시간 | ⑩ 직후 같은 worktree 에서 `time ./gradlew :infrastructure:common:integrationTest --rerun --offline --no-daemon`(컴파일은 up-to-date, 테스트 task 만 재실행) |
| CI wall-clock | 최종 SHA 의 PR Quality Gate run 에서 `min(job.startedAt)` ~ `max(job.completedAt)` (`gh run view <run-id> --json jobs`). 큐 대기 제외 |

기준선 기호(Task 0 이 기록): `B_unit`·`B_common`·`B_api`·`B_batch`(각 C 결과), `B_ctx`(K, 측정 근거상 23), `B_wall`(직렬 5개 합),
지연 메서드 4개의 기준선 time.

## 4. 위험

| 위험 | 대응 |
|---|---|
| 공유 context 에서 특정 batch 테스트가 이전 클래스 상태에 영향받음 | D5 의 공유 상태 표대로 초기화. 실패 시 테스트 초기화를 고치고, 최후 수단 `@DirtiesContext` 는 dod 변경 요청과 함께 |
| batch 테스트 JVM 메모리 | D5-4 `maxHeapSize = "1g"` |
| root 의 `FLUSH PRIVILEGES` 로 인증 캐시가 비는 문제 | D6 에서 금지. 공용 컨테이너 KDoc 에 이유를 남긴다 |
| 지문이 놓치는 스키마 변경(예: 테이블 옵션·collation) | 현재 스키마를 바꾸는 테스트는 CHECK 제거 1건뿐이고 지문이 잡는다. 자가 검증 테스트가 그 경로를 고정한다 |
| `--parallel` 로컬 ⑩ 에서 Docker 자원 경합 | 동시 컨테이너: common 1 + api 1~3 + batch MySQL 1 + Redis ≤ 8. 24코어/31GB 에서 문제 없다고 본다 — Task 7 실측 |
| required status check 미갱신으로 common IT 실패가 merge 를 막지 못함 | D1 [DECISION_NEEDED] — 저장소 관리자 조치 |
| setup-gradle 기본값 때문에 D2 효과가 작음 | D9 PR run 측정으로 확인. 목표는 D1 만으로도 도달 가능하다고 본다 |

## 5. 테스트 전략

리팩터링 성격이라 기본 검증은 "인프라를 바꾼 뒤 기존 테스트가 같은 수로 green" 이다. 새 동작(공용 자원 초기화)에만
자가 검증 테스트를 더한다.

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| common 공용 컨테이너 | 16개 클래스가 같은 수·같은 결과로 통과 | `:infrastructure:common:integrationTest` (tests = `B_common` + 3) |
| `LatestSchemaFixture` | (a) 데이터를 넣은 뒤 `reset()` 하면 `flyway_schema_history` 를 뺀 **모든** base table 이 0행, history 행 수 불변, AUTO_INCREMENT 가 1부터 (b) CHECK 제약을 지운 뒤 `reset()` 하면 제약이 복구된다 (c) 첫 `reset()` 이 빈 database 를 최신 schema 로 만든다 | `LatestSchemaFixtureIntegrationTest` 3건 |
| batch context 재사용 | 같은 수·같은 결과, 기동 수 ≤ 9 | `:apps:batch:integrationTest` (tests = `B_batch` + 2) |
| 공용 MockWebServer 초기화 | 순서 고정 2건: 1번이 응답 2개 enqueue·1개 소비·요청 기록을 남김 → 2번에서 새 응답이 그대로 오고, recorded request 가 2번 요청 하나뿐 | `SharedContextIsolationIntegrationTest` 2건 |
| unit 지연 단축 | 같은 수 green, 지연을 쓰는 메서드 4개 각각 기준선 대비 ≥ 0.8s 감소 | `:infrastructure:batch:test` |
| CI 구조 | needs 0, common job 분리, cache-read-only, 정확한 명령 문자열 | `bash ci/quality-gate-contract-test.sh`, `bash docker/deploy-contract-test.sh` |
| 정책 | 격리 정책·leak·하네스 무결성 | `verifyTestIsolationPolicy`, 각 IT 세션 종료의 `NonDaemonThreadLeakListener`, `harnessCheck harnessCheckTest`, `check-documentation.sh` |

## 6. [ASSUMPTION] · [DECISION_NEEDED] 목록

- [ASSUMPTION] A1 — branch protection 이 기존 job name 을 가리킬 수 있어 이름·번호를 유지한다(D1).
- [ASSUMPTION] A2 — setup-gradle v4.4.1 `cache-read-only` 기본값 때문에 D2 의 절감이 측정값보다 작을 수 있다(D2).
- [ASSUMPTION] A3 — Testcontainers 1.21.4 `MySQLContainer.getJdbcUrl()` 에 query 가 없다. 공용 컨테이너가 기동 시 확인한다(D6).
- [ASSUMPTION] A4 — batch 통합 테스트 JVM heap 1g 로 8 context 동시 유지가 가능하다(D5).
- [ASSUMPTION] A5 — 모든 job 의 `needs` 제거(docker-build·static-analysis·dependency-security 포함)는 사용자 위임
  "설계 판단" 범위다(D1).
- [DECISION_NEEDED] N1 — required status check 에 `8. Common integration` 추가(저장소 설정, PR 밖).
- [DECISION_NEEDED] N2 — dod 의 시간·개수 기준(AC1~AC5, AC9, AC10)을 T2/T3 로 두는 강등 승인(Gate 1). 대안: 결과 XML 을
  집계하는 `신규스크립트` 를 만들어 T1 로 올린다.

## 7. 개정 이력

| 시점 | 변경 | 사유 |
|---|---|---|
| ⑥ spec-reviewer 1R (7건 ACCEPT) | D4 기대 절감을 메서드별 ~1s 로 정정, AC10 을 메서드별 ≥ 0.8s 로 / D5 `requestCount` 근거 정정 / AC9 둘째 grep 에 test-strategy 포함 / qa-agent 79행 갱신 대상 추가 / plan 라벨 "시그니처 계약" / CRLF 유지 지시 / testing.md bullet 에 Ryuk 정리 명시 | ExchangeRate 재시도가 새 연결에서 돌아 두 지연이 겹친다 — 클래스 합계 4s 단축 가정이 틀렸다. 나머지는 근거 정확도·문서 일관성 |
