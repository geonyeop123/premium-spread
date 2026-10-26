# 환율 제공자 Twelve Data 교체와 FX 캐시 TTL 여유 (issue #93) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 환율을 Twelve Data 에서 5분마다 수집하고, 주기 5분 ↔ 허용 공백 30분 ↔ 캐시 TTL 40분을 테스트로 묶어 캐시가 freshness 판정보다 먼저 만료되지 않게 한다.

**Architecture:** Domain `FxIngestionFreshness`(허용 공백)를 기준으로 `RedisTtl.FX`(infra:common 테스트)와 수집 주기(apps:batch 기동 검증)가 관계를 강제한다.
포트 `ExchangeRateProvider` 와 `ExchangeRateClient` 등 클래스 이름·env 이름은 유지하고 adapter 구현만 Twelve Data `GET /exchange_rate`(헤더 인증)로 바꾼다.
새 port·Job·JobId·migration 없음.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.x (WebFlux `WebClient`, `@ConfigurationProperties`), Reactor `Retry.backoff`, MockWebServer(okhttp3 4.12), MySQL·Redis Testcontainers, JUnit5 + AssertJ.

**Spec:** `docs/work/fx-provider-twelve-data/design.md` · **DoD:** `docs/work/fx-provider-twelve-data/dod.md`

## Global Constraints

- base `728e3f1`(Task 0 확인). 커밋은 오케스트레이터가 한다 — 각 태스크 끝의 Commit 줄은 메시지 제안이다.
- **실제 Twelve Data 호출 금지.** MockWebServer 와 가짜 key(`test-key`, `fake-key-SENTINEL-93`)만. 환율 `1234.5678`, timestamp `1767225600`(`2026-01-01T00:00:00Z`).
- **테스트 소스에 `https://api.twelvedata.com` 같은 scheme 포함 외부 URL 을 쓰지 않는다** — `TestIsolationArchitectureTest` 위반. host 비교는 `"api.twelvedata.com"` 문자열로.
- key 는 URL·예외 메시지·로그·`toString()` 에 나타나지 않는다. HTTP 오류 본문과 제공자 `message` 는 예외·로그에 넣지 않는다(design §4.4–4.6).
- 허용 예외: `AccountPnlMonitorJobTest` 의 dod R5 범위뿐. `ExchangeRateClientTest`·`ExchangeRateSchedulerE2ETest` 는 동결 대상이 아니라 재작성·확장한다.
- Domain 에 framework 의존 추가 금지(`FxIngestionFreshness` 는 `java.time` 만). 앱 main 은 infrastructure·`modules:redis` 타입을 import 하지 않는다.
- Redis TTL 을 바꾸는 커밋은 `docs/runbooks/redis-contract.md` 를 함께 바꾼다(Task 2).
- `JobConfig`·`JobId`·Redis key·payload·Flyway·env 이름 무변경.
- 컴파일 실패를 RED 로 쓰지 않는다. RED 는 stub 또는 기존 구현에서의 assertion 실패다.
- 각 태스크 끝에 `./gradlew test --offline --no-daemon` 이 초록이어야 한다. integrationTest 소스도 컴파일이 깨진 채로 두지 않는다.
- 모든 gradle 명령은 `--offline --no-daemon`. ktlint·detekt 는 로컬 실행 불가 — CI 로 확인(dod R8), 우회 금지.

## Review Focus

1. **시간 불변식** — `RedisTtl.FX − MAX_INGESTION_GAP ≥ 5분`, `fixedRate × 2 ≤ MAX_INGESTION_GAP` 이 테스트·기동 검증으로 강제되는가. 의존 방향(modules:redis ↛ domain/apps)이 그대로인가(Task 2·4).
2. **key 누출** — 헤더 인증, URL 에 key 없음, 오류 본문·`message` 비노출, `Properties.toString()` override(Task 3).
3. **크레딧 예산** — 기본 `maxRetries = 1`, 429 비재시도, `retryableStatuses = 500..599`(Task 3, design §5).
4. **거절 규칙** — HTTP 200 + `status:error`, symbol 불일치·누락, rate ≤ 0, timestamp 누락이 모두 재시도 없이 계약 오류인가(Task 3).
5. **허용 예외 범위** — `AccountPnlMonitorJobTest` diff 가 R5 뿐인가(Task 4).

