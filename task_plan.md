# Task Plan: Scheduler E2E 테스트 구현

## Goal

`scheduler_e2e_testcases.md`의 11개 E2E 테스트케이스를 실제 구현하여 전체 GREEN 달성.
선행으로 프로덕션 코드 수정 + 테스트 인프라 구축 포함.

## References

- 갭 분석: `findings.md` (14건 식별, 전체 논의 완료)
- 원본 E2E 문서: `.ai/planning/refactoring/batch/testcase/scheduler_e2e_testcases.md`

## GAP 결정 요약

| GAP | 심각도 | 결정 |
|-----|--------|------|
| 1 | Critical | `updateSummaryCache()` 구간별 `runCatching` 적용 |
| 2 | Medium | `updateSummaryCache()`에 jobExecutor 패턴 전환 (leaseTime 30초) |
| 3 | Medium | E2E 문서 보강 (구간별 데이터 소스 명시) |
| 4 | Low | position 캐시 seed → `saveHistory` 검증 포함 |
| 5 | Low | `fetchExchangeRateOnStartup()` 결과 동일성 1건만 검증 |
| 6 | Medium | Client별 MockWebServer 분리 (Bithumb, Binance, ExchangeRate 각각) |
| 7 | Medium | E2E 문서 보강 (Redis key/DB table 검증 방법 구체화) |
| 8 | High | GAP-9~11, 14로 해소 |
| 9 | Critical | batch `build.gradle.kts`에 testFixtures 의존성 추가 |
| 10 | High | `integrationTest` Gradle task + test 태그 분리 |
| 11 | High | batch `application.yml`에 test 프로필 섹션 추가 (스케줄링 비활성화) |
| 12 | Medium | `AggregationJob`에 `Clock` 주입, 테스트에서 `Clock.fixed()` 사용 |
| 13 | Low | `PremiumAggregationRepository`, `TickerAggregationRepository`에 조회 메서드 추가 |
| 14 | Medium | BatchTestConfig에서 Client별 MockWebServer Bean + Client Bean override |

---

## Phase 0: 프로덕션 코드 수정 (GAP-1, 2, 12, 13)

### 0-1. updateSummaryCache 구간별 runCatching 적용 (GAP-1)
- **파일:** `apps/batch/src/main/kotlin/io/premiumspread/scheduler/PremiumAggregationScheduler.kt`
- **변경:** 단일 try-catch → 4개 구간을 각각 `runCatching { ... }.onFailure { log.error(...) }`로 감싸기
- **검증:** 기존 단위 테스트 `PremiumAggregationSchedulerTest` updateSummaryCache 2건 GREEN 확인

### 0-2. updateSummaryCache에 jobExecutor 패턴 전환 (GAP-2)
- **파일:** `PremiumAggregationScheduler.kt`
- **변경:**
  - `SUMMARY_CONFIG` companion object 추가 (jobName: `aggregation:summary`, leaseTime: 30초)
  - `updateSummaryCache()` 내부를 `jobExecutor.execute(SUMMARY_CONFIG) { ... }` 패턴으로 변경
- **검증:** 기존 단위 테스트 수정/추가 필요 (jobExecutor mock 설정)

### 0-3. AggregationJob에 Clock 주입 (GAP-12)
- **파일:** `apps/batch/src/main/kotlin/io/premiumspread/application/job/aggregation/AggregationJob.kt`
- **변경:**
  ```kotlin
  class AggregationJob<T>(
      private val reader: (Instant, Instant) -> T?,
      private val writer: (T, Instant, Instant) -> Unit,
      private val unit: ChronoUnit = ChronoUnit.MINUTES,
      private val clock: Clock = Clock.systemDefaultZone(),  // 추가
  ) {
      fun run(): JobResult {
          val now = clock.instant()  // Instant.now() → clock.instant()
          ...
      }
  }
  ```
- **전파:** `PremiumAggregationScheduler`, `TickerAggregationScheduler`에서 AggregationJob 생성 시 Clock 전달
  - 프로덕션: 기본값 `Clock.systemDefaultZone()` → 변경 없음
  - 테스트: `Clock.fixed(...)` 주입 → 시간 윈도우 완전 제어
- **검증:** 기존 `AggregationJobTest` GREEN 유지 확인

