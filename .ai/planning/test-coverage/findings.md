# Findings: Batch 모듈 테스트 커버리지 분석

## 전체 파일 현황

### 프로덕션 코드 (30개 파일)
```
application/
  common/     JobConfig, JobExecutor, JobResult
  job/
    aggregation/ AggregationJob<T>
    fx/          FxIngestionJob
    premium/     PremiumRealtimeJob
    ticker/      TickerIngestionJob
cache/           FxCacheService, TickerCacheService, PremiumCacheService, PositionCacheService
calculator/      PremiumCalculator
client/
  binance/       BinanceClient, BinanceResponse
  bithumb/       BithumbClient, BithumbResponse
  exchangerate/  ExchangeRateClient, ExchangeRateResponse
  config/        WebClientConfig
repository/      ExchangeRateRepository, PremiumAggregationRepository, PremiumSnapshotRepository, TickerAggregationRepository
scheduler/       ExchangeRateScheduler, PremiumAggregationScheduler, PremiumScheduler, TickerAggregationScheduler, TickerScheduler
```

### 테스트 현황 (21개 파일, 총 75건)

#### 단위 테스트 (55건)
| 파일 | 테스트 수 | 대상 컴포넌트 |
|------|---------|-------------|
| JobExecutorTest | 6 | JobExecutor |
| AggregationJobTest | 6 | AggregationJob |
| FxIngestionJobTest | 2 | FxIngestionJob |
| PremiumRealtimeJobTest | 8 | PremiumRealtimeJob |
| TickerIngestionJobTest | 3 | TickerIngestionJob |
| PremiumCalculatorTest | 11 | PremiumCalculator |
| BinanceClientTest | 4 | BinanceClient (MockWebServer) |
| BithumbClientTest | 4 | BithumbClient (MockWebServer) |
| ExchangeRateClientTest | 5 | ExchangeRateClient (MockWebServer) |
| ExchangeRateSchedulerTest | 4 | ExchangeRateScheduler |
| PremiumSchedulerTest | 3 | PremiumScheduler |
| TickerSchedulerTest | 3 | TickerScheduler |
| PremiumAggregationSchedulerTest | 10 | PremiumAggregationScheduler |
| TickerAggregationSchedulerTest | 9 | TickerAggregationScheduler |

#### E2E 통합 테스트 (20건)
| 파일 | 테스트 수 | 커버 흐름 |
|------|---------|---------|
| ExchangeRateSchedulerE2ETest | 3 | ExchangeRate 캐시 저장, DB 저장, startup 동일성 |
| TickerSchedulerE2ETest | 3 | Ticker Hash/ZSet 저장 |
| PremiumSchedulerE2ETest | 3 | Premium 캐시/ZSet/히스토리 저장 |
| PremiumAggregationE2ETest | 6 | Premium minute/hour/day + summary |
| TickerAggregationE2ETest | 5 | Ticker minute/hour/day |

## GAP 분석

### GAP-1: FxCacheService (단위 테스트 없음)

**역할:** Redis Hash로 환율 데이터 저장/조회
**로직 복잡도:** 낮음 (직렬화/역직렬화)
**E2E 커버 여부:** ExchangeRateSchedulerE2ETest가 캐시 저장을 간접 검증 ✅
**필요 테스트:**
- `save()`: key format `fx:usd:krw`, hash field (base/quote/rate/timestamp), TTL 설정 검증
- `get()`: hash → FxRateData 파싱 검증
- `get()`: hash 비어있으면 null 반환
- `get()`: 파싱 실패 케이스 (broken value)
- `getUsdKrw()`: FxRateData.rate만 반환

**단위 테스트 방식:**
```kotlin
val redisTemplate: StringRedisTemplate = mockk()
val valueOps: ValueOperations<String, String> = mockk(relaxed = true)
val hashOps: HashOperations<String, String, String> = mockk()
every { redisTemplate.opsForHash<String, String>() } returns hashOps
```

---

### GAP-2: TickerCacheService (단위 테스트 없음)

**역할:** Redis Hash 티커 저장/조회 + ZSet 초당/집계 데이터 관리
**로직 복잡도:** 중간 (집계 계산 포함)
**E2E 커버 여부:** TickerSchedulerE2ETest, TickerAggregationE2ETest가 간접 검증 ✅
**필요 테스트:**

핵심 집계 로직 (단위 테스트 필수):
```
aggregateSecondsData():
  - 여러 가격 데이터 → high/low/open/close/avg/count 계산
  - 빈 데이터 → null 반환
  - 평균 계산 정확도 (RoundingMode.HALF_UP)

aggregateData():
  - 여러 집계 데이터 → 재집계 (고-저-오픈-클로즈-가중평균)
  - 빈 데이터 → null 반환
  - 가중 평균 = Σ(avg_i * count_i) / Σcount_i
```

