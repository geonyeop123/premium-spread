# 환율 제공자 Twelve Data 교체와 FX 캐시 TTL 여유 (issue #93) 설계

- slug: `fx-provider-twelve-data` · base `728e3f1`(origin/dev, Task 0 이 `git merge-base HEAD origin/dev` 로 확인) · branch `fix/issue-93-fx-provider-twelve-data`
- 입력: ③ 합의(오케스트레이터 전달 범위 — 제공자를 Twelve Data 로, 수집 주기 5분, #93 TTL 여유 함께 해결), `.ai/rules/{architecture,batch,testing,naming}.md`,
  `docs/runbooks/{redis-contract,deployment,account-pnl-alert}.md`, 현재 코드(아래 §2)
- 상태: DRAFT

## 0. 요약

#93 의 직접 원인은 `RedisTtl.FX = 31분` 과 수집 주기 30분의 여유가 1분뿐이었다는 것이다. 벽시계가 3분 튀자 캐시가 다음 수집 전에 만료됐고,
`AccountPnlMonitorJob` 은 "마지막 수집 성공 2시간 이내" 판정은 통과했지만 이어서 읽은 캐시가 비어 `STALE_FX` 로 실패했다. 즉 **캐시가 Job 의
freshness 판정보다 먼저 만료될 수 있는 구조** 자체가 결함이다.

조사 중 더 큰 문제가 드러났다. 현 제공자(exchangerate-api Free)는 하루 1회 갱신이라 김프 계산이 최대 하루 묵은 환율을 쓰고 있었다. 사용자 결정에 따라
제공자를 Twelve Data(Basic 무료)로 바꾸고 수집 주기를 5분으로 줄인다. 이 변경 안에서 TTL·허용 공백·주기의 관계를 하나의 불변식으로 묶는다.

핵심 결정:

| # | 결정 | 절 |
|---|---|---|
| K1 | 허용 공백을 Domain 상수 `FxIngestionFreshness.MAX_INGESTION_GAP = 30분` 으로 올리고 2시간에서 줄인다. `AccountPnlMonitorJob` 은 이 상수로 판정한다 | §3.1 |
| K2 | `RedisTtl.FX = 40분`. 불변식 `RedisTtl.FX ≥ MAX_INGESTION_GAP + 5분` 을 `infrastructure:common` 단위 테스트가 묶는다(이 모듈만 Domain 과 `modules:redis` 를 둘 다 본다) | §3.2 |
| K3 | 수집 주기 기본값 5분. `BatchSchedulingProperties.ExchangeRate` 가 `fixedRate × 2 ≤ MAX_INGESTION_GAP` 을 기동 시 검증한다(한 회차 실패를 견딘다) | §3.3 |
| K4 | 기존 포트 `ExchangeRateProvider` 와 클래스 이름(`ExchangeRateClient`·`ExchangeRateClientProperties`·`ExchangeRateResponse`)·설정 prefix `exchange-rate.*`·env 이름(`EXCHANGE_RATE_*`)을 유지하고 **구현 내용만** Twelve Data 로 바꾼다. 운영자는 키 값만 교체한다 | §4, §6 |
| K5 | 인증은 `Authorization: apikey <key>` 헤더. key 는 URL·예외 메시지·로그·`toString()` 어디에도 나타나지 않는다. HTTP 오류 본문은 메시지에 넣지 않는다 | §4.3, §4.5 |
| K6 | `max-retries` 기본 3 → **1**, 재시도 대상은 5xx·연결 오류·timeout. **429 는 재시도하지 않는다**. 최악 하루 호출 580 ≤ 800 | §5 |
| K7 | HTTP 200 이어도 본문 `status:"error"` 면 계약 오류(재시도 없음). symbol 불일치·rate ≤ 0·필드 누락도 계약 오류 | §4.4 |
| K8 | Flyway 불필요 — `exchange_rate` 스키마·unique key 그대로 | §7 |
| K9 | api `TrackingFacade.FX_MAX_AGE`(35분, 제공자 timestamp 기준)는 **이번 범위에서 동작을 바꾸지 않는다**. 주석만 현재 사실로 고치고, 주말 판정은 후속 후보로 남긴다 | §8 |

## 1. 범위

### 1.1 포함

- Domain `FxIngestionFreshness`(허용 공백 30분과 판정 함수) (§3.1)
- `RedisTtl.FX` 31분 → 40분, TTL 불변식 테스트, `docs/runbooks/redis-contract.md` 동시 갱신 (§3.2)
- `ExchangeRateClient`·`ExchangeRateClientProperties`·`ExchangeRateResponse` 를 Twelve Data `GET /exchange_rate` 로 교체, 재시도 정책, 비밀값 비노출 (§4, §5)
- `AccountPnlMonitorJob` 의 FX 허용 공백을 Domain 상수로, `FxIngestionJob` 성공 로그에 `observedAt` 추가 (§3.1, §4.6)
- 수집 주기 기본값 5분(yml·`BatchSchedulingProperties`·Scheduler fallback)과 기동 검증 (§3.3)
- yml `exchange-rate` 블록 기본값(endpoint·max-retries) (§6)
- 문서: redis-contract·deployment·account-pnl-alert runbook, `.env.example`·`deploy/.env.prd.example`, ARCHITECTURE·PROJECT_STATUS·project-overview·README 의 주기·제공자 서술,
  `JdbcExchangeRateWriteRepository`·`TrackingFacade` 의 낡은 주석 (§9)

### 1.2 제외 (scope creep 차단선)

- `ExchangeRateProvider` 포트 시그니처 변경, 새 포트·새 Job·새 JobId, `JobConfig`(lock:fx 35s/30s) 변경
- `PremiumRealtimeJob` 에 FX freshness 판정 추가, batch `FxRateReadAdapter` 에 DB fallback 추가
- api `TrackingFacade.FX_MAX_AGE` 의 값·판정 방식 변경(주말 대응) — 후속 후보 F1 (§8)
- 기동 직후 이중 실행(fixed-rate 첫 tick + startup 실행) 제거 — 후속 후보 F2 (§5.1)
- 제공자 다중화·fallback 제공자, 크레딧 사용량 metric
- env 이름 변경, 클래스·패키지 rename, Redis key·payload 변경, Flyway migration

## 2. 현재 구조

```text
ExchangeRateScheduler (fixed-rate 30m + startup 5s)
  → FxIngestionJob.run()  [JobExecutor: lock:fx, lease 35s, timeout 30s]
      → ExchangeRateProvider.fetch(USD, KRW)      ← infrastructure:batch ExchangeRateClient (exchangerate-api v6, key 가 URL path 에)
      → FxRateWritePort.save   (MySQL exchange_rate, ON DUPLICATE KEY UPDATE rate)   ← DB-first
      → FxRateCacheWritePort.save (Redis fx:usd:krw, TTL RedisTtl.FX=31m)

소비자
  PremiumRealtimeJob      → FxRateReadPort(캐시만) — miss 면 missing_data 로 건너뜀, freshness 판정 없음
  AccountPnlMonitorJob    → 마지막 FX_INGESTION 성공이 2h 이내인지 → 캐시 값 필수 (없으면 STALE_FX)
  api TrackingFacade      → premium snapshot.fxObservedAt(=제공자 timestamp) 이 [now-35m, now] 안인지
```

| 테스트 | 강제하는 것 | 이 설계의 대응 |
|---|---|---|
| `ExchangeRateClientTest` (infra:batch) | exchangerate-api JSON, 5xx 재시도 상한, 4xx 비재시도, read timeout 재시도 | Twelve Data 계약으로 **다시 쓴다**(같은 파일). 5xx·4xx·timeout 시나리오는 유지 |
| `ExchangeRateSchedulerE2ETest` (apps:batch integration) | `ExchangeRateResponse(result=…, conversionRate=…)` 생성자로 응답 직렬화, 실패 3회(500) | 응답을 **raw JSON 문자열**로 바꾼다(DTO 생성자에 묶이지 않게). `BatchTestConfig` 의 `maxRetries = 2` 는 유지 — 500×3 시나리오가 그대로 성립 |
| `AccountPnlMonitorJobTest` :218-280 | 허용 공백 2시간 경계(2h 통과, 2h+1ms 실패, 첫 실행 기준 2h) | **허용 예외** — 기대값을 `FxIngestionFreshness.MAX_INGESTION_GAP` 으로, 이름의 "2시간"을 "허용 공백"으로 (dod R5) |
| 그 밖의 `JobLastSuccessReadPort` fake | 모두 `now − 10분` 반환 | 30분 안이라 무수정 통과 |
| `TestIsolationArchitectureTest` | 테스트 소스에 `https?://` 외부 host 금지 | 테스트는 Twelve Data URL 을 **scheme 포함으로 쓰지 않는다**. 기본 endpoint 확인은 `endpoint.host == "api.twelvedata.com"` 처럼 host 문자열로만 |

## 3. 시간 계약 — 주기·허용 공백·TTL

### 3.1 허용 공백 `FxIngestionFreshness` (Domain, `io.premiumspread.domain.exchangerate`)

```kotlin
object FxIngestionFreshness {
    val MAX_INGESTION_GAP: Duration = Duration.ofMinutes(30)

    /** lastSuccessAt 으로부터 now 까지가 MAX_INGESTION_GAP 을 넘으면 true. 경계(정확히 30분)는 신선, 미래 시각(음수 경과)은 신선. */
    fun isStale(lastSuccessAt: Instant, now: Instant): Boolean
}
```

- 의미: "FX 수집이 마지막으로 성공한 뒤 이만큼 지나면 환율을 믿지 않는다." 값의 나이(제공자 timestamp)가 아니라 **수집 성공 시각**이 기준이다 — 주말엔 원천 값이 바뀌지
  않기 때문이다(기존 `checkFxFreshness` KDoc 과 같은 원칙).
- 왜 Domain 인가: 허용 공백은 "얼마나 묵은 환율로 손익을 계산해도 되는가"라는 비즈니스 정책이고, 캐시 TTL(infrastructure)과 주기 검증(apps:batch)이 **둘 다 참조**해야
  한다. 두 모듈이 공통으로 볼 수 있는 곳은 Domain 뿐이다. I/O 없는 `object` 라 port 가 아니다(구현이 하나뿐이고 대체 가치가 없다).
- **2시간 → 30분 근거.**
  1. 5분 주기에서 2시간은 24회 연속 실패를 견딘다는 뜻이다. 그동안 owner 는 아무 신호 없이 묵은 환율로 계산된 손익을 받는다.
  2. 실제 동작은 이미 30분대였다 — TTL 31분이 지나면 캐시가 비어 `STALE_FX` 가 났으므로, "2시간"은 문서상 숫자일 뿐 실효 한도는 31분이었다. 30분으로 적는 것은
     실효 동작을 정직하게 드러내는 것이고, runbook `account-pnl-alert.md:418` 의 "PC 잠자기 후 중단·복구 메일 한 쌍"이 나는 문턱도 사실상 그대로다.
  3. 30분 = 5분 주기 6회. 일시적 제공자 장애(5xx 몇 회)·429 한두 회는 경보 없이 견디고, 크레딧 소진처럼 지속되는 장애는 30분 안에 `환율 수집 지연` 중단 메일로 드러난다.
- `AccountPnlMonitorJob.checkFxFreshness` 는 `Duration.between(reference, now) > MAX_FX_INGESTION_GAP` 을 `FxIngestionFreshness.isStale(reference, now)` 로 바꾸고 private
  상수 `MAX_FX_INGESTION_GAP` 를 지운다. 기준 시각(`findLastSuccess(FX_INGESTION) ?: firstRunAt`)·이어지는 캐시 필수 조회·예외 범주·메시지는 그대로다.

### 3.2 캐시 TTL `RedisTtl.FX = 40분`

원칙: **캐시는 Job 의 freshness 판정보다 먼저 만료되면 안 된다.** `AccountPnlMonitorJob` 이 "신선"으로 판정한 순간 캐시가 비어 있으면 #93 이 재발한다.

```text
캐시 쓰기 시각 W, 성공 기록 시각 S  (같은 회차, W ≤ S ≤ W + execution timeout 30s)
판정이 신선 ⇔ now − S ≤ GAP(30m)
캐시 만료 = W + TTL ≥ S − 30s + TTL
필요조건: S − 30s + TTL > S + GAP + 시계 튐  ⇔  TTL > GAP + 30s + 시계 튐
```

| 항목 | 값 |
|---|---|
| `MAX_INGESTION_GAP` | 30분 |
| 쓰기→성공 기록 지연 상한 | 30초 (`lock:fx` execution timeout) |
| 관측된 시계 튐 | 3분 (#93) |
| 테스트가 강제하는 최소 여유 `TTL − GAP` | **5분** (30초 + 3분 + 여분) |
| 채택 `RedisTtl.FX` | **40분** (실제 여유 10분) |

- 불변식 테스트 위치: `infrastructure:common` 단위 테스트 `FxCacheTtlContractTest`. 이 모듈은 `domain` 과 `modules:redis` 를 모두 `implementation` 으로 가지고 있고
  `FxCacheWriter` 가 `RedisTtl.FX` 의 유일한 사용처다. `modules:redis` 는 Domain·apps 를 모르고(의존 방향 유지), apps 는 `modules:redis` 상수를 import 하지 않는다.
- 부작용: `PremiumRealtimeJob` 은 freshness 판정 없이 캐시를 쓰므로, 수집이 멈추면 마지막 값으로 최대 40분(기존 31분) 계속 계산한다. 정상 운영에선 5분마다 TTL 이
  갱신되므로 차이가 없다. Premium 쪽 freshness 판정은 범위 밖(§1.2).
- TTL 값을 고치면 이 테스트와 `docs/runbooks/redis-contract.md` 를 같은 커밋에서 고친다(`.ai/rules/architecture.md` Persistence와 Cache).

### 3.3 수집 주기 5분과 기동 검증

- 기본값: `batch.scheduling.exchange-rate.fixed-rate: ${EXCHANGE_RATE_FIXED_RATE:5m}`, `BatchSchedulingProperties.ExchangeRate.fixedRate = Duration.ofMinutes(5)`,
  `ExchangeRateScheduler` 의 `@Scheduled` fallback `300000`. startup-delay 5s 는 그대로.
- 검증(전문):

```kotlin
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

- 의미: 주기가 허용 공백의 절반 이하여야 한 회차가 실패해도 `STALE_FX` 가 나지 않는다. 기본 5분이면 6회 중 5회 실패까지 견딘다. 이 검증은 운영자가 예전 값
  `EXCHANGE_RATE_FIXED_RATE=30m` 을 남겨 두면 **기동 실패**로 드러나게 한다(조용히 매 회차 `STALE_FX` 가 나는 것보다 낫다). runbook 에 적는다.
- 하한(크레딧) 검증은 넣지 않는다 — 크레딧 한도는 제공자 요금제의 사실이라 apps:batch 의 스케줄 설정이 알 대상이 아니다. 대신 §5 의 식을 runbook 에 둔다. `[ASSUMPTION]` A3.
- `interfaces.scheduling` 이 Domain 을 import 하는 것은 경계 위반이 아니다 — 금지 대상은 기술 패키지(`ArchitectureBoundaryTest` `batch scheduling interfaces do not depend
  on technical implementations`)다. Task 0 이 확인한다.

## 4. Twelve Data adapter (`infrastructure:batch` `io.premiumspread.infrastructure.batch.exchange`)

### 4.1 포트·클래스 유지

`ExchangeRateProvider.fetch(base, quote): ExchangeRateSnapshot` 그대로. `ExchangeRateClient` 가 계속 구현한다. 클래스 이름은 제공자 중립으로 남긴다(K4) —
`BatchInfrastructureAutoConfiguration`·`BatchTestConfig` 의 참조가 무변경이고, 제공자 고유 사항은 KDoc 과 metric tag(`provider=TWELVE_DATA`)에 둔다.

### 4.2 요청

```text
GET {endpoint}/exchange_rate?symbol={BASE}/{QUOTE}
Authorization: apikey {apiKey}
User-Agent: PremiumSpread-Batch/1.0      (기존 WebClient 기본 헤더)
```

- `symbol` 은 URI 템플릿 변수로 넘긴다(`queryParam("symbol", "{symbol}")` + `build("$base/$quote")`). `/` 의 인코딩 여부와 무관하게 서버가 `USD/KRW` 로 디코딩한다.
  테스트는 `requestUrl.queryParameter("symbol") == "USD/KRW"` 로 잰다.
- `Authorization` 헤더는 **요청마다** `ExchangeRateClient` 가 붙인다. `ExchangeWebClientConfig` 의 기본 헤더로 두지 않는다 — `BatchTestConfig` 처럼 WebClient 를 따로
  만드는 경로에서도 헤더 계약이 성립해야 하고, 비밀값을 공유 bean 설정에 퍼뜨리지 않는다.
- base·quote 는 기존처럼 `trim().uppercase()`.

### 4.3 응답 DTO (전문)

```kotlin
/** Twelve Data `GET /exchange_rate` 응답. 성공·오류 본문을 한 타입으로 받는다. */
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

- 실측 정상: `{"symbol":"EUR/USD","rate":1.12167,"timestamp":1792975320}`. 실측 오류: HTTP 401 + `{"code":401,"message":"…","status":"error"}`.
- `rate` 는 JSON number → Jackson 이 `BigDecimal` 로 텍스트 그대로 읽는다(double 경유 없음).
- 자동 `toString()` 노출: `symbol, rate, timestamp, status, code, message`. `message` 는 제공자가 만든 문구라 무엇이 들어올지 보장할 수 없다 → **이 객체를 로그·예외에
  넣지 않는다.** 비밀값은 포함되지 않는다(요청 헤더는 응답에 없다).

### 4.4 응답 매핑과 거절 규칙

| 순서 | 조건 | 결과 | 예외 메시지 (전문) | 재시도 |
|---|---|---|---|---|
| 1 | HTTP 2xx 아님, status ∈ `retryableStatuses`(기본 500..599) | `RetryableExchangeRateException` | `Exchange rate HTTP {status}` | O |
| 2 | HTTP 2xx 아님, 그 외(401·403·404·429 …) | `ExchangeRateHttpException` | `Exchange rate HTTP {status}` | X |
| 3 | 본문 없음 | `ExchangeRateApiException` | `Exchange rate API returned an empty response` | X |
| 4 | `status` 가 `error`(대소문자 무시) — HTTP 200 포함 | `ExchangeRateApiException` | `Exchange rate API error: code={code 또는 unknown}` | X |
| 5 | `symbol` 이 null 이거나 `"$base/$quote"` 와 다름(대소문자 무시) | `ExchangeRateApiException` | `Unexpected exchange rate symbol (expected {base}/{quote})` | X |
| 6 | `rate` 가 null | `ExchangeRateApiException` | `No rate in response` | X |
| 7 | `rate ≤ 0` | `ExchangeRateApiException` | `Non-positive rate in response` | X |
| 8 | `timestamp` 가 null 이거나 ≤ 0 | `ExchangeRateApiException` | `No observation timestamp in response` | X |
| 9 | 그 외 | `ExchangeRateSnapshot(base, quote, rate, Instant.ofEpochSecond(timestamp), Exchange.FX_PROVIDER)` | — | — |

- 연결 오류(`WebClientRequestException`)·timeout(`TimeoutException`, cause 포함)은 기존처럼 재시도 대상.
- HTTP 오류 본문은 **읽어서 버린다**(연결 재사용을 위해 소비하되 메시지에 넣지 않는다). 기존 `body.take(256)` 를 메시지에 붙이던 동작을 없앤다 — 오류 본문이 무엇을
  되비출지 보장할 수 없다. 4번 행도 `message` 가 아니라 `code` 만 싣는다. 진단은 HTTP status·`code`(401 인증, 429 한도, 400/404 심볼) 로 충분하다.
- 5번의 기대값만 메시지에 싣고 받은 symbol 은 싣지 않는다(제공자 제어 문자열).
- 예외 타입(전문):

```kotlin
open class ExchangeRateApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

open class ExchangeRateHttpException(val status: HttpStatusCode) :
    ExchangeRateApiException("Exchange rate HTTP ${status.value()}")

class RetryableExchangeRateException(status: HttpStatusCode) : ExchangeRateHttpException(status)
```

- `rate` 의 상한·timestamp 의 미래 여부는 검사하지 않는다. 미래 timestamp 는 소비자(`TrackingFacade` 양방향 판정)가 이미 막는다. `[ASSUMPTION]` A4.
- 메트릭(이름·tag 무변경, provider 값만): `fx.fetch{provider=TWELVE_DATA, outcome=success|http|connection|timeout|contract|other}`, `fx.fetch.retry{provider}`,
  `fx.fetch.latency{provider}`. outcome 분류 규칙은 기존 그대로.

### 4.5 비밀값 비노출 — `ExchangeRateClientProperties` (전문)

```kotlin
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
    // init 검증 6개는 기존 그대로
    fun isRetryable(status: Int): Boolean
    fun overallDeadline(): Duration   // 식 기존 그대로: (connect+read)×(retries+1) + maxBackoff×retries + 5s

    override fun toString(): String =
        "ExchangeRateClientProperties(endpoint=$endpoint, apiKey=****, base=$base, quote=$quote, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, maxRetries=$maxRetries, " +
            "initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, retryableStatuses=$retryableStatuses)"
}
```

- `toString()` 노출 프로퍼티(전문): `endpoint, base, quote, connectTimeout, readTimeout, maxRetries, initialBackoff, maxBackoff, retryableStatuses` + 고정 문자열 `apiKey=****`.
  `apiKey` 값은 노출하지 않는다. data class 자동 `toString()` 을 쓰지 않는 유일한 이유가 이것이다.
- key 가 나갈 수 있는 경로와 차단:

| 경로 | 차단 |
|---|---|
| 요청 URL(→ `WebClientRequestException` 메시지, 접근 로그) | 헤더 인증 — URL 에 key 없음 |
| HTTP 오류 본문 되비침 | 본문을 예외·로그에 넣지 않음(§4.4) |
| `ExchangeRateClientProperties.toString()` | override(위) |
| 로그 | 아래 §4.6 키만 |

### 4.6 로그 (키 전문)

| 위치 | 레벨 | 담는 키 | 담지 않는 값 |
|---|---|---|---|
| `ExchangeRateClient` 성공 | DEBUG | base, quote, rate, observedAt | apiKey, 헤더, 응답 객체 |
| `ExchangeRateClient` 재시도 | WARN | 회차(n/max), 실패 예외의 클래스 단순 이름과 메시지(§4.4 메시지 또는 연결/timeout 메시지) | apiKey, 헤더, 응답 본문, 제공자 `message` |
| `ExchangeRateClient` 최종 실패 | ERROR | base, quote, cause(스택 포함) | 같음 |
| `FxIngestionJob` 성공 | INFO | base, quote, rate, observedAt | 같음 |
| `FxIngestionJob` 실패 | ERROR | exception(기존) | 같음 |

`FxIngestionJob` 성공 로그는 현재 `Fetched exchange rate - USD/KRW: {rate}` 로 쌍이 하드코딩돼 있다. `observedAt` 을 더하면 운영자가 로그만으로 "값이 분 단위로 갱신되는가 / 주말에
고정됐는가"를 볼 수 있다(T4 검증에 쓴다). 로깅 문구 자체는 구현 재량.

## 5. 크레딧 예산과 timeout

### 5.1 하루 호출 수 (최악)

Twelve Data Basic: 하루 800 크레딧, 분당 8. `/exchange_rate` 1회 = 1 크레딧(심볼 1개). **실패 요청도 크레딧을 쓴다고 가정한다**(최악). `[ASSUMPTION]` A1.

| 항목 | 회차 |
|---|---|
| fixed-rate 5분 × 24시간 (기동 직후 첫 tick 포함) | 289 |
| startup 실행(기동 5초 뒤, fixed-rate 와 별개) | 1 |
| 합계 회차 R | 290 |

| `max-retries` r | 회차당 최대 호출 r+1 | 최악 호출 R×(r+1) | 800 이내 |
|---|---|---|---|
| 3 (기존) | 4 | 1160 | X |
| 2 | 3 | 870 | X |
| **1 (채택)** | 2 | **580** | O — 여유 220 |
| 0 | 1 | 290 | O — 일시 오류 1회에 회차 실패 |

- 재기동 1회당 추가 최대 호출: startup 실행 1 + 리셋된 fixed-rate 첫 tick 1 = 2회차 × 2 = 4. 여유 220 이면 하루 55회 재기동까지 안전.
- 분당 8: 최다 구간은 기동 직후(첫 tick 과 5초 뒤 startup 실행이 각각 재시도까지) 4회.
- **한 key 를 쓰는 batch 프로세스는 하나**여야 한다. 로컬 운영 batch 와 다른 배포가 같은 key 를 쓰면 위 예산이 두 배가 된다. runbook 에 적는다. `[ASSUMPTION]` A2.
- 주기를 운영자가 줄이면 예산을 넘는다: 일일 최악 호출 ≈ (86400 / fixedRate초 + 2) × (r+1). runbook 에 식과 "fixed-rate 4분 미만 금지(r=1 기준)"를 적는다.
- 429 를 재시도하지 않는 이유: (a) 분당 한도면 250ms~2s 뒤 재시도는 같은 분 안이라 또 429 이고 크레딧만 쓴다. (b) 일일 한도 소진이면 UTC 자정까지 회복되지 않는다.
  둘 다 다음 회차(5분 뒤)가 올바른 재시도다. 지속되면 30분 뒤 `STALE_FX` 로 owner 에게 드러난다.
- F2(후속): 기동 시 fixed-rate 첫 tick 과 startup 실행이 5초 간격으로 겹친다(기존 동작). 30분 주기에선 의미가 있었지만 5분 주기에선 중복이다. 이번엔 예산에 포함만 하고
  제거는 후속.

### 5.2 timeout 합

| 항목 | 값 |
|---|---|
| connect / read | 2s / 3s |
| 시도 수 (r=1) | 2 |
| backoff (initial 250ms, max 2s, 1회) | ≤ 2s |
| `overallDeadline()` = (2+3)×2 + 2×1 + 5 | **17s** |
| `lock:fx` execution timeout / lease | 30s / 35s |

17s < 30s < 35s — `block(overallDeadline)` 이 Job timeout 보다 먼저 끝나고, lock 은 timeout 뒤에도 남는다. 기본값의 `overallDeadline() == 17s` 를 단위 테스트가 잰다.
`JobConfig` 는 apps:batch 에 있어 infra 테스트가 직접 볼 수 없으므로 30s 와의 관계는 이 표와 테스트 이름에 남긴다.

## 6. 설정·env

- **env 이름 유지**: `EXCHANGE_RATE_API_KEY`, `EXCHANGE_RATE_ENDPOINT`, `EXCHANGE_RATE_BASE/QUOTE`, `EXCHANGE_RATE_CONNECT_TIMEOUT/READ_TIMEOUT`, `EXCHANGE_RATE_MAX_RETRIES`,
  `EXCHANGE_RATE_INITIAL_BACKOFF/MAX_BACKOFF`, `EXCHANGE_RATE_FIXED_RATE`, `EXCHANGE_RATE_STARTUP_DELAY`. prefix `exchange-rate.*` 는 이미 제공자 중립이다. 운영자는
  `EXCHANGE_RATE_API_KEY` 값만 Twelve Data key 로 바꾼다. `docker/{batch,app}-compose.yml`·`docker/deploy.sh`·`docker/deploy-contract-test.sh` 무변경.
- yml `exchange-rate` 블록 변경(그 외 줄 무변경):

```yaml
exchange-rate:
  endpoint: ${EXCHANGE_RATE_ENDPOINT:https://api.twelvedata.com}
  max-retries: ${EXCHANGE_RATE_MAX_RETRIES:1}
```

- yml scheduling: `fixed-rate: ${EXCHANGE_RATE_FIXED_RATE:5m}`.
- 운영 전환 시 확인(runbook): host env 또는 `~/.config/premium-spread/account-pnl.env` 에 `EXCHANGE_RATE_ENDPOINT`(구 제공자 주소)·`EXCHANGE_RATE_FIXED_RATE`·
  `EXCHANGE_RATE_MAX_RETRIES` override 가 있으면 지운다. `EXCHANGE_RATE_FIXED_RATE=30m` 이 남아 있으면 §3.3 검증으로 기동이 실패한다.
- `application-local.yml`·test profile 의 dummy key 는 그대로.

## 7. DB·cache 순서와 Flyway

- 순서 무변경: `FxIngestionJob` 이 MySQL 저장 성공 뒤 Redis 를 쓴다(DB-first). DB 실패면 캐시를 쓰지 않는다(기존 E2E 가 잰다).
- **Flyway 불필요.** `exchange_rate(base_currency, quote_currency, rate, observed_at, created_at)` 와 unique key `(base_currency, quote_currency, observed_at)` 그대로.
  5분 주기에서 평일엔 회차마다 새 `observed_at`(분 단위) 행이 생기고 하루 최대 약 290행이다. 주말엔 같은 timestamp 가 오면 `ON DUPLICATE KEY UPDATE rate` 로 한 행에 수렴한다
  (기존과 같은 동작). 보존 정책은 범위 밖 — 연 약 7만 행 수준이라 즉시 문제 아님. `[ASSUMPTION]` A5.
- `JdbcExchangeRateWriteRepository.save` KDoc 의 "(30분 단위)" 는 "수집 회차마다, 같은 observed_at 은 한 행으로 수렴"으로 고친다(주석만).

### 7.1 주말 동작

forex 는 주말에 닫힌다. Twelve Data 가 금요일 마감 값과 그 timestamp 를 계속 돌려준다고 가정한다(`[ASSUMPTION]` A6 — T4 관찰 기회가 있으면 확인).

| 소비자 | 주말 동작 |
|---|---|
| `FxIngestionJob` | 매 회차 성공. DB 는 같은 행 갱신(수렴), 캐시는 5분마다 TTL 갱신 |
| `AccountPnlMonitorJob` | 수집 성공 시각 기준이라 신선 → 계산. 메일의 "환율 관측" 은 금요일 시각 |
| `PremiumRealtimeJob` | 금요일 마감 환율로 계산(판정 없음) |
| api `TrackingFacade.recordFromMarket` 등 | `fxObservedAt` 이 35분을 넘어 `STALE_PREMIUM_SNAPSHOT` — §8 |

## 8. api `TrackingFacade.FX_MAX_AGE` — 범위 밖 판단

현재 `FX_MAX_AGE = 35분` 은 premium snapshot 의 `fxObservedAt`(제공자 timestamp)에 대한 양방향 판정이다.

- 지금까지: 일일 제공자라 `fxObservedAt` 이 매일 00:00:01 UTC 였다 → 하루의 대부분 시간 실패했을 것이다.
- 이 변경 후: 평일엔 timestamp 가 호출 분과 같아 최대 약 5분 + 지연 → 통과. 주말엔 실패.

**이번 범위에 넣지 않는다.** 근거:
1. 이 변경은 이 판정을 **나쁘게 만들지 않고** 평일에는 처음으로 통과하게 만든다. 회귀가 아니다.
2. 주말 판정을 고치려면 "수집 성공 시각" 기준으로 바꿔야 하는데, 그 값은 batch 프로세스의 `JobLastSuccessReadPort`(runbook 상 프로세스 메모리 기반)에 있어 api 가 읽을 경로가 없다.
   새 port·저장소 계약이 필요한 별도 설계다.
3. 주말 archive 를 막는 것이 맞는지(금요일 환율로 확정해도 되는지)는 사용자 판단이 필요한 비즈니스 질문이다.

이번엔 동작 무변경, KDoc 주석의 "batch 의 exchange-rate fixed-rate 가 30m 이므로" 를 현재 사실(주기 5분, 35분은 유지, 주말엔 제공자 timestamp 가 고정돼 실패하며 후속 F1)로만 고친다.

**후속 후보 F1**: Tracking 의 FX freshness 를 주말에도 의미 있게 — (a) 수집 성공 시각을 api 가 읽을 수 있게 하는 방식, 또는 (b) 주말엔 마감 timestamp 를 허용하는 방식 중 선택.

## 9. 문서 갱신 대상

| 파일 | 변경 |
|---|---|
| `docs/runbooks/redis-contract.md:18` | `fx:{base}:{quote}` TTL `31분` → `40분`, 표 아래 한 줄: "허용 공백(`FxIngestionFreshness.MAX_INGESTION_GAP` 30분) + 5분 이상 — `FxCacheTtlContractTest` 가 강제" |
| `docs/runbooks/deployment.md:44` | `EXCHANGE_RATE_API_KEY` 를 "Twelve Data API key" 로 명시. 신규 `### 환율 제공자 (Twelve Data)` — 발급, 헤더 인증(key 가 URL 에 없음), 주기 5분·max-retries 1·429 비재시도, 크레딧 식과 4분 하한, key 당 batch 하나, 전환 시 지울 override(§6), 주말 동작(§7.1) |
| `docs/runbooks/account-pnl-alert.md:413, :418, :430` | 환율 수집 간격 `2시간` → `30분`, `(30분 주기)` → `(5분 주기)` |
| `.env.example:5-7`, `deploy/.env.prd.example:42-44` | 주석 `ExchangeRate API` / `exchangerate-api.com 에서 발급` → `Twelve Data` / `twelvedata.com 에서 발급(Basic 무료)`. 키 이름·placeholder 그대로 |
| `.ai/architecture/ARCHITECTURE_DESIGN.md:76` | `FX API (30분)` → `Twelve Data FX API (5분)` |
| `.ai/PROJECT_STATUS.md:65` | `USD/KRW 30분 수집` → `USD/KRW 5분 수집(Twelve Data)` |
| `.ai/context/project-overview.md:74` | `30분마다` → `5분마다(Twelve Data)` |
| `README.md:78` | `30분 · ExchangeRate API` → `5분 · Twelve Data` |
| `infrastructure/batch/.../ExchangeRateResponse.kt` KDoc | exchangerate-api 예시 → Twelve Data 예시(§4.3, 가짜 값) |
| `JdbcExchangeRateWriteRepository` KDoc, `TrackingFacade` KDoc | 주석만 (§7, §8) |
| `docs/runbooks/auth-security.md`·`durable-notification-delivery.md` | 무변경 |

## 10. 설계 완결성 체크리스트 (orchestrator ④)

| 항목 | 결정 |
|---|---|
| Facade 이름·주입 | **N/A** — REST 변경 없음. 진입은 기존 `ExchangeRateScheduler` → `FxIngestionJob` |
| Domain port · 구현 모듈 | **새 port 없음.** 기존 `ExchangeRateProvider` ← `infrastructure:batch` `ExchangeRateClient`(구현만 교체). 새 Domain 타입 `FxIngestionFreshness` 는 순수 `object` |
| MarketPair identity | FX 는 `(base, quote)` 가 identity. 응답 `symbol` 이 요청 `BASE/QUOTE` 와 다르면 거절(§4.4-5) — 다른 pair 값으로 보정하지 않는다. Redis key `fx:{base}:{quote}`·DB row 무변경 |
| 시간·범위 | `observedAt = Instant.ofEpochSecond(timestamp)`(UTC). 허용 공백 판정은 주입 `Clock` 의 `Instant` 끼리, 경계 포함(정확히 30분 = 신선). 집계 버킷 무관 |
| DB·cache 순서 | DB-first 무변경. TTL 불변식 §3.2 |
| Flyway | **불필요**(§7). Task 0 이 `ls … \| sort -V \| tail -1` 로 최신 번호만 기록 |
| batch lock·lease·timeout·JobResult | 무변경 `lock:fx` 35s/30s. `overallDeadline` 17s(§5.2). `FxIngestionJob` 분기 무변경: 성공 → `JobResult.Success`, 모든 예외 → `JobResult.Failure(exception)`(DB·캐시 미기록). `AccountPnlMonitorJob` 의 `STALE_FX` 분기는 문턱만 30분 |
| 알림 event identity | 변경 없음 |
| 공개 endpoint | 변경 없음 |
| Redis | TTL 만 변경(`RedisTtl.FX` 40분) + runbook 동시 갱신. key·payload 무변경 |

## 11. 테스트 전략

외부 호출 금지: 실제 Twelve Data 를 부르지 않는다. MockWebServer(기존 `ExchangeRateClientTest`·`BatchTestConfig` 방식)와 가짜 key(`test-key`, 누출 검사용 `fake-key-SENTINEL-93`)만 쓴다.
환율 값은 실제와 거리가 먼 가짜(`1234.5678`), timestamp 는 고정 epoch 초(`1767225600` = `2026-01-01T00:00:00Z`).

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| Domain | `isStale` 경계: 30분 신선, 30분+1ms stale, 0, 미래(음수) 신선, 상수 = 30분 | `domain` unit, 고정 `Instant` |
| TTL 계약 | `RedisTtl.FX == 40분`, `RedisTtl.FX − MAX_INGESTION_GAP ≥ 5분` | `infrastructure:common` unit |
| adapter | 요청 경로·symbol·헤더·URL 에 key 없음, 매핑, 거절 규칙 9행, 재시도(5xx 2회·429/401 1회·timeout 상한), 기본값(endpoint host·r=1·retryable=500..599·deadline 17s), 누출(예외 메시지·출력·`toString`·URL) | `infrastructure:batch` unit (MockWebServer, `OutputCaptureExtension` — `DiscordWebhookSenderAdapterTest` 선례) |
| application | `AccountPnlMonitorJob` 허용 공백 30분 경계·첫 실행 기준 | `apps:batch` unit (기존 `AccountPnlMonitorJobTest` 허용 예외 수정) |
| 설정 | `ExchangeRate` 기본 5분·15분 허용·15분+1ms 거부, yml 기본값 문자열(주기 5m·endpoint host·max-retries 1, 구 제공자 host 부재) | `apps:batch` unit |
| E2E | Twelve Data JSON → DB `observed_at` = timestamp, 캐시 rate·timestamp, 캐시 TTL ∈ (35분, 40분], 요청 헤더, 최종 실패 시 DB·캐시 없음 | `apps:batch` integration (MySQL/Redis Testcontainers + MockWebServer) |
| 경계 | 모듈·import·테스트 격리(외부 URL) | root `architectureTest` |
| 사람 | 실제 key 로 5분 간격 수집과 분 단위 `observed_at` | T4 owner |

## 12. 가정과 결정

### [ASSUMPTION]

| # | 가정 | 확인 방법·시점 |
|---|---|---|
| A1 | 실패 요청(4xx·5xx)도 크레딧을 쓴다 — 최악 예산 계산의 전제 | 보수적 가정. T4 에서 Twelve Data 대시보드 사용량이 시간당 약 12 인지 관찰 |
| A2 | Twelve Data key 를 쓰는 batch 프로세스는 하나다 | runbook 에 명시, owner 확인 |
| A3 | 주기 하한(크레딧)은 코드 검증 없이 runbook 식으로만 지킨다 | Gate 1 에서 사람 확인 |
| A4 | `rate` 상한·timestamp 미래 여부는 adapter 가 검사하지 않는다 | 소비자 판정이 이미 있음 |
| A5 | `exchange_rate` 행 증가(평일 하루 약 290행)는 보존 정책 없이 감당 가능 | 연 약 7만 행 |
| A6 | 주말에 Twelve Data 가 금요일 마감 값과 그 timestamp 를 돌려준다 | T4 관찰 기회가 있으면 기록, 없으면 "미관찰" |
| A7 | 허용 공백 2시간 → 30분은 사용자 합의(5분 주기·#93 해결) 안의 설계 판단이다. owner 체감 변화는 "수집이 30분 넘게 멈추면 중단 메일" 이며, 실효 한도는 이미 TTL 31분이었다(§3.1) | Gate 1 에서 사람 확인 |
| A8 | `TrackingFacade` 는 주석만 고치고 동작은 그대로 둔다(§8) | Gate 1 에서 사람 확인 |

### [DECISION_NEEDED]

없음. (A3·A7·A8 은 사용자 합의 범위 안의 판단이지만 Gate 1 에서 함께 확인받는다.)

### 후속 후보

| # | 내용 |
|---|---|
| F1 | api Tracking FX freshness 의 주말 대응(§8) |
| F2 | 기동 시 fixed-rate 첫 tick 과 startup 실행 중복 제거(§5.1) |
| F3 | `PremiumRealtimeJob` 의 FX freshness 판정(현재 캐시 TTL 에만 의존) |

## 13. 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| 초안 | — | — |