### 0-4. Repository에 조회 메서드 추가 (GAP-13)
- **파일:** `apps/batch/src/main/kotlin/io/premiumspread/repository/PremiumAggregationRepository.kt`
  - `findLatestMinute(symbol)`, `findLatestHour(symbol)`, `findLatestDay(symbol)` 추가
- **파일:** `apps/batch/src/main/kotlin/io/premiumspread/repository/TickerAggregationRepository.kt`
  - `findLatestMinute(exchange, symbol)`, `findLatestHour(exchange, symbol)`, `findLatestDay(exchange, symbol)` 추가
- **검증:** 컴파일 확인

### 0-5. 기존 단위 테스트 전체 GREEN 확인
- `./gradlew :apps:batch:test`

---

## Phase 1: 테스트 인프라 구축 (GAP-9, 10, 11, 14)

### 1-1. build.gradle.kts 수정 (GAP-9, 10)
- **파일:** `apps/batch/build.gradle.kts`
- **추가:**
  ```kotlin
  // testFixtures 의존성 (GAP-9)
  testImplementation(testFixtures(project(":modules:jpa")))
  testImplementation(testFixtures(project(":modules:redis")))

  // integrationTest task (GAP-10)
  tasks.named<Test>("test") {
      useJUnitPlatform { excludeTags("integration") }
  }
  tasks.register<Test>("integrationTest") {
      description = "Runs integration tests (requires Docker)"
      group = "verification"
      useJUnitPlatform { includeTags("integration") }
      shouldRunAfter(tasks.named("test"))
  }
  ```

### 1-2. application.yml test 프로필 추가 (GAP-11)
- **파일:** `apps/batch/src/main/resources/application.yml`
- **추가:** test 프로필 섹션
  ```yaml
  ---
  spring:
    config:
      activate:
        on-profile: test
    task:
      scheduling:
        pool:
          size: 0
    main:
      allow-bean-definition-overriding: true
  ```

### 1-3. BatchTestConfig 생성 (GAP-6, 14)
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/config/BatchTestConfig.kt`
- **내용:**
  ```kotlin
  @TestConfiguration
  class BatchTestConfig {
      @Bean fun bithumbMockServer() = MockWebServer()
      @Bean fun binanceMockServer() = MockWebServer()
      @Bean fun exchangeRateMockServer() = MockWebServer()

      @Bean
      fun bithumbClient(bithumbMockServer: MockWebServer, registry: MeterRegistry) =
          BithumbClient(WebClient.create(bithumbMockServer.url("/").toString()), registry)

      @Bean
      fun binanceClient(binanceMockServer: MockWebServer, registry: MeterRegistry) =
          BinanceClient(WebClient.create(binanceMockServer.url("/").toString()), registry)

      @Bean
      fun exchangeRateClient(exchangeRateMockServer: MockWebServer, registry: MeterRegistry) =
          ExchangeRateClient(WebClient.create(exchangeRateMockServer.url("/").toString()), registry)
  }
  ```

### 1-4. BatchIntegrationTestBase 생성
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/support/BatchIntegrationTestBase.kt`
- **내용:**
  ```kotlin
  @Tag("integration")
  @SpringBootTest
  @ActiveProfiles("test")
  @Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, BatchTestConfig::class)
  abstract class BatchIntegrationTestBase {
      @Autowired lateinit var redisTemplate: StringRedisTemplate
      @Autowired lateinit var jdbcTemplate: JdbcTemplate
      @Autowired lateinit var databaseCleanUp: DatabaseCleanUp

      @BeforeEach
      fun cleanUp() {
          databaseCleanUp.truncateAllTables()
          redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
      }
  }
  ```

### 1-5. 인프라 검증
- `./gradlew :apps:batch:integrationTest` 실행 (빈 테스트, context 로딩 확인)
- TestContainers MySQL + Redis 정상 기동 확인

---

## Phase 2: Ingestion E2E 테스트 (E2E #2, #3, #4)

> 데이터 의존성상 ExchangeRate → Ticker 순서

