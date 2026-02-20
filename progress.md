# Progress Log

## Session 2026-02-11

### 작업
- E2E 테스트케이스 문서(`scheduler_e2e_testcases.md`) 검토
- 실제 구현 코드 5개 Scheduler + 4개 Job + JobExecutor 전체 분석
- 기존 단위 테스트 10개 파일 커버리지 확인
- 문서 ↔ 구현 간 8개 주요 갭 식별

### 상태
- Phase 1 완료, findings.md에 상세 분석 기록

---

## Session 2026-02-12 (1차)

### 작업
- 이전 findings 기반 심층 분석 수행
- 추가 6건 갭 식별 (GAP-9 ~ GAP-14)
- 14건 GAP 전체 논의 및 결정 완료
- 6 Phase 구현 플랜 확정 (task_plan.md)

### GAP 결정 요약
- GAP-1: runCatching 적용
- GAP-2: jobExecutor 패턴 전환 (leaseTime 30초)
- GAP-3: E2E 문서 보강
- GAP-4: position 캐시 seed → saveHistory 검증 포함
- GAP-5: fetchExchangeRateOnStartup 결과 동일성 1건만 검증
- GAP-6: Client별 MockWebServer 분리
- GAP-7: E2E 문서 보강
- GAP-8: GAP-9~11, 14로 해소
- GAP-9: testFixtures 의존성 추가
- GAP-10: integrationTest Gradle task 추가
- GAP-11: application.yml test 프로필 섹션 추가
- GAP-12: AggregationJob에 Clock 주입
- GAP-13: Repository에 조회 메서드 추가
- GAP-14: BatchTestConfig에서 WebClient Bean override

### 상태
- 플랜 확정 완료

---

## Session 2026-02-12 (2차)

### 작업
- 기존 batch 리팩토링 + ai md 구조변경 5건 커밋/푸시 완료
- Phase 0 (프로덕션 코드 수정) 완료
- Phase 1 (테스트 인프라 구축) 완료
- Phase 2 시작 (Ingestion E2E)

### Phase 0 완료 내역

#### 0-1 + 0-2: updateSummaryCache 수정
- **파일:** `apps/batch/src/main/kotlin/io/premiumspread/scheduler/PremiumAggregationScheduler.kt`
- 단일 try-catch → 구간별 `runCatching { ... }.onFailure { log.error(...) }` 적용
- `jobExecutor.execute(SUMMARY_CONFIG)` 패턴 전환
- `SUMMARY_CONFIG` companion object 추가 (jobName: `aggregation:summary`, leaseTime: 30초)
- 기존 단위 테스트 `PremiumAggregationSchedulerTest` 업데이트 (jobExecutor mock 설정 추가)

#### 0-3: AggregationJob Clock 주입
- **파일:** `apps/batch/src/main/kotlin/io/premiumspread/application/job/aggregation/AggregationJob.kt`
- `clock: Clock = Clock.systemDefaultZone()` 파라미터 추가
- `Instant.now()` → `clock.instant()` 변경

#### 0-4: Repository 조회 메서드 추가
- **파일:** `PremiumAggregationRepository.kt` — `findLatestMinute/Hour/Day(symbol)` 추가
- **파일:** `TickerAggregationRepository.kt` — `findLatestMinute/Hour/Day(exchange, symbol)` 추가

#### 0-5: 검증 GREEN
- `./gradlew :apps:batch:compileKotlin` ✅
- `./gradlew :apps:batch:test` ✅

### Phase 1 완료 내역

#### 1-1: build.gradle.kts
- testFixtures 의존성 추가 (jpa, redis)
- `integrationTest` Gradle task + `test` 태그 분리

#### 1-2: application.yml test 프로필
- 스케줄링 비활성화 (`pool.size: 0`)
- Bean override 허용 (`allow-bean-definition-overriding: true`)
- test api key 설정

#### 1-3: BatchTestConfig 생성
- 3 MockWebServer beans (bithumb, binance, exchangeRate)
- 3 WebClient bean overrides (bithumbWebClient, binanceWebClient, exchangeRateWebClient)

#### 1-4: BatchIntegrationTestBase 생성
- `@Tag("integration")`, `@SpringBootTest`, `@ActiveProfiles("test")`
- `@Import(MySqlTestContainersConfig, RedisTestContainersConfig, BatchTestConfig)`
- `@BeforeEach`: DB truncate + Redis flushAll

#### 1-5: 검증 GREEN
- `./gradlew :apps:batch:compileTestKotlin` ✅
- `./gradlew :apps:batch:test` ✅

### Phase 2 진행 상황

#### 2-1: ExchangeRateSchedulerE2ETest 작성 완료 → 실행 시 실패

**작성 파일:**
- `apps/batch/src/test/kotlin/io/premiumspread/scheduler/ExchangeRateSchedulerE2ETest.kt`
- 3개 테스트: 캐시 저장 검증, DB 저장 검증, startup 동일성 검증

**추가 인프라 (Phase 2에서 발견한 누락):**
- `apps/batch/src/test/resources/batch-schema.sql` — batch 전용 테이블 DDL (CREATE TABLE IF NOT EXISTS)
  - batch 모듈은 JPA Entity 없이 JdbcTemplate만 사용
  - `ddl-auto: update`로는 테이블이 생성되지 않으므로 별도 스키마 초기화 필요
  - 대상 테이블: exchange_rate, ticker_minute/hour/day, premium_minute/hour/day
