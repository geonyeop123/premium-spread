# Findings: scheduler_e2e_testcases.md 문서 검토 (v2)

## 현황 요약

| 구분                                            | 상태                   |
|-----------------------------------------------|----------------------|
| 단위 테스트 (scheduler_testcases.md)               | 10개 테스트 클래스 전부 구현 완료 |
| E2E 테스트 (scheduler_e2e_testcases.md)          | 문서만 존재, 구현 0건        |
| E2E 인프라 (@Tag("integration"), TestContainers) | batch 모듈에 미구축        |

---

## 발견된 주요 갭 (14건)

### GAP-1: updateSummaryCache 구간 독립성 — 문서 vs 구현 불일치 (Critical)

**문서 (E2E #8):**
> `updateSummaryCache()` 실행 후 summary 캐시 `1m/10m/1h/1d`가 **모두** 저장된다.

**실제 구현 (`PremiumAggregationScheduler:69-89`):**

```kotlin
fun updateSummaryCache() {
    try {  // ← 단일 try-catch로 4개 구간 전체를 감싼다
        // 1m → 10m → 1h → 1d 순차 실행
    } catch (e: Exception) {
        log.error("Failed to update summary cache", e)
    }
}
```

**문제:** 중간 구간(예: 1h)에서 예외가 발생하면 이후 구간(1d)은 실행되지 않는다.

- 단위 테스트 문서에서도 `(현재 구현 기준 Red 예상)`으로 명시
- E2E happy case에서도 4개 구간 모두 정상 데이터가 준비되어야만 pass

**필요 조치:**

- 코드를 구간별 독립 try-catch로 수정 → 단위 테스트 2건 GREEN 전환 → E2E에서도 부분 실패 시 안전

---

### GAP-2: updateSummaryCache는 jobExecutor를 사용하지 않음 (Medium)

다른 모든 스케줄러 메서드는 `jobExecutor.execute(config) { ... }` 패턴이지만, `updateSummaryCache()`만 **직접 실행**.

**영향:**

- 분산 락 없음 → 멀티 인스턴스에서 중복 실행 가능
- 메트릭 없음 → 모니터링 사각지대
- last-run 갱신 없음

**E2E 영향:** E2E에서 lock 검증 불가, 별도 취급 필요

---

### GAP-3: 1m/10m vs 1h/1d 데이터 소스 구분 미흡 (Medium)

**문서 (E2E #8.1):** "초/분/시 집계 데이터를 준비한다"

**실제 데이터 소스:**

| 구간  | 메서드                              | 소스                                |
|-----|----------------------------------|-----------------------------------|
| 1m  | `calculateSummaryFromSeconds()`  | 초 단위 ZSet (`premium:seconds:btc`) |
| 10m | `calculateSummaryFromSeconds()`  | 초 단위 ZSet (`premium:seconds:btc`) |
| 1h  | `calculateSummary(MINUTES, ...)` | 분 집계 ZSet (`premium:minutes:btc`) |
| 1d  | `calculateSummary(HOURS, ...)`   | 시 집계 ZSet (`premium:hours:btc`)   |

**E2E 시딩 필요:**

- 1m/10m 테스트: `premium:seconds:btc` ZSet에 데이터 seed
- 1h 테스트: `premium:minutes:btc` ZSet에 데이터 seed
- 1d 테스트: `premium:hours:btc` ZSet에 데이터 seed

---

### GAP-4: PremiumRealtimeJob의 positionCacheService 의존성 누락 (Low)

**문서 (E2E #1):** 빗썸/바이낸스 티커와 환율 준비만 언급

**실제:** `positionCacheService.hasOpenPosition()` 결과에 따라 `saveHistory()` 호출 여부 결정

**권장:** E2E에서 position 데이터 없는 상태로 진행 (history 저장 skip), 문서에 명시

---

### GAP-5: Section 4 (fetchExchangeRateOnStartup) 실질적 중복 (Low)

`fetchExchangeRateOnStartup()`은 내부적으로 `fetchExchangeRate()`를 그대로 호출하므로 E2E 결과가 동일.

**권장:** Section 3과 통합하거나, Section 4는 "startup 시 호출되는지"만 확인

---

### GAP-6: 외부 API mocking 전략 미명시 (Medium)

**문서 원칙:** "외부 API는 mocking을 허용한다"

**미정의 사항:**

- mocking 방식: **MockWebServer** (이미 batch build.gradle.kts에 의존성 존재)
- 대상 API: BithumbClient, BinanceClient, ExchangeRateClient
- WebClient baseUrl override 전략: TestConfig에서 Bean 교체 필요

---

### GAP-7: 검증 방법 구체성 부족 (Medium)

"캐시가 저장된다", "DB에 저장된다" → 구체적 Redis key/DB table 명시 필요

| 검증 대상       | Redis Key / DB Table                                     |
|-------------|----------------------------------------------------------|
| 티커 현재 캐시    | Hash `ticker:{exchange}:{symbol}`                        |
| 티커 초 ZSet   | ZSet `ticker:seconds:{exchange}:{symbol}`                |
| 환율 캐시       | Hash `fx:usd:krw`                                        |
| 환율 DB       | `exchange_rate` table                                    |
| 프리미엄 현재 캐시  | Hash `premium:btc`                                       |
| 프리미엄 초 ZSet | ZSet `premium:seconds:btc`                               |
| 분 집계 캐시     | ZSet `premium:minutes:btc` / `ticker:minutes:{ex}:{sym}` |
| 시 집계 캐시     | ZSet `premium:hours:btc` / `ticker:hours:{ex}:{sym}`     |
| 분 DB        | `premium_minute` / `ticker_minute`                       |
| 시 DB        | `premium_hour` / `ticker_hour`                           |
| 일 DB        | `premium_day` / `ticker_day`                             |
| summary 캐시  | Hash `summary:{1m                                        |10m|1h|1d}:btc` |

---

### GAP-8: 테스트 인프라/환경 설정 미정의 (High)

batch 모듈에 integration test 인프라 0건.

---

### GAP-9: batch build.gradle.kts에 testFixtures 의존성 미선언 (Critical — 신규)

**현재 batch `build.gradle.kts`:**

```kotlin
testImplementation("org.testcontainers:testcontainers")
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

**누락:**

```kotlin
testImplementation(testFixtures(project(":modules:jpa")))    // MySqlTestContainersConfig, DatabaseCleanUp
testImplementation(testFixtures(project(":modules:redis")))  // RedisTestContainersConfig
```

API 모듈은 이 두 줄이 있지만 batch에는 없음. TestContainers 설정과 DB 초기화 유틸을 사용하려면 **반드시 추가 필요**.

---

### GAP-10: batch에 integrationTest Gradle task 미정의 (High — 신규)

API 모듈은 `integrationTest` task가 있어 `@Tag("integration")` 테스트를 분리 실행:

```kotlin
tasks.register<Test>("integrationTest") {
    useJUnitPlatform { includeTags("integration") }
}
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}
```

batch 모듈에는 이 설정이 없어:

- `./gradlew test` 실행 시 integration 테스트가 Docker 없이도 실행 시도 → 실패
- `./gradlew :apps:batch:integrationTest` 태스크 자체가 존재하지 않음

---

### GAP-11: application-test.yml 미존재 (High — 신규)

batch의 `@Scheduled` 메서드들이 테스트 context에서도 실행됨.

**필요:**

```yaml
# apps/batch/src/test/resources/application-test.yml
spring:
  task:
    scheduling:
      pool:
        size: 1
  main:
    allow-bean-definition-overriding: true

# 실제 스케줄링 비활성화를 위해 @Scheduled를 disable하거나
# TestConfig에서 SchedulingConfigurer로 제어
```

또는 `@TestPropertySource(properties = ["spring.scheduling.enabled=false"])` 전략

---

### GAP-12: AggregationJob 시간 윈도우 타이밍 문제 (Medium — 신규)

`AggregationJob.run()`:

```kotlin
val now = Instant.now()
val windowStart = now.minus(1, unit).truncatedTo(unit)
val windowEnd = windowStart.plus(1, unit)
```

- minute 집계: 현재 시각의 "직전 1분"을 조회
- E2E에서 `now`가 `13:05:23`이면 `[13:04:00, 13:05:00)` 윈도우를 조회

**E2E 시딩 전략:**

- seed 데이터의 timestamp를 **직전 완료된 시간 단위**에 맞춰야 함
- `Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(30, ChronoUnit.SECONDS)` 등으로 seed

---

### GAP-13: DB 검증용 조회 메서드 부재 (Low — 신규)

`PremiumAggregationRepository`, `TickerAggregationRepository`에 `save*` 메서드만 존재. 조회 메서드 없음.

E2E에서 DB 검증 시:

- 방안 A: `JdbcTemplate`로 직접 SELECT 쿼리 (추가 코드 최소)
- 방안 B: 테스트용 조회 메서드 추가 (코드 변경 필요)

**권장:** 방안 A — E2E 테스트 내에서 `JdbcTemplate` 직접 사용

---

### GAP-14: WebClient Bean override 전략 필요 (Medium — 신규)

TickerScheduler/ExchangeRateScheduler E2E에서 외부 API를 MockWebServer로 대체하려면:

- BithumbClient/BinanceClient/ExchangeRateClient의 WebClient baseUrl을 MockWebServer URL로 교체
- Spring context 내 WebClient Bean을 TestConfig에서 override

**전략:** TestConfig에서 각 Client Bean을 MockWebServer baseUrl로 생성하여 주입

---

## 긍정적 평가

- 11개 테스트케이스가 실제 스케줄러 메서드 1:1 대응으로 잘 구조화됨
- "외부 API는 mocking 허용" 원칙이 현실적
- 실행 순서 권장 섹션이 데이터 의존성을 잘 반영
- Happy case 위주로 E2E 범위를 제한한 것은 적절 (unit test에서 이미 edge case 커버)