---

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `domain/src/main/kotlin/io/premiumspread/domain/exchangerate/FxIngestionFreshness.kt` (신규) | 허용 공백 30분, `isStale` | 1 |
| `modules/redis/src/main/kotlin/io/premiumspread/redis/RedisTtl.kt` | `FX` 40분 + 주석 | 2 |
| `docs/runbooks/redis-contract.md` | TTL 40분 + 불변식 한 줄 | 2 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/ExchangeRateClientProperties.kt` | 기본값(endpoint·maxRetries·retryableStatuses), `toString` override | 3 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/ExchangeRateResponse.kt` | Twelve Data DTO | 3 |
| `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/ExchangeRateClient.kt` | 요청·헤더·매핑·거절·예외·provider tag | 3 |
| `apps/batch/src/main/resources/application.yml` | `exchange-rate` endpoint·max-retries(3), scheduling fixed-rate(4) | 3·4 |
| `apps/batch/src/integrationTest/kotlin/io/premiumspread/scheduler/ExchangeRateSchedulerE2ETest.kt` (package `io.premiumspread.interfaces.scheduling`) | raw JSON 픽스처, 확장 단언 | 3 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt` | `FxIngestionFreshness.isStale` 사용, private 상수 제거 | 4 |
| `apps/batch/src/main/kotlin/io/premiumspread/application/job/fx/FxIngestionJob.kt` | 성공 로그 키 | 4 |
| `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/BatchSchedulingProperties.kt` | `ExchangeRate` 기본 5분·검증 | 4 |
| `apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling/ExchangeRateScheduler.kt` | `@Scheduled` fallback `300000` | 4 |
| 문서·주석 (design §9 표) | | 5 |
| tests — domain `FxIngestionFreshnessTest`; infra:common `FxCacheTtlContractTest`; infra:batch `ExchangeRateClientTest`(재작성); apps:batch `FxIngestionScheduleContractTest`(신규)·`AccountPnlMonitorJobTest`(허용 예외)·`ExchangeRateSchedulerE2ETest`(확장) | | 1~4 |

---

### Task 0: Gate 1 기준선

**Files:** 없음 (dod `## 증거 로그` 에 append)

- [ ] **Step 1:** 기준선 실행·기록.
  ```bash
  git rev-parse --short HEAD && git merge-base HEAD origin/dev | cut -c1-7          # base 728e3f1 확인
  ./gradlew architectureTest --offline --no-daemon                                 # R1
  ./gradlew test --offline --no-daemon                                             # R2
  ./gradlew :apps:batch:integrationTest --offline --no-daemon                      # R3 (Docker)
  ./gradlew :infrastructure:common:integrationTest --offline --no-daemon           # R4 (Docker)
  ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon          # R6
  ls infrastructure/common/src/main/resources/db/migration | sort -V | tail -1     # 최신 번호 기록만 (migration 추가 없음)
  grep -rn "MAX_FX_INGESTION_GAP\|ofHours(2)" --include=*.kt apps/batch            # 허용 공백 사용처
  grep -rn "RedisTtl.FX\b" --include=*.kt .                                        # TTL 사용처
  grep -rn "ExchangeRateResponse(\|ExchangeRateClientProperties(" --include=*.kt . # DTO·Properties 생성처
  grep -rn "EXCHANGERATE_API\|exchangerate-api" . --exclude-dir=.git --exclude-dir=build
  grep -rn "import io.premiumspread.domain" apps/batch/src/main/kotlin/io/premiumspread/interfaces/scheduling
  ```
- [ ] **Step 2: 예상 결과.** merge-base `728e3f1`. gradle 5건 exit 0. `MAX_FX_INGESTION_GAP` 은 `AccountPnlMonitorJob.kt` 2곳(:259, :362), `ofHours(2)` 는 그 상수와
  `AccountPnlMonitorJobTest` :221, :231, :268. `RedisTtl.FX` 사용처는 `FxCacheWriter.kt:26` 하나. DTO 생성처는 `ExchangeRateSchedulerE2ETest` 하나, Properties 생성처는
  `ExchangeRateClientTest`·`BatchTestConfig`. `EXCHANGERATE_API` 는 `ExchangeRateClient.kt` 하나(대시보드·알림 규칙 참조 없음). scheduling 패키지의 Domain import 는 현재 0건
  (Task 4 가 처음 추가 — `ArchitectureBoundaryTest` 의 금지 대상은 기술 패키지라 허용). **다르면 멈추고 보고한다.** Docker 부재면 R3·R4 "미실행"(green 금지).
- [ ] **Step 3: Commit** — `docs: Twelve Data 환율 교체 Gate 1 기준선 기록` (dod 증거 로그만)

### Task 1: Domain 허용 공백 `FxIngestionFreshness` (AC1)

**Files:** Create `domain/src/main/kotlin/io/premiumspread/domain/exchangerate/FxIngestionFreshness.kt`;
Test Create `domain/src/test/kotlin/io/premiumspread/domain/exchangerate/FxIngestionFreshnessTest.kt`

