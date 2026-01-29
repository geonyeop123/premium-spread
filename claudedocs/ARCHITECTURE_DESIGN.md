# Premium Spread System Architecture Design

## 1. 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    EXTERNAL APIs                                      │
├─────────────────────┬─────────────────────┬─────────────────────────────────────────┤
│   Bithumb API       │   Binance Futures   │        Exchange Rate API                │
│   (BTC/KRW Spot)    │   (BTCUSDT Perp)    │        (USD/KRW)                        │
│   [1초 갱신]         │   [1초 갱신]         │        [10분 갱신]                       │
│   15 req/s          │   20 req/s          │        1000 req/day                     │
└────────┬────────────┴──────────┬──────────┴─────────────────┬───────────────────────┘
         │                       │                             │
         │                       │                             │
         ▼                       ▼                             ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              BATCH SERVER (apps:batch)                               │
│  ┌─────────────────────────────────────────────────────────────────────────────┐    │
│  │                         Scheduler Layer                                      │    │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐   │    │
│  │  │  TickerScheduler │  │ ExchangeScheduler│  │   PremiumCalculator     │   │    │
│  │  │  (1초 @Scheduled)│  │ (10분 @Scheduled)│  │   (1초, Position 연동)   │   │    │
│  │  └────────┬─────────┘  └────────┬─────────┘  └────────────┬─────────────┘   │    │
│  │           │                     │                         │                  │    │
│  │           ▼                     ▼                         ▼                  │    │
│  │  ┌─────────────────────────────────────────────────────────────────────┐    │    │
│  │  │                    External API Clients                              │    │    │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────────────┐ │    │    │
│  │  │  │BithumbClient│  │BinanceClient│  │  ExchangeRateClient          │ │    │    │
│  │  │  └─────────────┘  └─────────────┘  └──────────────────────────────┘ │    │    │
│  │  └─────────────────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────────────────┘    │
│                                        │                                             │
│                          ┌─────────────┴─────────────┐                              │
│                          ▼                           ▼                              │
│             ┌─────────────────────┐     ┌─────────────────────┐                     │
│             │   Redisson Lock     │     │   Redis Cache Write │                     │
│             │   (분산 락)          │     │                     │                     │
│             └─────────────────────┘     └─────────────────────┘                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    REDIS CLUSTER                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐    │
│  │  Ticker Cache          │  FX Cache           │  Premium Cache               │    │
│  │  ticker:bithumb:btc    │  fx:usd:krw         │  premium:btc                 │    │
│  │  ticker:binance:btc    │  TTL: 15분          │  TTL: 5초                    │    │
│  │  TTL: 5초              │                     │                              │    │
│  ├─────────────────────────────────────────────────────────────────────────────┤    │
│  │  Position Cache        │  Lock Keys          │  Metrics                     │    │
│  │  position:open:count   │  lock:ticker:*      │  batch:last_run:*           │    │
│  │  TTL: 30초             │  TTL: 3초           │  TTL: 5분                    │    │
│  └─────────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              API SERVER (apps:api)                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────┐    │
│  │                         Interfaces Layer                                     │    │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐ │    │
│  │  │ PremiumController│  │ TickerController │  │   PositionController      │ │    │
│  │  │ GET /premium     │  │ GET /tickers     │  │   GET/POST /positions     │ │    │
│  │  └────────┬─────────┘  └────────┬─────────┘  └────────────┬───────────────┘ │    │
│  │           │                     │                         │                  │    │
│  │           ▼                     ▼                         ▼                  │    │
│  │  ┌─────────────────────────────────────────────────────────────────────┐    │    │
│  │  │                      Application Layer (Facade)                      │    │    │
│  │  │  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────────┐│    │    │
│  │  │  │PremiumFacade │  │TickerFacade  │  │   PositionFacade           ││    │    │
│  │  │  └──────────────┘  └──────────────┘  └─────────────────────────────┘│    │    │
│  │  └─────────────────────────────────────────────────────────────────────┘    │    │
│  │           │                                                                  │    │
│  │           ▼                                                                  │    │
│  │  ┌─────────────────────────────────────────────────────────────────────┐    │    │
│  │  │                      Infrastructure Layer                            │    │    │
│  │  │  ┌──────────────────────┐  ┌────────────────────────────────────┐   │    │    │
│  │  │  │ Redis Cache Reader   │  │   JPA Repository (Fallback)       │   │    │    │
│  │  │  │ (Primary Read Path)  │  │   (Cache Miss 시)                  │   │    │    │
│  │  │  └──────────────────────┘  └────────────────────────────────────┘   │    │    │
│  │  └─────────────────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    DATABASE (MySQL)                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────┐    │
│  │  ticker (이력)  │  premium (이력)  │  position  │  api_key (암호화)          │    │
│  └─────────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              OBSERVABILITY STACK                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────────────────────────┐    │
│  │   Prometheus    │  │    Grafana      │  │   AlertManager / PagerDuty       │    │
│  │   (Metrics)     │  │   (Dashboard)   │  │   (Alerting)                     │    │
│  └─────────────────┘  └─────────────────┘  └───────────────────────────────────┘    │
│  ┌─────────────────┐  ┌─────────────────┐                                           │
│  │   ELK Stack     │  │   Sentry        │                                           │
│  │   (Logging)     │  │   (Error Track) │                                           │
│  └─────────────────┘  └─────────────────┘                                           │
└─────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              SECRETS MANAGEMENT                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐    │
│  │                    AWS Secrets Manager / HashiCorp Vault                     │    │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐ │    │
│  │  │ Exchange API Keys│  │   DB Credentials │  │   Redis Password          │ │    │
│  │  │ (Read-Only)      │  │                  │  │                           │ │    │
│  │  └──────────────────┘  └──────────────────┘  └────────────────────────────┘ │    │
│  └─────────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Redis 키/TTL 설계

