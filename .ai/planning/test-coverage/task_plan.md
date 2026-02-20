# Task Plan: Batch 모듈 테스트 커버리지 보강

## Goal

현재 E2E 테스트(20건)를 기반으로, 연결되는 하위 레이어(CacheService, Repository)에 대한
단위 테스트 및 통합 테스트를 보강하여 **충분한 테스트 계층 구조**를 달성한다.

"충분한"의 기준:
- E2E 테스트가 전체 흐름 검증
- 각 컴포넌트는 자기 책임 범위를 독립적으로 검증하는 테스트 보유
- 집계 로직 등 핵심 비즈니스 로직은 단위 테스트로 빠른 피드백 가능
- 과한 중복 테스트는 제거하지 않고 가이드 수준에서 명시

## 결론 요약 (분석 결과)

### 완전 미테스트 컴포넌트 (GAP)
| 컴포넌트 | 유형 | 우선순위 | 이유 |
|---------|------|---------|------|
| `FxCacheService` | 단위 | HIGH | Redis hash 직렬화/역직렬화 + TTL |
| `TickerCacheService` | 단위 | HIGH | 집계 로직 (aggregateSecondsData/aggregateData) |
| `PremiumCacheService` | 단위 | HIGH | 집계+서머리 로직 복잡 |
| `PositionCacheService` | 단위 | LOW | 단순 로직이라 E2E 간접 커버 충분 |
| `ExchangeRateRepository` | 통합 | HIGH | ON DUPLICATE KEY UPDATE, findLatest |
| `PremiumAggregationRepository` | 통합 | HIGH | findLatest* 신규 메서드 |
| `TickerAggregationRepository` | 통합 | HIGH | findLatest* 신규 메서드 |
| `PremiumSnapshotRepository` | 통합 | MEDIUM | 단순 INSERT이나 미테스트 |

### 부분 취약 테스트 (PARTIAL GAP) — 기존 테스트 파일 엣지케이스 누락
| 테스트 파일 | 누락 엣지케이스 | 우선순위 |
|-----------|--------------|---------|
| `PremiumRealtimeJobTest` | **fx rate = 0 → Skipped(invalid_price)** (코드 로직 있음) | **CRITICAL** |
| `FxIngestionJobTest` | repository.save() 예외 → Failure (캐시는 이미 저장됨) | HIGH |
| `TickerIngestionJobTest` | tickerCacheService.saveAll() 예외 → Failure | HIGH |
| `BithumbClientTest` | MAX_RETRIES(3번) 초과 → 최종 exception | MEDIUM |
| `BinanceClientTest` | MAX_RETRIES 초과 → 최종 exception | MEDIUM |
| `BithumbClientTest` | status=0000이지만 data=null → exception | MEDIUM |
| `FxIngestionJobTest` | fxCacheService.save() 예외 → Failure | MEDIUM |
| `PremiumRealtimeJobTest` | premiumCacheService.save() 예외 → Failure | MEDIUM |
| `JobExecutorTest` | Failure 시 last-run 갱신 안 함 | LOW |
| `AggregationJobTest` | Clock.fixed()로 windowStart/windowEnd 정확한 값 검증 없음 | LOW |

### 허용 가능한 중복 (ACCEPTABLE REDUNDANCY)
- 각 Scheduler 단위 테스트의 `호출 시 jobExecutor execute를 1회 호출한다` - thin entrypoint 계약 확인이므로 허용
- Scheduler 단위 테스트 + E2E 테스트 공존 - 계층이 다르므로 허용

## Phases

### Phase 0-A: 기존 단위 테스트 엣지케이스 보강 (기존 파일 수정)
- Status: complete ✅
- Tasks:
  - [x] `PremiumRealtimeJobTest`: fx rate = 0 → Skipped(invalid_price) (**CRITICAL**)
  - [x] `FxIngestionJobTest`: repository.save() 예외 → Failure + 캐시는 저장됨 검증
  - [x] `FxIngestionJobTest`: fxCacheService.save() 예외 → Failure
  - [x] `TickerIngestionJobTest`: tickerCacheService.saveAll() 예외 → Failure
  - [x] `PremiumRealtimeJobTest`: premiumCacheService.save() 예외 → Failure
  - [x] `BithumbClientTest`: MAX_RETRIES(3회) 모두 실패 → 최종 exception
  - [x] `BithumbClientTest`: status=0000이지만 data=null → BithumbApiException
  - [x] `BinanceClientTest`: MAX_RETRIES(3회) 모두 실패 → 최종 exception
  - [x] `JobExecutorTest`: Failure 시 last-run 갱신 안 함