**Interfaces — Produces (전문):**

```kotlin
package io.premiumspread.domain.exchangerate

import java.time.Duration
import java.time.Instant

/**
 * FX 수집이 마지막으로 성공한 뒤 환율을 믿을 수 있는 최대 공백.
 * 값의 나이(제공자 timestamp)가 아니라 수집 성공 시각 기준이다 — 주말엔 원천 값이 바뀌지 않는다.
 * RedisTtl.FX 는 이보다 5분 이상 길어야 하고(FxCacheTtlContractTest), 수집 주기는 이것의 절반 이하여야 한다(BatchSchedulingProperties).
 */
object FxIngestionFreshness {
    val MAX_INGESTION_GAP: Duration = Duration.ofMinutes(30)

    fun isStale(lastSuccessAt: Instant, now: Instant): Boolean
}
```

요건: `isStale` = `Duration.between(lastSuccessAt, now) > MAX_INGESTION_GAP`. 경계(정확히 30분)는 false, 음수 경과(미래 lastSuccess)는 false.

**테스트가 재는 것** (`FxIngestionFreshnessTest`, 고정 `t0 = Instant.parse("2026-10-23T05:00:00Z")`):
- 상수 = `Duration.ofMinutes(30)`
- 경과 0 → false, 30분 → false, 30분 + 1ms → true, −1분 → false

- [ ] **Step 1:** stub — `MAX_INGESTION_GAP = Duration.ofHours(2)`, `isStale` 는 `> MAX_INGESTION_GAP`. 테스트 작성.
- [ ] **Step 2:** dod AC1 명령 → RED(상수 단언·`30분+1ms → true` 실패). 증거 로그 AC1.
- [ ] **Step 3:** 상수 30분. dod AC1 명령 → PASS. `./gradlew test --offline --no-daemon` → PASS(아직 사용처 없음).
- [ ] **Step 4: Commit** — `feat: FX 수집 허용 공백 30분을 Domain 정책으로`

### Task 2: `RedisTtl.FX` 40분과 TTL 불변식 (AC2)

**Files:** Modify `modules/redis/src/main/kotlin/io/premiumspread/redis/RedisTtl.kt`(FX 한 줄 + 주석), `docs/runbooks/redis-contract.md`(:18 + 표 아래 한 줄);
Test Create `infrastructure/common/src/test/kotlin/io/premiumspread/infrastructure/common/cache/exchangerate/FxCacheTtlContractTest.kt`

**Interfaces — Produces (전문):**

```kotlin
    // FX 캐시: 40분. FX 수집 허용 공백(Domain FxIngestionFreshness, 30분) + 5분 이상이어야 한다 — 캐시가 freshness 판정보다
    // 먼저 만료되면 판정은 신선인데 값이 없는 #93 이 재발한다. infrastructure:common FxCacheTtlContractTest 가 강제한다.
    val FX: Duration = Duration.ofMinutes(40)
```

Redis 계약(전문): key `fx:{base}:{quote}`(무변경), TTL **40분**, payload `schema_version, base, quote, rate, timestamp, source`(무변경).

runbook 표 아래 한 줄(전문): `` `fx:{base}:{quote}` TTL 은 FX 수집 허용 공백(`FxIngestionFreshness.MAX_INGESTION_GAP`, 30분) + 5분 이상이어야 한다 — `FxCacheTtlContractTest` 가 강제한다. ``

**테스트가 재는 것** (`FxCacheTtlContractTest`, 순수 단위 — Spring·Redis 없음):
- `RedisTtl.FX == Duration.ofMinutes(40)`
- `RedisTtl.FX.minus(FxIngestionFreshness.MAX_INGESTION_GAP) >= Duration.ofMinutes(5)` (테스트 이름에 "캐시가 freshness 판정보다 먼저 만료되지 않는다")