### 2-1. ExchangeRateScheduler E2E (E2E #3, #4)
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/e2e/ExchangeRateSchedulerE2ETest.kt`
- **테스트 3건:**
  1. MockWebServer 환율 응답 준비 → `fetchExchangeRate()` → Hash `fx:usd:krw` 필드값 검증
  2. `fetchExchangeRate()` 후 → DB `exchange_rate` 레코드 (rate, currency pair) 검증
  3. `fetchExchangeRateOnStartup()` → 결과 동일성 1건 검증 (GAP-5)

### 2-2. TickerScheduler E2E (E2E #2)
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/e2e/TickerSchedulerE2ETest.kt`
- **테스트 3건:**
  1. MockWebServer 빗썸/바이낸스 응답 준비 → `fetchTickers()` → Hash `ticker:bithumb:btc`, `ticker:binance:btc` 검증
  2. 실행 후 → ZSet `ticker:seconds:bithumb:btc`, `ticker:seconds:binance:btc` 데이터 존재 검증
  3. 캐시 필드값 (price, volume, exchange) 정확성 검증

---

## Phase 3: Premium 실시간 E2E 테스트 (E2E #1)

### 3-1. PremiumScheduler E2E
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/e2e/PremiumSchedulerE2ETest.kt`
- **선행 데이터:** Redis에 bithumb/binance 티커 + fx 환율 캐시 + position 캐시 seed (GAP-4)
- **테스트 3건:**
  1. 선행 캐시 준비 → `calculatePremium()` → Hash `premium:btc` 검증
  2. 실행 후 → ZSet `premium:seconds:btc` 데이터 존재 검증
  3. 실행 후 → ZSet `premium:btc:history` 데이터 존재 검증 (position open 상태)

---

## Phase 4: Premium Aggregation E2E 테스트 (E2E #5, #6, #7, #8)

> Clock.fixed() 사용으로 시간 윈도우 제어 (GAP-12)

### 4-1. aggregateMinute E2E (E2E #5)
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/e2e/PremiumAggregationE2ETest.kt`
- **선행 데이터:** `premium:seconds:btc` ZSet에 고정 시간 윈도우 데이터 seed
- **테스트 2건:**
  1. 초 데이터 seed → `aggregateMinute()` → ZSet `premium:minutes:btc` 검증
  2. 실행 후 → DB `premium_minute` 레코드 검증 (Repository 조회 메서드 사용)

### 4-2. aggregateHour E2E (E2E #6)
- **선행 데이터:** `premium:minutes:btc` ZSet
- **테스트 2건:**
  1. 분 데이터 seed → `aggregateHour()` → ZSet `premium:hours:btc` 검증
  2. 실행 후 → DB `premium_hour` 레코드 검증

### 4-3. aggregateDay E2E (E2E #7)
- **선행 데이터:** `premium:hours:btc` ZSet
- **테스트 1건:**
  1. 시 데이터 seed → `aggregateDay()` → DB `premium_day` 레코드 검증 (캐시 저장 없음)

### 4-4. updateSummaryCache E2E (E2E #8)
- **선행 데이터 (GAP-3):**
  - `premium:seconds:btc` ZSet — 1m/10m 범위 (소스: `calculateSummaryFromSeconds`)
  - `premium:minutes:btc` ZSet — 1h 범위 (소스: `calculateSummary(MINUTES)`)
  - `premium:hours:btc` ZSet — 1d 범위 (소스: `calculateSummary(HOURS)`)
- **테스트 1건:**
  1. 모든 소스 seed → `updateSummaryCache()` → Hash `summary:1m:btc`, `summary:10m:btc`, `summary:1h:btc`, `summary:1d:btc` 모두 검증

---

## Phase 5: Ticker Aggregation E2E 테스트 (E2E #9, #10, #11)

> Clock.fixed() 사용, TARGETS (bithumb/btc, binance/btc) 모두 검증

### 5-1. aggregateMinute E2E (E2E #9)
- **파일:** `apps/batch/src/test/kotlin/io/premiumspread/e2e/TickerAggregationE2ETest.kt`
- **선행 데이터:** `ticker:seconds:bithumb:btc`, `ticker:seconds:binance:btc` ZSet
- **테스트 2건:**
  1. 초 데이터 seed → `aggregateMinute()` → ZSet `ticker:minutes:bithumb:btc`, `ticker:minutes:binance:btc` 검증
  2. 실행 후 → DB `ticker_minute` 레코드 (bithumb + binance 각 1건) 검증

### 5-2. aggregateHour E2E (E2E #10)
- **선행 데이터:** `ticker:minutes:{exchange}:btc` ZSet
- **테스트 2건:** 분 데이터 seed → `aggregateHour()` → 시 캐시 + DB 검증