### 2.1 키 네이밍 컨벤션

```
{domain}:{sub-domain}:{identifier}:{optional-qualifier}
```

### 2.2 키 설계 상세

| Key Pattern | 예시 | 설명 | TTL | 갱신 주기 |
|-------------|------|------|-----|----------|
| `ticker:{exchange}:{symbol}` | `ticker:bithumb:btc` | 거래소별 티커 | 5초 | 1초 |
| `ticker:{exchange}:{symbol}` | `ticker:binance:btc` | 바이낸스 선물 티커 | 5초 | 1초 |
| `fx:{base}:{quote}` | `fx:usd:krw` | 환율 정보 | 15분 | 10분 |
| `premium:{symbol}` | `premium:btc` | 프리미엄율 | 5초 | 1초 |
| `premium:{symbol}:history` | `premium:btc:history` | 최근 N개 프리미엄 (List) | 1시간 | 1초 |
| `position:open:count` | `position:open:count` | 열린 포지션 수 | 30초 | 변경 시 |
| `position:open:exists` | `position:open:exists` | 열린 포지션 존재 여부 | 30초 | 변경 시 |
| `lock:ticker:{exchange}` | `lock:ticker:bithumb` | 티커 갱신 분산 락 | 3초 | - |
| `lock:fx` | `lock:fx` | 환율 갱신 분산 락 | 15초 | - |
| `lock:premium` | `lock:premium` | 프리미엄 계산 분산 락 | 3초 | - |
| `batch:last_run:{job}` | `batch:last_run:ticker` | 마지막 배치 실행 시각 | 5분 | 배치 실행 시 |
| `batch:health:{server}` | `batch:health:batch-1` | 배치 서버 헬스 | 30초 | 10초 |

### 2.3 데이터 구조

```kotlin
// Ticker Cache (Hash)
HSET ticker:bithumb:btc
    "price" "129555000"
    "volume" "1234.5"
    "timestamp" "1706500000000"
    "observed_at" "2026-01-29T10:00:00Z"

// Premium Cache (Hash)
HSET premium:btc
    "rate" "1.28"
    "korea_price" "129555000"
    "foreign_price" "89277.1"
    "fx_rate" "1432.6"
    "observed_at" "2026-01-29T10:00:00Z"

// Premium History (Sorted Set) - 차트용
ZADD premium:btc:history {timestamp} "{rate}:{korea_price}:{foreign_price}"

// Position Open Check (String)
SET position:open:exists "true" EX 30
SET position:open:count "3" EX 30
```

### 2.4 Open Position 기반 캐싱 정책

```
┌─────────────────────────────────────────────────────────────────┐
│                    Position-Aware Caching Policy                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Always Cache - 시장 모니터링용]                                 │
│  ├── ticker:*          기본 가격 데이터                          │
│  ├── fx:*              환율 데이터                               │
│  └── premium:*         기본 프리미엄율                            │
│                                                                  │
│  [Conditional Cache - Open Position 있을 때만]                   │
│  ├── premium:*:history 프리미엄 히스토리 (차트)                   │
│  ├── pnl:*             포지션별 PnL 계산 결과                     │
│  └── position:detail:* 포지션 상세 계산 (수수료 포함)              │
│                                                                  │
│  [Policy Logic]                                                  │
│  if (position:open:exists == "true") {                          │
│      calculate_and_cache_premium_history()                       │
│      calculate_and_cache_pnl()                                   │
│  }                                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 배치 스케줄/락 전략

### 3.1 스케줄링 설계

```kotlin
// TickerScheduler.kt
@Component
class TickerScheduler(
    private val bithumbClient: BithumbClient,
    private val binanceClient: BinanceClient,
    private val redissonClient: RedissonClient,
    private val tickerCacheService: TickerCacheService,
) {

    @Scheduled(fixedRate = 1000) // 1초
    fun fetchTickers() {
        val lock = redissonClient.getLock("lock:ticker:all")
        if (lock.tryLock(0, 2, TimeUnit.SECONDS)) {
            try {
                // 병렬로 두 거래소 호출
                val bithumb = async { bithumbClient.getBtcTicker() }
                val binance = async { binanceClient.getBtcFutures() }

                tickerCacheService.saveAll(bithumb.await(), binance.await())
                updateLastRunMetric("ticker")
            } finally {
                lock.unlock()
            }
        }
    }
}