- [ ] **Step 1:** 테스트 작성(`RedisTtl` 은 31분 그대로).
- [ ] **Step 2:** dod AC2 명령 → RED(두 단언 모두 실패: 31분, 여유 1분). 증거 로그 AC2.
- [ ] **Step 3:** `RedisTtl.FX` 40분 + 주석, runbook 갱신. dod AC2 명령 → PASS. `./gradlew test --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `fix: FX 캐시 TTL 을 허용 공백보다 길게 (40분)`

### Task 3: Twelve Data adapter (AC4, AC6)

**Files:** Modify `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/{ExchangeRateClientProperties,ExchangeRateResponse,ExchangeRateClient}.kt`,
`apps/batch/src/main/resources/application.yml`(`exchange-rate` 블록의 `endpoint`·`max-retries` 두 줄만);
Test Rewrite `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/ExchangeRateClientTest.kt`,
Modify `apps/batch/src/integrationTest/kotlin/io/premiumspread/scheduler/ExchangeRateSchedulerE2ETest.kt`. `BatchTestConfig`·`ExchangeWebClientConfig`·`BatchInfrastructureAutoConfiguration` 무변경.

**Interfaces — Produces (전문):**

```kotlin
// ExchangeRateClientProperties.kt
@Validated
@ConfigurationProperties(prefix = "exchange-rate")
data class ExchangeRateClientProperties(
    val endpoint: URI = URI.create("https://api.twelvedata.com"),
    @field:NotBlank
    val apiKey: String,
    @field:NotBlank
    val base: String = "USD",
    @field:NotBlank
    val quote: String = "KRW",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(3),
    @field:Min(0)
    @field:Max(10)
    val maxRetries: Int = 1,
    val initialBackoff: Duration = Duration.ofMillis(250),
    val maxBackoff: Duration = Duration.ofSeconds(2),
    val retryableStatuses: Set<Int> = (500..599).toSet(),
) {
    // init 의 require 6개는 기존 문구·조건 그대로
    fun isRetryable(status: Int): Boolean = status in retryableStatuses
    fun overallDeadline(): Duration   // 기존 식 그대로 — 기본값에서 17초

    override fun toString(): String =
        "ExchangeRateClientProperties(endpoint=$endpoint, apiKey=****, base=$base, quote=$quote, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, maxRetries=$maxRetries, " +
            "initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, retryableStatuses=$retryableStatuses)"
}
```

`toString()` 노출(전문): `endpoint, base, quote, connectTimeout, readTimeout, maxRetries, initialBackoff, maxBackoff, retryableStatuses` + 고정 `apiKey=****`.

```kotlin
// ExchangeRateResponse.kt — KDoc 은 Twelve Data 정상·오류 예시(가짜 값)로 교체
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeRateResponse(
    val symbol: String? = null,
    val rate: BigDecimal? = null,
    val timestamp: Long? = null,
    val status: String? = null,
    val code: Int? = null,
    val message: String? = null,
)
```

자동 `toString()` 노출(전문): `symbol, rate, timestamp, status, code, message` — 이 객체는 로그·예외에 넣지 않는다.

```kotlin
// ExchangeRateClient.kt — 공개 시그니처 무변경
class ExchangeRateClient(
    private val exchangeRateWebClient: WebClient,
    private val meterRegistry: MeterRegistry,
    private val properties: ExchangeRateClientProperties,
) : ExchangeRateProvider {
    fun fetchConfiguredRate(): ExchangeRateSnapshot
    override fun fetch(base: Currency, quote: Currency): ExchangeRateSnapshot
    fun fetch(baseCurrency: String, quoteCurrency: String): ExchangeRateSnapshot

    private companion object {
        const val PROVIDER = "TWELVE_DATA"
        const val FETCH_METRIC = "fx.fetch"
    }
}

open class ExchangeRateApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

open class ExchangeRateHttpException(val status: HttpStatusCode) :
    ExchangeRateApiException("Exchange rate HTTP ${status.value()}")