### 5-3. aggregateDay E2E (E2E #11)
- **선행 데이터:** `ticker:hours:{exchange}:btc` ZSet
- **테스트 1건:** 시 데이터 seed → `aggregateDay()` → DB `ticker_day` 검증 (캐시 저장 없음)

---

## Phase 6: 최종 검증 게이트

### 6-1. 전체 단위 테스트 GREEN 확인
- `./gradlew :apps:batch:test`

### 6-2. 전체 E2E 테스트 GREEN 확인
- `./gradlew :apps:batch:integrationTest`

### 6-3. 전체 프로젝트 빌드 확인
- `./gradlew compileKotlin`

### 6-4. E2E 문서 동기화 (GAP-3, 7)
- `scheduler_e2e_testcases.md` 보강
  - 각 테스트케이스별 구체적 검증 방법 (Redis key, DB table, 필드) 추가
  - 구간별 선행 데이터 소스 명시 (1m/10m → seconds, 1h → minutes, 1d → hours)
  - 테스트 인프라 전제조건 섹션 추가
- `progress.md` 갱신

---

## 파일 변경 계획 요약

### 프로덕션 코드 변경 (4건)

| 파일 | 변경 내용 | GAP |
|------|-----------|-----|
| `PremiumAggregationScheduler.kt` | updateSummaryCache: runCatching + jobExecutor 전환 | 1, 2 |
| `AggregationJob.kt` | Clock 파라미터 추가, `Instant.now()` → `clock.instant()` | 12 |
| `PremiumAggregationRepository.kt` | `findLatestMinute/Hour/Day` 조회 메서드 추가 | 13 |
| `TickerAggregationRepository.kt` | `findLatestMinute/Hour/Day` 조회 메서드 추가 | 13 |

### 빌드/설정 변경 (2건)

| 파일 | 변경 내용 | GAP |
|------|-----------|-----|
| `apps/batch/build.gradle.kts` | testFixtures 의존성 + integrationTest task | 9, 10 |
| `apps/batch/src/main/resources/application.yml` | test 프로필 섹션 추가 | 11 |

### 신규 테스트 파일 (7건)

| 파일 | 내용 | E2E # |
|------|------|-------|
| `.../config/BatchTestConfig.kt` | Client별 MockWebServer Bean + Client Bean override | — |
| `.../support/BatchIntegrationTestBase.kt` | E2E 베이스 클래스 (Redis flush + DB truncate) | — |
| `.../e2e/ExchangeRateSchedulerE2ETest.kt` | 환율 수집 + startup | #3, #4 |
| `.../e2e/TickerSchedulerE2ETest.kt` | 티커 수집 | #2 |
| `.../e2e/PremiumSchedulerE2ETest.kt` | 프리미엄 실시간 계산 + history | #1 |
| `.../e2e/PremiumAggregationE2ETest.kt` | 프리미엄 분/시/일 집계 + summary | #5, #6, #7, #8 |
| `.../e2e/TickerAggregationE2ETest.kt` | 티커 분/시/일 집계 | #9, #10, #11 |

### 문서 보강 (1건)

| 파일 | 변경 내용 | GAP |
|------|-----------|-----|
| `scheduler_e2e_testcases.md` | 검증 방법 구체화, 데이터 소스 명시, 인프라 전제조건 | 3, 7 |

---

## 예상 테스트 수

| Phase | 테스트 수 | 설명 |
|-------|----------|------|
| Phase 2 | 6건 | Ingestion (FX 3 + Ticker 3) |
| Phase 3 | 3건 | Premium Realtime (캐시 + seconds + history) |
| Phase 4 | 6건 | Premium Aggregation (minute 2 + hour 2 + day 1 + summary 1) |
| Phase 5 | 5건 | Ticker Aggregation (minute 2 + hour 2 + day 1) |
| **합계** | **20건** | E2E 전체 |

---

## Acceptance Criteria

1. `./gradlew :apps:batch:test` — 기존 단위 테스트 전체 GREEN
2. `./gradlew :apps:batch:integrationTest` — E2E 테스트 20건 전체 GREEN
3. `updateSummaryCache` 구간별 독립성 + jobExecutor 패턴 (단위 + E2E)
4. Docker 없이 `test` 실행 가능 (integration 태그 분리)
5. E2E 문서와 실제 테스트 1:1 대응
6. AggregationJob 시간 윈도우 — Clock 주입으로 flaky 테스트 방지