// ExchangeRateScheduler.kt
@Component
class ExchangeRateScheduler(...) {

    @Scheduled(fixedRate = 600_000) // 10분
    fun fetchExchangeRate() {
        val lock = redissonClient.getLock("lock:fx")
        if (lock.tryLock(0, 30, TimeUnit.SECONDS)) {
            try {
                val fxRate = exchangeRateClient.getUsdKrw()
                fxCacheService.save(fxRate)
                updateLastRunMetric("fx")
            } finally {
                lock.unlock()
            }
        }
    }
}

// PremiumScheduler.kt
@Component
class PremiumScheduler(...) {

    @Scheduled(fixedRate = 1000) // 1초
    fun calculatePremium() {
        val lock = redissonClient.getLock("lock:premium")
        if (lock.tryLock(0, 2, TimeUnit.SECONDS)) {
            try {
                val bithumb = tickerCacheService.get("bithumb", "btc")
                val binance = tickerCacheService.get("binance", "btc")
                val fx = fxCacheService.get("usd", "krw")

                val premium = premiumCalculator.calculate(bithumb, binance, fx)
                premiumCacheService.save(premium)

                // Open Position이 있으면 추가 캐싱
                if (positionCacheService.hasOpenPosition()) {
                    premiumCacheService.saveHistory(premium)
                    pnlCalculator.calculateAndCache()
                }

                updateLastRunMetric("premium")
            } finally {
                lock.unlock()
            }
        }
    }
}
```

### 3.2 분산 락 전략

```
┌─────────────────────────────────────────────────────────────────┐
│                    Distributed Lock Strategy                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Redisson Lock Configuration]                                   │
│                                                                  │
│  Lock Key         │ Wait Time │ Lease Time │ Purpose            │
│  ─────────────────┼───────────┼────────────┼────────────────────│
│  lock:ticker:all  │ 0초       │ 2초        │ 티커 갱신 독점      │
│  lock:fx          │ 0초       │ 30초       │ 환율 갱신 독점      │
│  lock:premium     │ 0초       │ 2초        │ 프리미엄 계산 독점   │
│                                                                  │
│  [Lock Behavior]                                                 │
│  - tryLock(0, leaseTime): 즉시 획득 시도, 실패 시 skip          │
│  - 다른 인스턴스가 이미 실행 중이면 이번 주기 skip               │
│  - Lease Time > 작업 예상 시간 (여유 확보)                       │
│                                                                  │
│  [Failover]                                                      │
│  - 락 획득 서버 다운 시 Lease Time 후 자동 해제                  │
│  - 다음 주기에 다른 서버가 자연스럽게 인계                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 장애 복구 및 모니터링

```kotlin
// BatchHealthChecker.kt
@Component
class BatchHealthChecker(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
) {

    @Scheduled(fixedRate = 10_000) // 10초
    fun checkBatchHealth() {
        val jobs = listOf("ticker", "fx", "premium")

        jobs.forEach { job ->
            val lastRun = redisTemplate.opsForValue()
                .get("batch:last_run:$job")
                ?.toLongOrNull() ?: 0L

            val elapsed = System.currentTimeMillis() - lastRun
            val threshold = when (job) {
                "ticker" -> 5_000L   // 5초 (1초 갱신 * 5회)
                "fx" -> 900_000L     // 15분 (10분 갱신 * 1.5)
                else -> 5_000L
            }

            if (elapsed > threshold) {
                meterRegistry.counter("batch.stale", "job", job).increment()
                alertService.sendAlert("Batch job $job is stale: ${elapsed}ms")
            }
        }
    }
}
```

### 3.4 배치 서버 다중화