부수 로직:
- `save()`: hash 구조 + TTL
- `get()`: hash → TickerData 파싱
- `saveToSeconds()`: ZSet add + cutoff 삭제

---

### GAP-3: PremiumCacheService (단위 테스트 없음)

**역할:** Redis Hash/ZSet 프리미엄 데이터 + 집계 + 서머리 계산
**로직 복잡도:** 높음 (집계 + 서머리 2종)
**E2E 커버 여부:** PremiumSchedulerE2ETest, PremiumAggregationE2ETest가 간접 검증 ✅

**필요 테스트 (핵심):**

```
aggregateSecondsData():
  - 초당 rate 데이터 → PremiumAggregation(high/low/open/close/avg/count)

aggregateData():
  - 분 집계 → 시 집계 재집계 (가중 평균)

calculateSummaryFromSeconds():
  - 초당 데이터 → PremiumSummary(high/low/current)
  - current = 마지막 rate
  - currentTimestamp = 마지막 timestamp

calculateSummary():
  - 집계 데이터 → PremiumSummary
  - current = lastAgg.close
```

`save/get/saveHistory/saveSummary/getSummary` 는 직렬화 검증 위주

---

### GAP-4: PositionCacheService (단위 테스트 없음)

**역할:** 포지션 존재 여부 flag Redis 저장/조회
**로직 복잡도:** 매우 낮음
**E2E 커버 여부:** PremiumSchedulerE2ETest에서 seed 후 간접 검증 ✅
**결론:** 스킵 가능. 추가 시 매우 낮은 가치

---

### GAP-5: ExchangeRateRepository (통합 테스트 없음)

**역할:** exchange_rate 테이블 INSERT + SELECT
**E2E 커버 여부:** ExchangeRateSchedulerE2ETest에서 DB 저장 검증 ✅
**필요 테스트 (Repository 단독 통합 테스트):**
- `save()`: INSERT 검증
- `save()` 중복 호출: `ON DUPLICATE KEY UPDATE rate = VALUES(rate)` 동작 검증
  - 같은 base/quote/observed_at → rate 업데이트
- `findLatest()`: 최신 레코드 조회
- `findLatest()`: 레코드 없으면 null 반환

---

### GAP-6: PremiumAggregationRepository (통합 테스트 없음)

**역할:** premium_minute/hour/day 테이블 INSERT + SELECT
**E2E 커버 여부:** PremiumAggregationE2ETest가 DB 저장 검증 ✅
**필요 테스트:**
- `saveMinute/Hour/Day()`: INSERT 검증 (OHLCAV 값)
- `findLatestMinute/Hour/Day()`: 최신 레코드 조회 (신규 추가된 메서드)
- `findLatest*()` 여러 레코드 중 최신 반환 검증

---

### GAP-7: TickerAggregationRepository (통합 테스트 없음)

**역할:** ticker_minute/hour/day 테이블 INSERT + SELECT
**E2E 커버 여부:** TickerAggregationE2ETest가 DB 저장 검증 ✅
**필요 테스트:** GAP-6와 동일 구조

---

### GAP-8: PremiumSnapshotRepository (통합 테스트 없음)

**역할:** premium_snapshot 테이블 INSERT
**E2E 커버 여부:** PremiumSchedulerE2ETest에서 간접 커버 여부 확인 필요
  → E2E에서 premium_snapshot 테이블 검증 없음 ❌
**필요 테스트:**
- `save()`: INSERT 후 SELECT로 검증

---

## PARTIAL GAP 분석

### PARTIAL-1: AggregationJobTest - Clock 제어 취약

현재 테스트:
```kotlin
assertThat(capturedTo!!).isAfter(capturedFrom!!)  // 약한 검증
```

권장 테스트:
```kotlin
val fixedNow = Instant.parse("2024-01-01T00:01:30Z")
val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
val job = AggregationJob(reader = ..., writer = ..., clock = clock)

// windowStart = fixedNow.minus(1분).truncatedTo(분) = 2024-01-01T00:00:00Z
// windowEnd   = windowStart + 1분 = 2024-01-01T00:01:00Z
assertThat(capturedFrom).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
assertThat(capturedTo).isEqualTo(Instant.parse("2024-01-01T00:01:00Z"))
```

---

## E2E 엣지케이스 분석

### 공통 원칙
- 단위 테스트가 로직(return value)을 검증 → E2E는 **인프라 상태** 검증이 추가 가치
- "저장 없음(null/key 없음)"을 실제 Redis/DB로 확인하는 것은 E2E에서만 의미 있음
- 단순 위임 결과(Skipped 반환)는 단위 테스트로 충분 → E2E 중복 불필요