class RetryableExchangeRateException(status: HttpStatusCode) : ExchangeRateHttpException(status)
```

요청(전문): `GET /exchange_rate?symbol={BASE}/{QUOTE}` — `uri { it.path("/exchange_rate").queryParam("symbol", "{symbol}").build("$base/$quote") }`,
헤더 `Authorization: apikey ${properties.apiKey}` 를 요청마다. base·quote 는 `trim().uppercase()`.

거절·매핑 표(전문, 위에서부터 첫 일치):

| 조건 | 예외 | 메시지 | 재시도 |
|---|---|---|---|
| non-2xx, `properties.isRetryable(status)` | `RetryableExchangeRateException` | `Exchange rate HTTP {status}` | O |
| non-2xx, 그 외 | `ExchangeRateHttpException` | `Exchange rate HTTP {status}` | X |
| 본문 없음 | `ExchangeRateApiException` | `Exchange rate API returned an empty response` | X |
| `status.equals("error", ignoreCase = true)` | `ExchangeRateApiException` | `Exchange rate API error: code={code ?: "unknown"}` | X |
| `symbol == null` 또는 `!symbol.equals("$base/$quote", ignoreCase = true)` | `ExchangeRateApiException` | `Unexpected exchange rate symbol (expected $base/$quote)` | X |
| `rate == null` | `ExchangeRateApiException` | `No rate in response` | X |
| `rate.signum() <= 0` | `ExchangeRateApiException` | `Non-positive rate in response` | X |
| `timestamp == null` 또는 `timestamp <= 0` | `ExchangeRateApiException` | `No observation timestamp in response` | X |
| 그 외 | — | `ExchangeRateSnapshot(base, quote, rate, Instant.ofEpochSecond(timestamp), Exchange.FX_PROVIDER)` | — |

그 밖 요건:
- `WebClientRequestException`·`TimeoutException`(cause 포함)은 재시도 대상(기존 `isRetryable` 그대로). `onRetryExhaustedThrow` 는 마지막 실패를 그대로 던진다(기존).
- non-2xx 본문은 소비·폐기하고 메시지에 넣지 않는다(예: `releaseBody().then(Mono.error(...))`).
- body deadline 은 기존처럼 헤더 수신 뒤 `timeout(readTimeout)`, 전체는 `block(overallDeadline())`.
- metric outcome 분류 무변경. 로그 키는 design §4.6 — 성공 DEBUG `base, quote, rate, observedAt`; 재시도 WARN 회차·실패 메시지; 최종 ERROR `base, quote, cause`.
  **담지 않는다**: apiKey, `Authorization` 헤더, 응답 본문, `ExchangeRateResponse` 객체, 제공자 `message`.
- yml(전문, 두 줄만): `endpoint: ${EXCHANGE_RATE_ENDPOINT:https://api.twelvedata.com}`, `max-retries: ${EXCHANGE_RATE_MAX_RETRIES:1}`.

**테스트가 재는 것** (`ExchangeRateClientTest`, MockWebServer, `@ExtendWith(OutputCaptureExtension::class)`, client 헬퍼는 endpoint = `server.url("/").toUri()`, connect 100ms, backoff 10/20ms):
- (a) 요청 — `takeRequest()`: method `GET`, `requestUrl.encodedPath == "/exchange_rate"`, `requestUrl.queryParameter("symbol") == "USD/KRW"`, `getHeader("Authorization") == "apikey test-key"`,
  `requestUrl.toString()` 에 `test-key` 없음, `requestCount == 1`
- (b) 매핑 — `{"symbol":"USD/KRW","rate":1234.5678,"timestamp":1767225600}` → base `USD`, quote `KRW`, rate `isEqualByComparingTo("1234.5678")`, `observedAt == Instant.parse("2026-01-01T00:00:00Z")`, source `FX_PROVIDER`
- (c) 거절 8종(각 `maxRetries = 3` 으로 두고 `requestCount == 1` 확인 — 재시도가 없음을 잰다): 200+`{"code":429,"message":"x","status":"error"}` → 메시지 `Exchange rate API error: code=429`;
  symbol `EUR/USD`; symbol 누락; rate 누락; rate `0`; rate `-1`; timestamp 누락; timestamp `0` — 메시지는 위 표 전문과 `isEqualTo`
- (d) 재시도 — `ExchangeRateClientProperties(apiKey = "test-key")` 의 `maxRetries == 1`, `retryableStatuses == (500..599).toSet()`, `overallDeadline() == Duration.ofSeconds(17)`
  (이름: "기본 deadline 은 lock:fx execution timeout 30초보다 짧다"), `endpoint.host == "api.twelvedata.com"`; 503×2 + 기본 `maxRetries`(1) → `RetryableExchangeRateException`·요청 2회;
  429(`maxRetries = 3`) → `ExchangeRateHttpException` 이고 `RetryableExchangeRateException` 아님·요청 1회; 401(`maxRetries = 3`) → 요청 1회; read timeout(`maxRetries = 1`, readTimeout 200ms, 본문 지연 2s ×2) → 요청 2회
- (e) 누출 — key `fake-key-SENTINEL-93`, 401 + 본문 `{"code":401,"message":"bad key fake-key-SENTINEL-93","status":"error"}` → 예외 메시지 `isEqualTo("Exchange rate HTTP 401")`;
  `CapturedOutput` 에 `fake-key-SENTINEL-93` 없음; 요청 URL 에 sentinel 없음; `properties.toString()` 에 sentinel 없음·`apiKey=****` 포함

**E2E 가 재는 것** (`ExchangeRateSchedulerE2ETest`, 기존 `BatchIntegrationTestBase`·`exchangeRateMockServer`·`BatchTestConfig(maxRetries = 2)`):
- `enqueueSuccessResponse` 를 DTO 생성자 대신 raw JSON `{"symbol":"USD/KRW","rate":1234.5678,"timestamp":1767225600}` 로(ObjectMapper·DTO import 제거)
- 기존 4개 테스트의 기대 rate 를 `1234.5678` 로. 추가 단언: DB `observed_at` 이 `2026-01-01T00:00:00Z`(`Timestamp.toInstant()` 비교, 세션 time_zone `+00:00` 은 `BatchTestConfig` 가 설정),
  Hash `timestamp == "1767225600000"`, `redisTemplate.getExpire("fx:usd:krw", TimeUnit.SECONDS)` ∈ (2100, 2400], `exchangeRateMockServer.takeRequest().getHeader("Authorization")` 가 `apikey ` 로 시작
- 최종 실패(500×3) 테스트는 그대로 — DB·캐시 없음
- 주의: `MockWebServer` 는 컨텍스트 공유 bean 이라 이전 테스트의 미소비 요청이 남을 수 있다. `takeRequest` 단언은 해당 테스트에서 enqueue 한 응답 1개에 대응하는 요청만 읽고,
  필요하면 `takeRequest(1, SECONDS)` 로 비어 있음을 허용하지 않는다(요청이 없으면 실패).

- [ ] **Step 1:** 테스트 먼저 — `ExchangeRateClientTest` 재작성, E2E 픽스처·단언 변경. 둘 다 **기존 production 코드로 컴파일된다**(새 공개 API 를 쓰지 않는다: `ExchangeRateHttpException::class`,
  `RetryableExchangeRateException::class`, `ExchangeRateClientProperties(...)` 기존 생성자).
- [ ] **Step 2:** dod AC4 명령 → RED(경로 `/v6/...`·헤더 없음·매핑 실패·기본값 단언 실패 등). dod AC6 명령 → RED(Twelve Data JSON 을 구 DTO 가 해석 못 해 Job Failure → DB 행 단언 실패). 증거 로그 AC4·AC6.
- [ ] **Step 3:** 위 전문대로 구현, yml 두 줄. dod AC4 → PASS, dod AC6 → PASS(Docker). `./gradlew architectureTest --offline --no-daemon`(R1 — 테스트 URL 격리 포함) → PASS. `./gradlew test --offline --no-daemon` → PASS.
- [ ] **Step 4: Commit** — `feat: 환율 제공자를 Twelve Data 로 교체 (헤더 인증, 429 비재시도)`

### Task 4: Job·Scheduler — 허용 공백과 수집 주기 5분 (AC5, AC3)

**Files:** Modify `apps/batch/src/main/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJob.kt`, `.../application/job/fx/FxIngestionJob.kt`,
`.../interfaces/scheduling/BatchSchedulingProperties.kt`(`ExchangeRate` 만), `.../interfaces/scheduling/ExchangeRateScheduler.kt`(fallback 한 곳), `apps/batch/src/main/resources/application.yml`(scheduling `fixed-rate` 한 줄);
Test 허용 예외 Modify `apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl/AccountPnlMonitorJobTest.kt`(dod R5),
Create `apps/batch/src/test/kotlin/io/premiumspread/config/FxIngestionScheduleContractTest.kt`

**Interfaces — Produces (전문):**

```kotlin
// AccountPnlMonitorJob — 생성자·run() 시그니처 무변경. checkFxFreshness 만:
private fun checkFxFreshness(now: Instant): ExchangeRateSnapshot {
    val reference = lastSuccess.findLastSuccess(JobId.FX_INGESTION) ?: checkNotNull(firstRunAt.get())
    if (FxIngestionFreshness.isStale(reference, now)) {
        throw AccountReadException(AccountReadFailureCategory.STALE_FX, "fx ingestion has not succeeded since $reference")
    }
    return fx.findLatest(Currency.USD, Currency.KRW)
        ?: throw AccountReadException(AccountReadFailureCategory.STALE_FX, "no USD/KRW rate is available")
}
// companion 의 MAX_FX_INGESTION_GAP 삭제. 나머지 상수 무변경.
```

```kotlin
// BatchSchedulingProperties.ExchangeRate
data class ExchangeRate(
    val fixedRate: Duration = Duration.ofMinutes(5),
    val startupDelay: Duration = Duration.ofSeconds(5),
) {
    init {
        require(fixedRate.isPositive()) { "exchangeRate.fixedRate must be positive." }
        require(!startupDelay.isNegative) { "exchangeRate.startupDelay must not be negative." }
        require(fixedRate.multipliedBy(2) <= FxIngestionFreshness.MAX_INGESTION_GAP) {
            "exchangeRate.fixedRate must be at most half of the FX ingestion gap ${FxIngestionFreshness.MAX_INGESTION_GAP}."
        }
    }
}
```

```kotlin
// ExchangeRateScheduler
@Scheduled(fixedRateString = "\${batch.scheduling.exchange-rate.fixed-rate:300000}")
```

```yaml
    exchange-rate:
      fixed-rate: ${EXCHANGE_RATE_FIXED_RATE:5m}
```

`FxIngestionJob` — 시그니처·`JobResult` 분기 무변경(성공 → `JobResult.Success`, 모든 예외 → `JobResult.Failure(exception)`, DB-first 순서 유지). 성공 INFO 로그 키: `base`, `quote`, `rate`, `observedAt`
(snapshot 값에서, 하드코딩 `USD/KRW` 제거). 담지 않는다: apiKey, 응답 객체.

허용 예외 수정 (전문 범위, `AccountPnlMonitorJobTest` 한 파일):
- `:221` `Duration.ofHours(2)` → `FxIngestionFreshness.MAX_INGESTION_GAP`, `:231` 같음, `:268` `clock.advance(FxIngestionFreshness.MAX_INGESTION_GAP)`
- 이름 `:219` `환율 수집 마지막 성공이 2시간을 넘으면 STALE_FX` → `환율 수집 마지막 성공이 허용 공백을 넘으면 STALE_FX`, `:230` `…정확히 2시간이면 계산한다` → `…정확히 허용 공백이면 계산한다`,
  `:239` `…수집 성공이 2시간 이내면 계산한다` → `…수집 성공이 허용 공백 이내면 계산한다`, `:261` `…첫 실행 시각부터 2시간을 잰다` → `…첫 실행 시각부터 허용 공백을 잰다`
- `describedAs` `:270` `첫 실행 후 정확히 2시간은 신선` → `첫 실행 후 정확히 허용 공백은 신선`, `:275` `2시간 1밀리초는 STALE_FX` → `허용 공백 + 1밀리초는 STALE_FX`
- import `io.premiumspread.domain.exchangerate.FxIngestionFreshness` 1줄. 그 밖 줄 무변경(`:241` 의 10분, `:277` 의 6분 포함)

**테스트가 재는 것**:
- `AccountPnlMonitorJobTest`(AC5) — 위 수정 후 4개 테스트가 30분 경계에서 성립: 정확히 30분 → 수익 메일, 30분+1ms → `환율 수집 지연`, 첫 실행 기준 30분 신선·+1ms stale
- `FxIngestionScheduleContractTest`(AC3) — `ExchangeRate()` 기본 5분·5초; `ExchangeRate(fixedRate = Duration.ofMinutes(15))` 생성 성공; `ExchangeRate(fixedRate = Duration.ofMinutes(15).plusMillis(1))`
  → `IllegalArgumentException`·메시지에 `exchangeRate.fixedRate`; `ClassPathResource("application.yml")` 본문이 `fixed-rate: ${EXCHANGE_RATE_FIXED_RATE:5m}`·`max-retries: ${EXCHANGE_RATE_MAX_RETRIES:1}`·`api.twelvedata.com`
  을 포함하고 `exchangerate-api.com` 을 포함하지 않음(scheme 포함 URL 을 테스트 소스에 쓰지 않는다)

- [ ] **Step 1:** 테스트 먼저 — R5 수정, `FxIngestionScheduleContractTest` 작성.
- [ ] **Step 2:** dod AC5 명령 → RED(Job 이 아직 2시간: `30분+1ms → STALE_FX` 와 첫 실행 `+1ms` 단언 실패). dod AC3 명령 → RED(기본 30분, 15분+1ms 허용, yml `30m`). 증거 로그 AC5·AC3.
- [ ] **Step 3:** 위 전문대로 구현. dod AC5·AC3 → PASS. `./gradlew architectureTest --offline --no-daemon`(scheduling → domain import 허용 확인) → PASS. `./gradlew test --offline --no-daemon` → PASS.
  `./gradlew :apps:batch:integrationTest --offline --no-daemon`(R3) → PASS.
- [ ] **Step 4: Commit** — `fix: FX 수집 주기 5분과 허용 공백 30분을 기동 시 검증`

### Task 5: 문서·주석 (AC7)

**Files:** Modify `docs/runbooks/deployment.md`, `docs/runbooks/account-pnl-alert.md`(:413, :418, :430), `.env.example`(:5-6), `deploy/.env.prd.example`(:42-43),
`.ai/architecture/ARCHITECTURE_DESIGN.md`(:76), `.ai/PROJECT_STATUS.md`(:65), `.ai/context/project-overview.md`(:74), `README.md`(:78),
`infrastructure/common/src/main/kotlin/io/premiumspread/infrastructure/common/persistence/jdbc/exchangerate/JdbcExchangeRateWriteRepository.kt`(KDoc 한 줄),
`apps/api/src/main/kotlin/io/premiumspread/application/tracking/TrackingFacade.kt`(`FX_MAX_AGE` KDoc 만). (`redis-contract.md` 는 Task 2, `ExchangeRateResponse` KDoc 은 Task 3)

요건 (문구는 구현 재량, 담아야 할 사실은 고정):
- `deployment.md` `:44` — `EXCHANGE_RATE_API_KEY`(Twelve Data API key). 신규 `### 환율 제공자 (Twelve Data)`:
  발급(twelvedata.com, Basic 무료: 분당 8·하루 800 크레딧); 인증은 `Authorization: apikey` 헤더라 key 가 URL·로그에 남지 않음; 주기 5분·`max-retries` 1·429 비재시도;
  최악 하루 호출 식 `(86400 / fixedRate초 + 2) × (max-retries + 1)` 과 "max-retries 1 기준 fixed-rate 4분 미만 금지"; **key 하나에 batch 프로세스 하나**;
  전환 시 지울 override `EXCHANGE_RATE_ENDPOINT`(구 제공자 주소)·`EXCHANGE_RATE_FIXED_RATE`·`EXCHANGE_RATE_MAX_RETRIES` 와 `EXCHANGE_RATE_FIXED_RATE` 가 15분 초과면 기동 실패;
  주말 동작(design §7.1 표 요약 — Tracking 확정 불가는 후속).
- `account-pnl-alert.md` — `2시간` → `30분`(:413, :430), `(30분 주기)` → `(5분 주기)`(:418). 신선도 행에 "캐시 TTL 40분은 이 30분보다 길다" 한 구절.
- env 예시 2개 — 주석만 Twelve Data 로, 키 이름·placeholder 그대로.
- ARCHITECTURE `FX API (30분)` → `Twelve Data FX API (5분)`; PROJECT_STATUS `USD/KRW 30분 수집` → `USD/KRW 5분 수집(Twelve Data)`; project-overview `30분마다` → `5분마다(Twelve Data)`; README 표 행 `5분 | Twelve Data → Redis + DB`.
- `JdbcExchangeRateWriteRepository` KDoc `(30분 단위)` → `수집 회차마다 저장, 같은 observed_at 은 한 행으로 수렴`.
- `TrackingFacade` KDoc — "batch exchange-rate 주기 5분. 35분은 유지. 제공자 timestamp 기준이라 주말(forex 휴장)엔 확정 불가 — 후속 F1(design fx-provider-twelve-data §8)". 코드 줄 무변경.
- 정본 문서(`AGENTS.md`·`.ai/*`·`docs/runbooks/*`)·env 예시·README 에 `exchangerate-api` 가 남지 않는다(`.ai/planning/**`·`docs/superpowers/**`·`docs/work/**` 의 과거 기록은 손대지 않는다).

- [ ] **Step 1:** 문서·주석 수정.
- [ ] **Step 2:** `bash docs/check-documentation.sh` → exit 0. `./gradlew harnessCheck --offline --no-daemon` → exit 0. `grep -rn "exchangerate-api" AGENTS.md .ai/*.md .ai/architecture .ai/context .ai/rules docs/runbooks README.md .env.example deploy/.env.prd.example` → 출력 없음.
  `./gradlew :apps:api:test --offline --no-daemon`(R9) → PASS. 증거 로그 AC7.
- [ ] **Step 3: Commit** — `docs: Twelve Data 환율 제공자와 5분 주기·TTL 40분 문서화`

### Task 6: 최종 gate

- [ ] **Step 1:** dod AC1~AC6 검증 명령 개별 실행, R1~R4·R6·R7·R9 실행(Docker 필요 항목 포함), R5 diff 확인. 결과를 증거 로그에 append.
- [ ] **Step 2:** `./gradlew jacocoTestReport jacocoTestCoverageVerification --offline --no-daemon` → exit 0(최종 증거는 CI artifact).
- [ ] **Step 3:** 경계면 대조 — `RedisTtl.FX` ↔ redis-contract.md, `FxIngestionFreshness` ↔ account-pnl-alert.md, yml `exchange-rate`·scheduling 기본값 ↔ deployment.md ↔ `ExchangeRateClientProperties` 기본값.
- [ ] **Step 4:** 판정 출력(dod 고정 포맷). AC7(T3)·AC8(T4) 이 남으므로 `AWAITING_HUMAN`. ktlint·detekt 는 CI(R8).

## 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| 초안 | — | — |