```
┌─────────────────────────────────────────────────────────────────┐
│                    Batch Server Deployment                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Active-Passive with Lock]                                      │
│                                                                  │
│  Batch Server 1 (Primary)     Batch Server 2 (Standby)          │
│  ┌─────────────────────┐      ┌─────────────────────┐           │
│  │ Ticker: RUNNING     │      │ Ticker: WAITING     │           │
│  │ FX:     RUNNING     │      │ FX:     WAITING     │           │
│  │ Premium: RUNNING    │      │ Premium: WAITING    │           │
│  └─────────────────────┘      └─────────────────────┘           │
│            │                            │                        │
│            ▼                            ▼                        │
│         Acquires Lock               tryLock fails               │
│            │                     (skips this cycle)              │
│            ▼                                                     │
│      Executes job                                                │
│                                                                  │
│  [Failover Scenario]                                             │
│  - Server 1 다운 → Lock 자동 만료 (2~30초)                       │
│  - Server 2가 다음 주기에 Lock 획득                              │
│  - 최대 지연: fixedRate + leaseTime                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 모듈 구조 (Gradle Multi-Module)

### 4.1 모듈 구조도

```
premium-spread/
├── settings.gradle.kts
├── build.gradle.kts
│
├── apps/                             # 실행 가능한 애플리케이션
│   ├── api/                          # API 서버 (Spring Boot)
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── io/premiumspread/
│   │           ├── PremiumSpreadApiApplication.kt
│   │           ├── interfaces/       # Controller, DTO
│   │           │   └── api/
│   │           │       ├── premium/
│   │           │       ├── ticker/
│   │           │       └── position/
│   │           ├── application/      # Facade, UseCase
│   │           │   ├── premium/
│   │           │   ├── ticker/
│   │           │   └── position/
│   │           ├── domain/           # Entity, Service, Repository interface
│   │           │   ├── premium/
│   │           │   ├── ticker/
│   │           │   └── position/
│   │           └── infrastructure/   # Repository 구현체 (도메인 단위)
│   │               ├── premium/
│   │               │   ├── PremiumJpaRepository.kt
│   │               │   └── PremiumRepositoryImpl.kt
│   │               ├── ticker/
│   │               │   ├── TickerJpaRepository.kt
│   │               │   └── TickerRepositoryImpl.kt
│   │               └── position/
│   │                   ├── PositionJpaRepository.kt
│   │                   └── PositionRepositoryImpl.kt
│   │
│   └── batch/                        # Batch 서버 (Spring Boot)
│       ├── build.gradle.kts
│       └── src/main/kotlin/
│           └── io/premiumspread/
│               ├── PremiumSpreadBatchApplication.kt
│               ├── scheduler/        # @Scheduled 작업
│               │   ├── TickerScheduler.kt
│               │   ├── ExchangeRateScheduler.kt
│               │   └── PremiumScheduler.kt
│               ├── client/           # External API Clients
│               │   ├── bithumb/
│               │   ├── binance/
│               │   └── exchangerate/
│               └── infrastructure/   # Cache 구현체
│                   ├── cache/
│                   │   ├── TickerCacheService.kt
│                   │   ├── PremiumCacheService.kt
│                   │   └── FxCacheService.kt
│                   └── lock/
│                       └── DistributedLockManager.kt
│
├── modules/                          # 기술 인프라 모듈
│   ├── jpa/                          # JPA 공통 (기존)
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── io/premiumspread/
│   │           └── jpa/
│   │               ├── BaseEntity.kt
│   │               └── JpaConfig.kt
│   │
│   └── redis/                        # Redis + Redisson (신규)
│       ├── build.gradle.kts
│       └── src/main/kotlin/
│           └── io/premiumspread/
│               └── redis/
│                   ├── RedisConfig.kt
│                   ├── RedissonConfig.kt
│                   └── RedisKeyGenerator.kt
│
└── supports/                         # Cross-cutting concerns (신규)
    ├── logging/                      # 로깅 설정
    │   ├── build.gradle.kts
    │   └── src/main/kotlin/
    │       └── io/premiumspread/
    │           └── logging/
    │               ├── LoggingConfig.kt
    │               ├── LogMaskingFilter.kt
    │               └── StructuredLogger.kt
    │
    └── monitoring/                   # 메트릭/알람
        ├── build.gradle.kts
        └── src/main/kotlin/
            └── io/premiumspread/
                └── monitoring/
                    ├── MetricsConfig.kt
                    ├── AlertService.kt
                    └── HealthIndicators.kt
```

### 4.2 의존성 방향

```
┌─────────────────────────────────────────────────────────────────┐
│                    Dependency Direction                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    Application Layer                       │  │
│  │  ┌─────────┐            ┌─────────┐                       │  │
│  │  │apps:api │            │apps:batch│                      │  │
│  │  │         │            │         │                       │  │
│  │  │ domain  │            │scheduler│                       │  │
│  │  │ infra   │            │ client  │                       │  │
│  │  │ facade  │            │ cache   │                       │  │
│  │  └────┬────┘            └────┬────┘                       │  │
│  │       │                      │                             │  │
│  │       │     depends on       │                             │  │
│  │       ▼                      ▼                             │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                   │
│  ┌───────────────────────────┼───────────────────────────────┐  │
│  │                    modules/                                │  │
│  │                           │                                │  │
│  │  ┌─────────────────┐  ┌───┴───────────────┐               │  │
│  │  │ modules:jpa     │  │  modules:redis    │               │  │
│  │  │ - BaseEntity    │  │  - RedisConfig    │               │  │
│  │  │ - JpaConfig     │  │  - RedissonConfig │               │  │
│  │  └─────────────────┘  └───────────────────┘               │  │
│  │                                                            │  │
│  └────────────────────────────────────────────────────────────┘  │
│                              │                                   │
│  ┌───────────────────────────┼───────────────────────────────┐  │
│  │                    supports/                               │  │
│  │                           │                                │  │
│  │  ┌─────────────────┐  ┌───┴───────────────┐               │  │
│  │  │supports:logging │  │supports:monitoring│               │  │
│  │  │ - LogMasking    │  │ - MetricsConfig   │               │  │
│  │  │ - StructuredLog │  │ - AlertService    │               │  │
│  │  └─────────────────┘  └───────────────────┘               │  │
│  │                                                            │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  [원칙]                                                          │
│  1. apps는 modules와 supports를 의존                             │
│  2. modules와 supports는 서로 의존하지 않음                        │
│  3. domain 코드는 apps 내부에 위치 (패키지 분리)                    │
│  4. infrastructure는 도메인 단위로 패키지 구성 (persistence 제거)   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 Gradle 의존성 설정