### Phase 0-B: 기존 E2E 테스트 엣지케이스 보강 (기존 파일 수정)
- Status: complete ✅
- 원칙: "인프라 상태(Redis key 없음 / DB null) 검증"이 목적. 단위 테스트가 이미 로직을 커버해도 E2E에서 실제 저장 없음을 확인하는 것은 별도 가치
- Tasks:
  - [x] `PremiumAggregationE2ETest`: 소스 없음 → aggregateMinute/Hour DB null (HIGH)
  - [x] `PremiumAggregationE2ETest`: updateSummaryCache 소스 전체 없음 → summary 키 없음 (HIGH)
  - [x] `TickerAggregationE2ETest`: 소스 없음 → aggregateMinute DB null (HIGH)
  - [x] `TickerAggregationE2ETest`: bithumb만 없음 → bithumb null, binance만 저장 (HIGH)
  - [x] `ExchangeRateSchedulerE2ETest`: API 최종 실패 → Redis key 없음, DB row 없음 (MEDIUM)
  - [x] `PremiumSchedulerE2ETest`: ticker 캐시 없음 → premium:btc key 없음 (MEDIUM)
  - `TickerSchedulerE2ETest`: 거래소 API 실패 → 저장 없음 (LOW, 단위 테스트 커버 충분 → 스킵)
  - `PremiumSchedulerE2ETest`: position 없음 → history 없음 (LOW, 단위 테스트 커버 충분 → 스킵)

### Phase 1: CacheService 단위 테스트 (MockK 기반)
- Status: complete ✅
- Tasks:
  - [x] `FxCacheServiceTest`: save/get/getUsdKrw + 파싱 실패 케이스
  - [x] `TickerCacheServiceTest`: save/get/aggregateSecondsData/aggregateData 집계 로직
  - [x] `PremiumCacheServiceTest`: save/get/aggregateSecondsData/aggregateData/calculateSummaryFromSeconds/calculateSummary
  - PositionCacheService 스킵 (E2E 간접 커버 충분)
- 방식: `StringRedisTemplate` MockK mock, `TypedTuple<String>` mockk로 ZSet 데이터 시뮬레이션

### Phase 2: Repository 통합 테스트 (TestContainers 기반)
- Status: complete ✅
- Tasks:
  - [x] `ExchangeRateRepositoryTest`: save(INSERT + ON DUPLICATE KEY UPDATE) + findLatest
  - [x] `PremiumAggregationRepositoryTest`: saveMinute/Hour/Day + findLatestMinute/Hour/Day
  - [x] `TickerAggregationRepositoryTest`: saveMinute/Hour/Day + findLatestMinute/Hour/Day
  - PremiumSnapshotRepository 스킵 (미사용 + 스키마 없음)
- 방식: `BatchIntegrationTestBase` 상속 (TestContainers MySQL + Redis 자동 설정)

### Phase 3: AggregationJobTest 보강 (선택)
- Status: pending
- Tasks:
  - [ ] `Clock.fixed()`로 windowStart = now.minus(1분).truncatedTo(분), windowEnd 정확 검증
- 방식: AggregationJob 생성 시 Clock.fixed() 주입

## Key Decisions

| 결정 | 근거 |
|------|------|
| CacheService는 단위 테스트 (MockK) | 집계 로직이 순수 Kotlin이므로 Mock Redis로 빠르게 검증 가능 |
| Repository는 통합 테스트 (TestContainers) | SQL 쿼리/ON DUPLICATE KEY UPDATE 동작은 실제 DB 필요 |
| E2E가 있어도 하위 레이어 테스트 추가 | 레이어 독립 검증, 빠른 피드백, regression 방어 |
| PositionCacheService 단위 테스트 스킵 | hasOpenPosition/getOpenPositionCount 2개 메서드, E2E 커버 충분 |