### 기존 E2E 테스트 엣지케이스 현황

#### ExchangeRateSchedulerE2ETest
| 케이스 | 현황 | 권장 |
|-------|------|------|
| API 성공 → 캐시 저장 | ✅ | - |
| API 성공 → DB 저장 | ✅ | - |
| startup 동일성 | ✅ | - |
| API 최종 실패 → Redis key 없음, DB row 없음 | ❌ | MEDIUM 추가 |

#### TickerSchedulerE2ETest
| 케이스 | 현황 | 권장 |
|-------|------|------|
| 성공 → Hash 저장 | ✅ | - |
| 성공 → bithumb ZSet 저장 | ✅ | - |
| 성공 → binance ZSet 저장 | ✅ | - |
| 거래소 API 실패 → 저장 없음 | ❌ | LOW (단위 테스트 충분, 스킵) |

#### PremiumSchedulerE2ETest
| 케이스 | 현황 | 권장 |
|-------|------|------|
| 선행 데이터 있음 → 캐시 저장 | ✅ | - |
| 선행 데이터 있음 → ZSet 저장 | ✅ | - |
| position open → history 저장 | ✅ | - |
| **ticker 캐시 없음 → premium:btc key 없음** | ❌ | MEDIUM 추가 |
| position 없음 → history 없음 | ❌ | LOW (단위 테스트 충분, 스킵) |

#### PremiumAggregationE2ETest
| 케이스 | 현황 | 권장 |
|-------|------|------|
| aggregateMinute 성공 → 캐시+DB 저장 | ✅ | - |
| aggregateHour 성공 → 캐시+DB 저장 | ✅ | - |
| aggregateDay 성공 → DB 저장 | ✅ | - |
| updateSummaryCache 성공 → 4구간 저장 | ✅ | - |
| **초당 데이터 없음 → aggregateMinute DB null** | ❌ | HIGH 추가 |
| **분 데이터 없음 → aggregateHour DB null** | ❌ | HIGH 추가 |
| **updateSummaryCache 소스 전체 없음 → summary 키 없음** | ❌ | HIGH 추가 |
| updateSummaryCache 일부 구간만 소스 없음 → 선택적 저장 | ❌ | LOW (단위 테스트 resilience ✅, 스킵) |

#### TickerAggregationE2ETest
| 케이스 | 현황 | 권장 |
|-------|------|------|
| aggregateMinute 성공 → 캐시+DB 저장 | ✅ | - |
| aggregateHour 성공 → 캐시+DB 저장 | ✅ | - |
| aggregateDay 성공 → DB 저장 | ✅ | - |
| **초당 데이터 없음 → aggregateMinute DB null** | ❌ | HIGH 추가 |
| **bithumb 없음 → bithumb null, binance만 저장** | ❌ | HIGH 추가 (partial success 검증) |
| binance 없음 → bithumb만 저장 | ❌ | MEDIUM (bithumb 없음과 대칭, 2건 중 1건 선택) |

---

## 중복/과한 테스트 분석

### 허용 가능한 중복 (제거 불필요)

1. **Scheduler "1회 호출" 테스트** (ExchangeRateSchedulerTest, PremiumSchedulerTest, TickerSchedulerTest)
   - 각 메서드마다 `jobExecutor.execute(any(), any())가 1회 호출된다` 존재
   - Scheduler가 thin entrypoint임을 명시적으로 검증
   - 제거하면 오히려 위임 계약이 불명확해짐 → 유지

2. **Scheduler 단위 + E2E 공존**
   - 단위: 위임 계약 + job 로직 독립 검증
   - E2E: 실제 인프라(Redis, DB)와의 통합 검증
   - 역할이 다름 → 공존 정당

### 실제 불필요한 중복 없음

---

## 구현 순서 권장

```
1순위 (높은 가치):
  PremiumCacheService 단위 테스트  - 집계/서머리 로직이 복잡
  TickerCacheService 단위 테스트   - 집계 로직 검증
  PremiumAggregationRepository 통합 테스트 - findLatest* 신규 메서드
  TickerAggregationRepository 통합 테스트  - findLatest* 신규 메서드

2순위 (중간 가치):
  ExchangeRateRepository 통합 테스트 - ON DUPLICATE KEY UPDATE 동작
  PremiumSnapshotRepository 통합 테스트 - E2E 커버 없음
  FxCacheService 단위 테스트           - 직렬화 검증

3순위 (낮은 가치):
  AggregationJobTest Clock 보강 - 코드 품질 향상
  PositionCacheService 단위 테스트 - 단순 로직, 낮은 가치
```