```kotlin
// settings.gradle.kts
rootProject.name = "premium-spread"

include(
    // apps
    ":apps:api",
    ":apps:batch",
    // modules
    ":modules:jpa",
    ":modules:redis",
    // supports
    ":supports:logging",
    ":supports:monitoring",
)

// modules/jpa/build.gradle.kts (기존)
dependencies {
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}

// modules/redis/build.gradle.kts (신규)
dependencies {
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.redisson:redisson-spring-boot-starter:3.24.3")
}

// supports/logging/build.gradle.kts (신규)
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
}

// supports/monitoring/build.gradle.kts (신규)
dependencies {
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-registry-prometheus")
}

// apps/api/build.gradle.kts
dependencies {
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}

// apps/batch/build.gradle.kts (신규)
dependencies {
    implementation(project(":modules:redis"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")  // External API 호출용
    // No JPA - batch는 Redis만 사용
}
```

---

## 5. 보안 설계

### 5.1 Secret 관리 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                    Secret Management Architecture                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Option A: AWS Secrets Manager] (권장)                          │
│                                                                  │
│  AWS Secrets Manager                                             │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  premium-spread/api-keys                                 │    │
│  │  ├── bithumb_api_key                                    │    │
│  │  ├── bithumb_secret_key                                 │    │
│  │  ├── binance_api_key                                    │    │
│  │  ├── binance_secret_key                                 │    │
│  │  └── exchange_rate_api_key                              │    │
│  └─────────────────────────────────────────────────────────┘    │
│                           │                                      │
│                           ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Spring Cloud AWS Secrets Manager                        │    │
│  │  @Value("${bithumb.api-key}") // 자동 주입              │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  [Option B: HashiCorp Vault]                                     │
│  - Self-hosted 환경에 적합                                       │
│  - 더 세밀한 정책 제어 가능                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 API Key 권한 최소화

```kotlin
// API Key 권한 설정 가이드

/**
 * Bithumb API
 * - 필요 권한: 시세 조회 (Public API, 키 불필요)
 * - Read-Only 계정으로 충분
 */

/**
 * Binance Futures API
 * - 필요 권한: Read Market Data
 * - 거래 권한(Trade) 비활성화 필수
 * - IP 화이트리스트 설정 권장
 */

/**
 * Exchange Rate API
 * - 무료 티어: 일 1000회 호출
 * - API Key만 사용 (Secret 없음)
 */
```

### 5.3 로깅 마스킹 (supports/logging)

```kotlin
// LogMaskingFilter.kt
@Component
class LogMaskingFilter : Filter {

    private val sensitivePatterns = listOf(
        Regex("(api[_-]?key)([\":]\\s*[\"']?)([^\"'\\s,}]+)", RegexOption.IGNORE_CASE),
        Regex("(secret[_-]?key)([\":]\\s*[\"']?)([^\"'\\s,}]+)", RegexOption.IGNORE_CASE),
        Regex("(password)([\":]\\s*[\"']?)([^\"'\\s,}]+)", RegexOption.IGNORE_CASE),
        Regex("(authorization)[\":]\\s*[\"']?([^\"'\\s,}]+)", RegexOption.IGNORE_CASE),
    )

    fun mask(message: String): String {
        var masked = message
        sensitivePatterns.forEach { pattern ->
            masked = pattern.replace(masked) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}***MASKED***"
            }
        }
        return masked
    }
}

// application.yml
logging:
  pattern:
    console: "%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}"
```

### 5.4 Key Rotation 전략

```
┌─────────────────────────────────────────────────────────────────┐
│                    Key Rotation Strategy                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [AWS Secrets Manager Auto-Rotation]                             │
│                                                                  │
│  1. Rotation Lambda 설정 (90일 주기)                             │
│  2. 새 키 생성 → Secret 업데이트 → 앱 재시작 없이 적용           │
│                                                                  │
│  Timeline:                                                       │
│  Day 0       Day 85      Day 90        Day 91                   │
│  │           │           │             │                        │
│  │  Current  │  Alert    │  Rotate     │  Old Key Expire       │
│  │  Key      │  Sent     │  New Key    │                        │
│  ▼           ▼           ▼             ▼                        │
│  ───────────────────────────────────────────────────            │
│                                                                  │
│  [Application Side]                                              │
│  - Spring Cloud AWS: 자동 리프레시 (RefreshScope)                │
│  - 또는 ConfigMap 변경 시 Pod 재시작 (K8s)                       │
│                                                                  │
│  [Manual Rotation (거래소 API)]                                  │
│  - 거래소에서 새 API Key 발급                                    │
│  - Secrets Manager 수동 업데이트                                 │
│  - 앱 배포 또는 Refresh 트리거                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. 관측성 및 운영 (supports/monitoring)

### 6.1 메트릭 설계 (Micrometer + Prometheus)

```kotlin
// MetricsConfig.kt
@Configuration
class MetricsConfig(private val meterRegistry: MeterRegistry) {

