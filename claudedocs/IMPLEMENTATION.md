# Premium Spread MVP - Implementation Guide & Progress

> **Project**: 프리미엄 트레이딩 플랫폼
> **Last Updated**: 2026-01-30
> **Branch**: `feature/premium`
> **Status**: Batch 모듈 및 캐시 레이어 구현 완료, 통합 테스트 대기

## Resume Instructions

```bash
# 새 세션에서 시작할 때
cat claudedocs/IMPLEMENTATION.md

# 남은 작업 확인 후
"계속 진행해" 또는 "통합 테스트 진행해"
```

---

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    IMPLEMENTATION PROGRESS                       │
├─────────────────────────────────────────────────────────────────┤
│  Phase 1: Domain         [##########] 100%  ✅ Complete         │
│  Phase 2: Infrastructure [##########] 100%  ✅ Complete         │
│  Phase 3: Application    [##########] 100%  ✅ Complete         │
│  Phase 4: API            [##########] 100%  ✅ Complete         │
│  Phase 5: Batch          [##########] 100%  ✅ Complete (NEW)   │
│  Phase 6: Supports       [##########] 100%  ✅ Complete (NEW)   │
│  Phase 7: Integration    [░░░░░░░░░░]   0%  ⏳ Requires Docker  │
├─────────────────────────────────────────────────────────────────┤
│  Overall: 24/26 tasks (92%) - Batch/Cache 구현 완료             │
└─────────────────────────────────────────────────────────────────┘
```

---

## Task Board

### Phase 1: Domain Layer ✅

| #  | Task            | Status | Files                                                                                 |
|----|-----------------|--------|---------------------------------------------------------------------------------------|
| 1  | Position Entity | ✅      | `Position.kt`, `PositionPnl.kt`, `PositionStatus.kt`                                  |
| 2  | Position Tests  | ✅      | `PositionTest.kt` (9 cases)                                                           |
| 2a | Domain Services | ✅      | `TickerService.kt`, `PremiumService.kt`, `PositionService.kt`                         |
| 2b | Domain Commands | ✅      | `TickerCommand.kt`, `PremiumCommand.kt`, `PositionCommand.kt`                         |
| 2c | Service Tests   | ✅      | `TickerServiceTest.kt` (7), `PremiumServiceTest.kt` (6), `PositionServiceTest.kt` (6) |

### Phase 2: Infrastructure Layer ✅

| # | Task                  | Status | Files                                                                  |
|---|-----------------------|--------|------------------------------------------------------------------------|
| 3 | Repository Interfaces | ✅      | `TickerRepository.kt`, `PremiumRepository.kt`, `PositionRepository.kt` |
| 4 | JPA Implementations   | ✅      | `*JpaRepository.kt`, `*RepositoryImpl.kt`                              |
| 5 | Premium → Entity      | ✅      | `Premium.kt` (converted), `Symbol.kt` (@Embeddable)                    |
| 6 | Flyway Migrations     | ✅      | `V1__ticker.sql`, `V2__premium.sql`, `V3__position.sql`                |
| 7 | Repository Tests      | ✅      | `TickerRepositoryTest.kt`, `PremiumRepositoryTest.kt`, `PositionRepositoryTest.kt` |

### Phase 3: Application Layer ✅

| #  | Task                | Status | Files                                                                                     |
|----|---------------------|--------|-------------------------------------------------------------------------------------------|
| 8  | Application DTOs    | ✅      | `TickerDtos.kt`, `PremiumDtos.kt`, `PositionDtos.kt`                                      |
| 9  | TickerIngestUseCase | ✅      | `TickerIngestFacade.kt` → uses `TickerService`                                            |
| 10 | PremiumUseCase      | ✅      | `PremiumFacade.kt` → uses `TickerService`, `PremiumService`                               |
| 11 | PositionUseCase     | ✅      | `PositionFacade.kt` → uses `PositionService`, `PremiumService`                            |
| 12 | UseCase Tests       | ✅      | `TickerIngestFacadeTest.kt` (3), `PremiumFacadeTest.kt` (8), `PositionFacadeTest.kt` (11) |

### Phase 4: API Layer ✅

| #  | Task              | Status | Files                                                                                           |
|----|-------------------|--------|-------------------------------------------------------------------------------------------------|
| 13 | Controllers       | ✅      | `TickerController.kt`, `PremiumController.kt`, `PositionController.kt`                          |
| 14 | API DTOs          | ✅      | Request/Response in controllers                                                                 |
| 15 | Exception Handler | ✅      | `GlobalExceptionHandler.kt`                                                                     |
| 16 | API Tests         | ✅      | `TickerControllerTest.kt` (3), `PremiumControllerTest.kt` (6), `PositionControllerTest.kt` (11) |

### Phase 5: Batch Module ✅ (NEW)

| #  | Task                  | Status | Files                                                                     |
|----|-----------------------|--------|---------------------------------------------------------------------------|
| 17 | External API Clients  | ✅      | `BithumbClient.kt`, `BinanceClient.kt`, `ExchangeRateClient.kt`           |
| 18 | Cache Services        | ✅      | `TickerCacheService.kt`, `PremiumCacheService.kt`, `FxCacheService.kt`, `PositionCacheService.kt` |
| 19 | Premium Calculator    | ✅      | `PremiumCalculator.kt`                                                    |
| 20 | Batch Schedulers      | ✅      | `TickerScheduler.kt`, `PremiumScheduler.kt`, `ExchangeRateScheduler.kt`   |

### Phase 6: Support Modules ✅ (NEW)

| #  | Task                  | Status | Files                                                                     |
|----|-----------------------|--------|---------------------------------------------------------------------------|
| 21 | Redis Module          | ✅      | `RedisConfig.kt`, `DistributedLockManager.kt`, `RedisKeyGenerator.kt`, `RedisTtl.kt` |
| 22 | Logging Module        | ✅      | `StructuredLogger.kt`, `LogMaskingFilter.kt`, `RequestLoggingInterceptor.kt` |
| 23 | Monitoring Module     | ✅      | `AlertService.kt`, `PremiumMetrics.kt`, `BatchHealthIndicator.kt`, `ApplicationHealthIndicator.kt` |
| 24 | API Cache Layer       | ✅      | Redis 캐시 우선 조회 구현                                                  |

### Phase 7: Integration

| #  | Task              | Status | Blocked By     |
|----|-------------------|--------|----------------|
| 25 | Integration Tests | ⏳      | Docker 환경    |
| 26 | E2E Tests         | ⏳      | #25            |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           EXTERNAL APIs                                  │
│   Bithumb API (BTC/KRW)  │  Binance Futures  │  ExchangeRate API        │
│   [1초 갱신, 15 req/s]   │  [1초 갱신]        │  [10분 갱신]             │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                        BATCH SERVER (apps:batch)                         │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │  Schedulers: TickerScheduler(1s), PremiumScheduler(1s), FxScheduler │ │
│  │  Clients: BithumbClient, BinanceClient, ExchangeRateClient          │ │
│  │  Calculator: PremiumCalculator                                      │ │
│  │  Cache: TickerCacheService, PremiumCacheService, FxCacheService     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                            REDIS CLUSTER                                 │
│  ticker:bithumb:btc (5s) │ fx:usd:krw (15m) │ premium:btc (5s)          │
│  lock:ticker:all (2s)    │ lock:fx (30s)    │ lock:premium (2s)         │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                        API SERVER (apps:api)                             │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  interfaces/api: Controllers (Premium, Ticker, Position)         │   │
│  │  application: Facades (Cache-First Read)                         │   │
│  │  domain: Services, Entities, Commands                            │   │
│  │  infrastructure: JPA Repositories                                │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                            DATABASE (MySQL)                              │
│  ticker (이력)  │  premium (이력)  │  position  │  api_key (암호화)     │
└─────────────────────────────────────────────────────────────────────────┘
```

### Module Dependencies

```
apps/api ─────┬──► modules/jpa
              ├──► modules/redis
              ├──► supports/logging
              └──► supports/monitoring

apps/batch ───┬──► modules/redis
              ├──► supports/logging
              └──► supports/monitoring
```

### Layered Dependencies

```
Controller → Facade → Service → Repository
                 ↘      ↓
                  Domain Entities/Commands
```

- **Controller**: HTTP 요청/응답 처리
- **Facade**: 유스케이스 오케스트레이션, DTO 변환, 캐시 우선 조회
- **Service**: 도메인 로직, 엔티티 생성 (Command 패턴)
- **Repository**: 영속성 추상화

---

## API Endpoints

### Ticker

```
POST /api/v1/tickers              # Ingest ticker data
```

### Premium

```
POST /api/v1/premiums/calculate/{symbol}    # Calculate premium
GET  /api/v1/premiums/current/{symbol}      # Get latest
GET  /api/v1/premiums/history/{symbol}      # Get history (from, to)
```

### Position

```
POST /api/v1/positions            # Open position
GET  /api/v1/positions            # List open positions
GET  /api/v1/positions/{id}       # Get by ID
GET  /api/v1/positions/{id}/pnl   # Calculate PnL
POST /api/v1/positions/{id}/close # Close position
```

---

## File Structure

```
apps/api/src/main/kotlin/io/premiumspread/
├── domain/
│   ├── ticker/
│   │   ├── Ticker.kt, Premium.kt      ✅ Entities
│   │   ├── Quote.kt, Symbol.kt        ✅ @Embeddable
│   │   ├── Currency.kt, Exchange.kt   ✅ Enums
│   │   ├── TickerRepository.kt        ✅ Interface
│   │   ├── PremiumRepository.kt       ✅ Interface
│   │   ├── TickerService.kt           ✅ Domain Service
│   │   ├── PremiumService.kt          ✅ Domain Service
│   │   └── *Command.kt                ✅ Command Pattern
│   └── position/
│       ├── Position.kt                ✅ Entity
│       ├── PositionPnl.kt             ✅ VO
│       ├── PositionStatus.kt          ✅ Enum
│       ├── PositionRepository.kt      ✅ Interface
│       └── PositionService.kt         ✅ Domain Service
├── application/
│   ├── ticker/
│   │   ├── TickerIngestFacade.kt      ✅ uses TickerService
│   │   └── PremiumFacade.kt           ✅ Cache-First Read
│   └── position/
│       └── PositionFacade.kt          ✅ uses PositionService
├── infrastructure/{domain}/           ✅ JPA Repositories
└── interfaces/api/                    ✅ Controllers

apps/batch/src/main/kotlin/io/premiumspread/  🆕 NEW
├── PremiumSpreadBatchApplication.kt   ✅ @EnableScheduling
├── scheduler/
│   ├── TickerScheduler.kt             ✅ 1초 간격, 분산 락
│   ├── PremiumScheduler.kt            ✅ 1초 간격, 프리미엄 계산
│   └── ExchangeRateScheduler.kt       ✅ 10분 간격
├── calculator/
│   └── PremiumCalculator.kt           ✅ 프리미엄 계산 로직
├── cache/
│   ├── TickerCacheService.kt          ✅ Redis Hash, TTL 5초
│   ├── PremiumCacheService.kt         ✅ Redis Hash + Sorted Set
│   ├── FxCacheService.kt              ✅ 환율 캐시, TTL 15분
│   └── PositionCacheService.kt        ✅ Position 상태 캐시
└── client/
    ├── bithumb/BithumbClient.kt       ✅ BTC/KRW 시세, 재시도 로직
    ├── binance/BinanceClient.kt       ✅ BTCUSDT 선물 시세
    └── exchangerate/ExchangeRateClient.kt  ✅ USD/KRW 환율

modules/redis/src/main/kotlin/io/premiumspread/redis/  🆕 NEW
├── RedisConfig.kt                     ✅ RedisTemplate 설정
├── RedissonConfig.kt                  ✅ Redisson 클라이언트
├── DistributedLockManager.kt          ✅ 분산 락 관리
├── RedisKeyGenerator.kt               ✅ 키 네이밍 규칙
└── RedisTtl.kt                        ✅ TTL 상수

supports/logging/src/main/kotlin/io/premiumspread/logging/  🆕 NEW
├── StructuredLogger.kt                ✅ JSON 구조화 로깅
├── LogMaskingFilter.kt                ✅ API Key 마스킹
├── RequestLoggingInterceptor.kt       ✅ HTTP 요청 로깅
└── LoggingAutoConfiguration.kt        ✅ 자동 설정

supports/monitoring/src/main/kotlin/io/premiumspread/monitoring/  🆕 NEW
├── AlertService.kt                    ✅ 알람 서비스
├── PremiumMetrics.kt                  ✅ Micrometer 메트릭
├── ApplicationHealthIndicator.kt      ✅ 앱 헬스 체크
├── BatchHealthIndicator.kt            ✅ 배치 헬스 체크
└── MonitoringAutoConfiguration.kt     ✅ 자동 설정
```

---

## Test Status

```
API Module Tests: 81 passed ✅
├── Domain Tests: 39 ✅
│   ├── SymbolTest ............. 2 ✅
│   ├── QuoteTest .............. 2 ✅
│   ├── TickerTest ............. 1 ✅
│   ├── PremiumTest ............ 6 ✅
│   ├── PositionTest ........... 9 ✅
│   ├── TickerServiceTest ...... 7 ✅
│   ├── PremiumServiceTest ..... 6 ✅
│   └── PositionServiceTest .... 6 ✅
├── Application Tests: 22 ✅
│   ├── TickerIngestFacadeTest.. 3 ✅
│   ├── PremiumFacadeTest ...... 8 ✅
│   └── PositionFacadeTest .... 11 ✅
└── Controller Tests: 20 ✅
    ├── TickerControllerTest ... 3 ✅
    ├── PremiumControllerTest .. 6 ✅
    └── PositionControllerTest  11 ✅

Repository Tests: 27 pending ⏳ (Docker 환경 필요)
├── TickerRepositoryTest ... 10 ⏳
├── PremiumRepositoryTest .. 9 ⏳
└── PositionRepositoryTest . 8 ⏳

Batch Module: 구현 완료, 테스트 작성 대기
├── Scheduler Tests ........ ⏳
├── Client Tests ........... ⏳
└── Cache Service Tests .... ⏳
```

---

## Quick Commands

```bash
# Compile
./gradlew :apps:api:compileKotlin

# Test (unit tests - no Docker)
./gradlew :apps:api:test --tests "io.premiumspread.domain.*" \
  --tests "io.premiumspread.application.*" \
  --tests "io.premiumspread.interfaces.*"

# Test (repository integration - requires Docker)
./gradlew :apps:api:test --tests "io.premiumspread.infrastructure.persistence.*"

# Test (all - requires Docker)
./gradlew :apps:api:test

# Run application (requires MySQL)
./gradlew :apps:api:bootRun
```

---

## Key Decisions

| Decision               | Choice                                       |
|------------------------|----------------------------------------------|
| FX Provider            | Included in `Exchange` enum as `FX_PROVIDER` |
| Premium observedAt     | Max of input tickers' observedAt             |
| Position base currency | KRW fixed                                    |
| entryPremiumRate       | Stored in Position                           |
| Premium storage        | Converted to JPA Entity                      |
| Symbol storage         | @Embeddable with invoke operator             |
| Domain creation        | Command pattern via Domain Services          |
| Facade-to-Facade deps  | Removed, Facades use Services instead        |
| Cache Strategy         | Redis Hash + Sorted Set (프리미엄 히스토리)   |
| Distributed Lock       | Redisson (tryLock, leaseTime 2-30초)         |
| Batch Scheduler        | @Scheduled (1초/10분 주기)                   |
| External API Client    | WebClient + Retry 로직                       |
| Logging                | JSON 구조화 로깅 + 민감정보 마스킹            |
| Metrics                | Micrometer + Prometheus                      |

---

## Recent Changes

### 2026-01-30: Batch Module & Cache Layer 구현

**추가된 모듈:**

1. **apps/batch** - 배치 서버
   - 외부 API 클라이언트 (Bithumb, Binance, ExchangeRate)
   - 스케줄러 (1초/10분 주기)
   - 캐시 서비스 (Redis Hash/Sorted Set)
   - 프리미엄 계산 엔진

2. **modules/redis** - Redis 인프라
   - DistributedLockManager (Redisson 기반)
   - RedisKeyGenerator, RedisTtl

3. **supports/logging** - 로깅 지원
   - StructuredLogger (JSON 로깅)
   - LogMaskingFilter (민감정보 마스킹)
   - RequestLoggingInterceptor

4. **supports/monitoring** - 모니터링
   - PremiumMetrics (Micrometer)
   - AlertService
   - HealthIndicators

**주요 커밋:**

```
a7f0e1a feat: API 서버 캐시 레이어 및 캐시 우선 조회 구현
2862667 feat: 배치 스케줄러 및 캐시 서비스 구현
08e169c feat: 외부 API 클라이언트 구현 (Bithumb, Binance, ExchangeRate)
43ee957 feat: 멀티모듈 구조 확장 및 Redis 분산 락 구성
```

### 2026-01-29: Repository Integration Tests

- Repository 테스트 코드 작성 완료
- Testcontainers 설정

### 2026-01-28: Domain Services + Command Pattern

- Facade-to-Facade 의존성 제거
- Domain Service + Command 패턴 도입

---

## Next Actions

### Completed ✅

- [x] **Phase 1-4** API 서버 도메인, 인프라, 애플리케이션, API 레이어
- [x] **Phase 5** Batch 모듈 - 외부 API 클라이언트, 스케줄러, 캐시 서비스
- [x] **Phase 6** Support 모듈 - Redis, Logging, Monitoring

### Pending ⏳

- [ ] **#25** Integration Tests - Docker 환경에서 전체 흐름 테스트
- [ ] **#26** E2E Tests - API 서버 + Batch 서버 연동 테스트
- [ ] Production 환경 설정 (application-prod.yml)
- [ ] Dockerfile & docker-compose.yml 작성

## Git Status

```
Recent Commits (feature/premium):
a7f0e1a feat: API 서버 캐시 레이어 및 캐시 우선 조회 구현
2862667 feat: 배치 스케줄러 및 캐시 서비스 구현
08e169c feat: 외부 API 클라이언트 구현 (Bithumb, Binance, ExchangeRate)
43ee957 feat: 멀티모듈 구조 확장 및 Redis 분산 락 구성
```

---

## Redis Key Reference

| Key Pattern | TTL | 용도 |
|-------------|-----|------|
| `ticker:{exchange}:{symbol}` | 5초 | 거래소별 시세 |
| `fx:{base}:{quote}` | 15분 | 환율 |
| `premium:{symbol}` | 5초 | 프리미엄율 |
| `premium:{symbol}:history` | 1시간 | 프리미엄 히스토리 |
| `position:open:exists` | 30초 | 오픈 포지션 존재 여부 |
| `lock:ticker:all` | 2초 | 티커 갱신 락 |
| `lock:fx` | 30초 | 환율 갱신 락 |
| `lock:premium` | 2초 | 프리미엄 계산 락 |

---

## Quick Commands

```bash
# Compile all modules
./gradlew compileKotlin

# Run API server (requires MySQL + Redis)
./gradlew :apps:api:bootRun

# Run Batch server (requires Redis)
./gradlew :apps:batch:bootRun

# Run unit tests
./gradlew test

# Run specific module tests
./gradlew :apps:api:test
./gradlew :apps:batch:test
```

---

## Resume Checklist

```bash
# 1. Check current status
cat claudedocs/IMPLEMENTATION.md

# 2. Check git status
git status

# 3. Continue work
# "통합 테스트 진행해" or "Docker 설정해"
```

---

*Last updated: 2026-01-30 (Batch/Cache 구현 완료, 통합 테스트 대기)*