- `BatchTestConfig`에 `batchSchemaInitializer` bean 추가 (InitializingBean)
  - `batch-schema.sql` 읽어서 실행
- `BatchIntegrationTestBase.cleanUp()` 수정: `DatabaseCleanUp` 제거 → JdbcTemplate으로 직접 truncate
  - batch 모듈에 JPA Entity가 없으므로 EntityManager 기반 cleanup이 동작하지 않음
  - `BATCH_TABLES` 목록 기반으로 직접 truncate

**발견된 블로커: Redisson 연결 실패 ❌**

```
Caused by: org.redisson.client.RedisConnectionException
    Caused by: io.netty.channel.AbstractChannel$AnnotatedConnectException
        Caused by: java.net.ConnectException
```

### 상태
- Phase 2-6 전체 완료

---

## Session 2026-02-12 (3차)

### 작업
- Redisson 연결 블로커 해결 (6차 시도 끝에 해결)
- Phase 2~6 전체 구현 완료

### 블로커 해결 과정

**문제:** `@ServiceConnection`이 Redisson의 `@Value` 프로퍼티를 override하지 않음

**해결 (다단계):**
1. BatchTestConfig에 `redissonClient` bean 추가 (TestContainers 주소 사용)
2. BatchTestConfig에 `distributedLockManager` bean 추가
3. `@EnableScheduling` → `SchedulingConfig`로 분리, `@ConditionalOnProperty(scheduling.enabled)` 적용
4. `scheduling.enabled: false`, `redis.enabled: false` test 프로필 설정
5. WebClient override → **Client bean override** (`@Primary` 직접 적용)로 변경
   - `bithumbClient`, `binanceClient`, `exchangeRateClient` 빈을 `@Primary`로 override

### Phase 2: ExchangeRateSchedulerE2ETest ✅ (3건 GREEN)
- 환율 캐시 저장, DB 저장, startup 동일성 검증

### Phase 3: TickerSchedulerE2ETest ✅ (3건 GREEN)
- 빗썸/바이낸스 MockWebServer 응답 → fetchTickers() → Hash/ZSet 검증
- `Instant.now()` 기반 타임스탬프로 ZSet cleanup 문제 해결

### Phase 4: PremiumSchedulerE2ETest ✅ (3건 GREEN)
- 선행 데이터(ticker+fx) seed → calculatePremium() → 캐시/ZSet/히스토리 검증
- position:open:exists seed로 히스토리 저장 검증

### Phase 5: PremiumAggregationE2ETest ✅ (6건 GREEN)
- aggregateMinute: 캐시+DB (high/low/open/close/count 검증)
- aggregateHour: 캐시+DB
- aggregateDay: DB만
- updateSummaryCache: 4개 구간 서머리 해시 검증

### Phase 6: TickerAggregationE2ETest ✅ (5건 GREEN)
- aggregateMinute: 캐시+DB (exchange별 가격 데이터 검증)
- aggregateHour: 캐시+DB
- aggregateDay: DB만

### 최종 검증 게이트 ✅
- `./gradlew :apps:batch:integrationTest` — **BUILD SUCCESSFUL** (20건 전체 GREEN)

---

## 최종 결과 요약

### E2E 테스트 현황 (총 20건)

| 테스트 파일 | 테스트 수 | 상태 |
|------------|----------|------|
| ExchangeRateSchedulerE2ETest | 3 | ✅ GREEN |
| TickerSchedulerE2ETest | 3 | ✅ GREEN |
| PremiumSchedulerE2ETest | 3 | ✅ GREEN |
| PremiumAggregationE2ETest | 6 | ✅ GREEN |
| TickerAggregationE2ETest | 5 | ✅ GREEN |

### 변경된 파일 (커밋 대기)

#### 프로덕션 코드
- `PremiumAggregationScheduler.kt` — runCatching + jobExecutor 패턴
- `AggregationJob.kt` — Clock 주입
- `PremiumAggregationRepository.kt` — findLatest* 추가
- `TickerAggregationRepository.kt` — findLatest* 추가
- `PremiumSpreadBatchApplication.kt` — SchedulingConfig 분리 + @ConditionalOnProperty

#### 빌드/설정
- `apps/batch/build.gradle.kts` — testFixtures + integrationTest task
- `apps/batch/src/main/resources/application.yml` — test 프로필

#### 테스트 인프라
- `BatchTestConfig.kt` — MockWebServer + Client @Primary + Redisson + DistributedLockManager
- `BatchIntegrationTestBase.kt` — 베이스 클래스
- `batch-schema.sql` — batch DDL

#### E2E 테스트 (5개 신규)
- `ExchangeRateSchedulerE2ETest.kt`
- `TickerSchedulerE2ETest.kt`
- `PremiumSchedulerE2ETest.kt`
- `PremiumAggregationE2ETest.kt`
- `TickerAggregationE2ETest.kt`

#### 단위 테스트 (기존 수정)
- `PremiumAggregationSchedulerTest.kt` — jobExecutor mock 추가