    // Ticker 수집 메트릭
    val tickerFetchCounter = Counter.builder("ticker.fetch.total")
        .tag("exchange", "unknown")
        .register(meterRegistry)

    val tickerFetchLatency = Timer.builder("ticker.fetch.latency")
        .tag("exchange", "unknown")
        .register(meterRegistry)

    // Premium 계산 메트릭
    val premiumGauge = Gauge.builder("premium.rate.current") { currentPremiumRate }
        .register(meterRegistry)

    val premiumCalculationCounter = Counter.builder("premium.calculation.total")
        .register(meterRegistry)

    // 캐시 히트율
    val cacheHitCounter = Counter.builder("cache.hit.total")
        .tag("cache", "unknown")
        .register(meterRegistry)

    val cacheMissCounter = Counter.builder("cache.miss.total")
        .tag("cache", "unknown")
        .register(meterRegistry)

    // 외부 API 호출
    val externalApiLatency = Timer.builder("external.api.latency")
        .tag("api", "unknown")
        .register(meterRegistry)

    val externalApiErrorCounter = Counter.builder("external.api.error.total")
        .tag("api", "unknown")
        .tag("error_type", "unknown")
        .register(meterRegistry)

    // 배치 헬스
    val batchLastRunGauge = Gauge.builder("batch.last_run.epoch_seconds") { lastRunEpoch }
        .tag("job", "unknown")
        .register(meterRegistry)
}
```

### 6.2 주요 메트릭 목록

| 메트릭 | 타입 | 태그 | 용도 |
|--------|------|------|------|
| `ticker.fetch.total` | Counter | exchange | 티커 수집 횟수 |
| `ticker.fetch.latency` | Timer | exchange | 티커 수집 지연시간 |
| `ticker.fetch.error.total` | Counter | exchange, error_type | 티커 수집 에러 |
| `premium.rate.current` | Gauge | symbol | 현재 프리미엄율 |
| `premium.calculation.total` | Counter | - | 프리미엄 계산 횟수 |
| `fx.rate.current` | Gauge | pair | 현재 환율 |
| `cache.hit.total` | Counter | cache | 캐시 히트 |
| `cache.miss.total` | Counter | cache | 캐시 미스 |
| `external.api.latency` | Timer | api | 외부 API 응답시간 |
| `external.api.error.total` | Counter | api, error_type | 외부 API 에러 |
| `batch.last_run.epoch_seconds` | Gauge | job | 마지막 배치 실행 |
| `position.open.count` | Gauge | - | 열린 포지션 수 |

### 6.3 알람 정책

```yaml
# prometheus-rules.yml
groups:
  - name: premium-spread-alerts
    rules:
      # 배치 작업 지연
      - alert: BatchJobStale
        expr: time() - batch_last_run_epoch_seconds{job="ticker"} > 10
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Ticker batch job is stale"
          description: "Last run was {{ $value }} seconds ago"

      # 외부 API 에러율
      - alert: ExternalApiHighErrorRate
        expr: rate(external_api_error_total[5m]) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High error rate on {{ $labels.api }}"

      # 캐시 미스율
      - alert: HighCacheMissRate
        expr: rate(cache_miss_total[5m]) / (rate(cache_hit_total[5m]) + rate(cache_miss_total[5m])) > 0.3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Cache miss rate is high: {{ $value | humanizePercentage }}"

      # 프리미엄 급변
      - alert: PremiumRateSpike
        expr: abs(delta(premium_rate_current[5m])) > 2
        for: 1m
        labels:
          severity: info
        annotations:
          summary: "Premium rate changed significantly: {{ $value }}%"

      # Redis 연결 실패
      - alert: RedisConnectionFailed
        expr: up{job="redis"} == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Redis connection lost"
```

### 6.4 로깅 전략 (supports/logging)

```kotlin
// 로깅 레벨 및 포맷
// application-prod.yml
logging:
  level:
    root: WARN
    io.premiumspread: INFO
    io.premiumspread.scheduler: DEBUG  # 배치 디버깅용
    io.premiumspread.client: DEBUG     # 외부 API 디버깅용
  file:
    name: /var/log/premium-spread/app.log
  logback:
    rollingpolicy:
      max-file-size: 100MB
      max-history: 30

// 구조화된 로깅
@Component
class StructuredLogger {
    fun logTickerFetch(exchange: String, price: BigDecimal, latencyMs: Long) {
        logger.info(
            """{"event":"ticker_fetch","exchange":"$exchange","price":"$price","latency_ms":$latencyMs}"""
        )
    }

    fun logPremiumCalculation(rate: BigDecimal, korea: BigDecimal, foreign: BigDecimal) {
        logger.info(
            """{"event":"premium_calc","rate":"$rate","korea_price":"$korea","foreign_price":"$foreign"}"""
        )
    }
}
```

---

## 7. 테스트 전략

### 7.1 테스트 피라미드

```
                    ┌─────────────────┐
                    │   E2E Tests     │  (5%)
                    │  Testcontainers │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              │      Integration Tests      │  (25%)
              │   Redis, External API Mock  │
              └──────────────┬──────────────┘
                             │
       ┌─────────────────────┴─────────────────────┐
       │              Unit Tests                    │  (70%)
       │   Domain Logic, Services, Calculators     │
       └───────────────────────────────────────────┘
```

### 7.2 단위 테스트 (TDD)

```kotlin
// PremiumCalculatorTest.kt
class PremiumCalculatorTest {

    @Test
    fun `프리미엄 계산 - 정상 케이스`() {
        // Given
        val koreaPrice = BigDecimal("129555000")    // 1억 2955만원
        val foreignPrice = BigDecimal("89277.1")    // $89,277.1
        val fxRate = BigDecimal("1432.6")           // 1달러 = 1432.6원

        // When
        val premium = premiumCalculator.calculate(koreaPrice, foreignPrice, fxRate)

        // Then
        assertThat(premium.rate).isEqualTo(BigDecimal("1.28"))
    }

    @Test
    fun `프리미엄 계산 - 마이너스 프리미엄`() {
        // Given: 해외가 더 비싼 경우
        val koreaPrice = BigDecimal("125000000")
        val foreignPrice = BigDecimal("90000")
        val fxRate = BigDecimal("1432.6")

        // When
        val premium = premiumCalculator.calculate(koreaPrice, foreignPrice, fxRate)

        // Then
        assertThat(premium.rate).isLessThan(BigDecimal.ZERO)
    }
}

// TickerCacheServiceTest.kt
class TickerCacheServiceTest {

    @MockK
    lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `캐시 저장 및 조회`() {
        // Given
        val ticker = Ticker(exchange = "bithumb", symbol = "btc", price = BigDecimal("129555000"))

        // When
        tickerCacheService.save(ticker)
        val cached = tickerCacheService.get("bithumb", "btc")

        // Then
        verify { redisTemplate.opsForHash<String, String>().putAll(any(), any()) }
        assertThat(cached.price).isEqualTo(ticker.price)
    }
}
```

### 7.3 통합 테스트

```kotlin
// TickerSchedulerIntegrationTest.kt
@SpringBootTest
@Testcontainers
class TickerSchedulerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    }

    @MockBean
    lateinit var bithumbClient: BithumbClient

    @MockBean
    lateinit var binanceClient: BinanceClient

    @Test
    fun `스케줄러가 Redis에 티커를 저장한다`() {
        // Given
        every { bithumbClient.getBtcTicker() } returns mockBithumbResponse()
        every { binanceClient.getBtcFutures() } returns mockBinanceResponse()

        // When
        tickerScheduler.fetchTickers()

        // Then
        val cached = redisTemplate.opsForHash<String, String>().entries("ticker:bithumb:btc")
        assertThat(cached["price"]).isEqualTo("129555000")
    }
}

// ExternalApiClientIntegrationTest.kt
@SpringBootTest
@WireMockTest(httpPort = 8089)
class BithumbClientIntegrationTest {

    @Test
    fun `빗썸 API 호출 성공`() {
        // Given
        stubFor(get("/public/ticker/BTC_KRW")
            .willReturn(okJson("""{"data":{"closing_price":"129555000"}}""")))

        // When
        val ticker = bithumbClient.getBtcTicker()

        // Then
        assertThat(ticker.price).isEqualTo(BigDecimal("129555000"))
    }

    @Test
    fun `빗썸 API Rate Limit 초과 시 재시도`() {
        // Given
        stubFor(get("/public/ticker/BTC_KRW")
            .inScenario("RateLimit")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429))
            .willSetStateTo("Recovered"))

        stubFor(get("/public/ticker/BTC_KRW")
            .inScenario("RateLimit")
            .whenScenarioStateIs("Recovered")
            .willReturn(okJson("""{"data":{"closing_price":"129555000"}}""")))

        // When
        val ticker = bithumbClient.getBtcTicker()

        // Then
        assertThat(ticker.price).isEqualTo(BigDecimal("129555000"))
    }
}
```

### 7.4 E2E 테스트

```kotlin
// PremiumE2ETest.kt
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class PremiumE2ETest {

    companion object {
        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8").withDatabaseName("premium")
    }

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `프리미엄 조회 E2E`() {
        // Given: 캐시에 데이터 준비
        prepareTestData()

        // When
        val response = restTemplate.getForEntity("/api/v1/premium/btc", PremiumResponse::class.java)

        // Then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.rate).isNotNull()
    }
}
```

---

## 8. 리스크/대안 비교표

### 8.1 주요 리스크

| 리스크 | 영향도 | 발생확률 | 대응 방안 |
|--------|--------|----------|-----------|
| 외부 API 장애 (빗썸/바이낸스) | 높음 | 중간 | 캐시 TTL 연장, Fallback 거래소, 알람 |
| Redis 장애 | 높음 | 낮음 | Redis Cluster/Sentinel, DB Fallback |
| 환율 API 장애 | 중간 | 낮음 | 마지막 환율 유지, 수동 입력 지원 |
| Rate Limit 초과 | 중간 | 낮음 | Exponential Backoff, 호출 최적화 |
| 프리미엄 급변 (Flash Crash) | 중간 | 낮음 | 이상치 필터링, 알람 |
| API Key 유출 | 높음 | 낮음 | Read-Only 권한, 키 로테이션 |
| 배치 서버 다운 | 높음 | 낮음 | Active-Passive, Health Check |
| 네트워크 지연 | 낮음 | 중간 | 타임아웃 설정, Circuit Breaker |

### 8.2 기술 선택 대안 비교

#### 8.2.1 메시지 브로커 vs 스케줄러

| 구분 | Spring Scheduler | Kafka/RabbitMQ |
|------|------------------|----------------|
| 복잡도 | 낮음 | 높음 |
| 1초 주기 적합성 | 적합 | 과도함 |
| 분산 처리 | Redisson Lock 필요 | 네이티브 지원 |
| 운영 부담 | 낮음 | 높음 |
| 권장 | O (현재 요구사항) | X (향후 확장 시) |

#### 8.2.2 캐시 솔루션

| 구분 | Redis | Caffeine (Local) | Hazelcast |
|------|-------|------------------|-----------|
| 분산 지원 | O | X | O |
| 지연시간 | ~1ms | ~0.1ms | ~5ms |
| TTL 지원 | O | O | O |
| 분산 락 | Redisson | X | O |
| 운영 복잡도 | 중간 | 낮음 | 높음 |
| 권장 | O | 보조 캐시로 | X |

#### 8.2.3 외부 API 클라이언트

| 구분 | RestTemplate | WebClient | OpenFeign |
|------|--------------|-----------|-----------|
| 비동기 | X | O | X (Reactive 가능) |
| 선언적 | X | X | O |
| Retry/Timeout | 수동 | Reactor | Resilience4j |
| 권장 | X | 배치용 | API용 |

#### 8.2.4 Secret 관리

| 구분 | 환경변수 | AWS Secrets Manager | HashiCorp Vault |
|------|----------|---------------------|-----------------|
| 보안 수준 | 낮음 | 높음 | 높음 |
| 자동 로테이션 | X | O | O |
| 운영 복잡도 | 낮음 | 낮음 | 높음 |
| 비용 | 무료 | 월 ~$0.4/secret | Self-hosted |
| 권장 | 개발용 | O (AWS 환경) | O (On-premise) |

### 8.3 확장 고려사항

```
┌─────────────────────────────────────────────────────────────────┐
│                    Future Scalability                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [Phase 1: 현재 - MVP]                                           │
│  - 단일 코인 (BTC)                                               │
│  - 단일 거래소 페어 (빗썸-바이낸스)                                │
│  - 1초 갱신                                                      │
│                                                                  │
│  [Phase 2: 멀티 코인]                                            │
│  - ETH, SOL 추가                                                 │
│  - Redis Key에 symbol 파라미터화                                 │
│  - 스케줄러 병렬화                                               │
│                                                                  │
│  [Phase 3: 멀티 거래소]                                          │
│  - 업비트, 코인원 추가                                            │
│  - OKX, Bybit 추가                                               │
│  - 거래소별 Client 인터페이스 추상화                               │
│                                                                  │
│  [Phase 4: 실시간 스트리밍]                                       │
│  - WebSocket 기반 가격 수신                                       │
│  - 1초 폴링 → 실시간 Push                                        │
│  - Kafka 도입 (고빈도 데이터 처리)                                │
│  - modules/kafka 모듈 추가                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. 구현 우선순위

### Phase 1: 기반 인프라 (1주)
1. 멀티모듈 구조 설정 (modules/redis, supports/logging, supports/monitoring)
2. apps/batch 모듈 생성
3. Redis 설정 및 연결
4. 기본 로깅/모니터링 설정

### Phase 2: 데이터 수집 (1주)
5. 외부 API 클라이언트 구현 (apps/batch/client)
6. 배치 스케줄러 구현 (1초 간격)
7. Redis 캐싱 구현

### Phase 3: API 서버 (1주)
8. 캐시 읽기 서비스
9. REST API 엔드포인트
10. Position 연동 캐싱 정책

### Phase 4: 운영 (1주)
11. 메트릭/알람 설정
12. 보안 설정 (로깅 마스킹)
13. 테스트 커버리지 확보
