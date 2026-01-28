# Premium Spread MVP - Implementation Guide & Progress

> **Project**: 김치 프리미엄 트레이딩 플랫폼
> **Last Updated**: 2026-01-28
> **Branch**: `feature/premium`
> **Status**: UseCase/Controller 테스트 완료, Integration 테스트 대기

## 🔄 Resume Instructions

```bash
# 새 세션에서 시작할 때
cat claudedocs/IMPLEMENTATION.md

# 남은 작업 확인 후
"계속 진행해" 또는 "#7 Repository Tests 진행해"
```

---

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    IMPLEMENTATION PROGRESS                       │
├─────────────────────────────────────────────────────────────────┤
│  Phase 1: Domain         [##########] 100%  ✅ Complete         │
│  Phase 2: Infrastructure [########░░]  80%  🔄 Requires Docker  │
│  Phase 3: Application    [##########] 100%  ✅ Complete         │
│  Phase 4: API            [##########] 100%  ✅ Complete         │
│  Phase 5: Integration    [░░░░░░░░░░]   0%  ⏳ Requires Docker  │
├─────────────────────────────────────────────────────────────────┤
│  Overall: 15/18 tasks (83%)                                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📋 Task Board

### Phase 1: Domain Layer ✅

| # | Task | Status | Files |
|---|------|--------|-------|
| 1 | Position Entity | ✅ | `Position.kt`, `PositionPnl.kt`, `PositionStatus.kt` |
| 2 | Position Tests | ✅ | `PositionTest.kt` (10 cases) |

### Phase 2: Infrastructure Layer

| # | Task | Status | Files |
|---|------|--------|-------|
| 3 | Repository Interfaces | ✅ | `TickerRepository.kt`, `PremiumRepository.kt`, `PositionRepository.kt` |
| 4 | JPA Implementations | ✅ | `*JpaRepository.kt`, `*RepositoryImpl.kt` |
| 5 | Premium → Entity | ✅ | `Premium.kt` (converted), `Symbol.kt` (@Embeddable) |
| 6 | Flyway Migrations | ✅ | `V1__ticker.sql`, `V2__premium.sql`, `V3__position.sql` |
| 7 | Repository Tests | ⏳ | *Requires Docker* |

### Phase 3: Application Layer ✅

| # | Task | Status | Files |
|---|------|--------|-------|
| 8 | Application DTOs | ✅ | `TickerDtos.kt`, `PremiumDtos.kt`, `PositionDtos.kt` |
| 9 | TickerIngestUseCase | ✅ | `TickerIngestFacade.kt` |
| 10 | PremiumUseCase | ✅ | `PremiumFacade.kt` |
| 11 | PositionUseCase | ✅ | `PositionFacade.kt` |
| 12 | UseCase Tests | ✅ | `TickerIngestFacadeTest.kt`, `PremiumFacadeTest.kt`, `PositionFacadeTest.kt` (22 tests) |

### Phase 4: API Layer ✅

| # | Task | Status | Files |
|---|------|--------|-------|
| 13 | Controllers | ✅ | `TickerController.kt`, `PremiumController.kt`, `PositionController.kt` |
| 14 | API DTOs | ✅ | Request/Response in controllers |
| 15 | Exception Handler | ✅ | `GlobalExceptionHandler.kt` |
| 16 | API Tests | ✅ | `TickerControllerTest.kt`, `PremiumControllerTest.kt`, `PositionControllerTest.kt` (20 tests) |

### Phase 5: Integration

| # | Task | Status | Blocked By |
|---|------|--------|------------|
| 17 | Integration Tests | ⏳ | #7, #12, #16 |
| 18 | E2E Tests | ⏳ | #17 |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      interfaces/api                          │
│   TickerController   PremiumController   PositionController  │
│                    GlobalExceptionHandler                    │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                      application                             │
│   TickerIngestFacade   PremiumFacade   PositionFacade       │
│                    (DTOs: Criteria/Result)                   │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                        domain                                │
│  ┌─────────────────┐           ┌─────────────────┐          │
│  │     ticker/     │           │    position/    │          │
│  │  Ticker         │           │  Position       │          │
│  │  Premium        │           │  PositionPnl    │          │
│  │  Quote, Symbol  │           │  PositionStatus │          │
│  │  Exchange       │           │                 │          │
│  │  Currency       │           │                 │          │
│  └─────────────────┘           └─────────────────┘          │
│           │ Repository Interface    │                        │
└───────────┼─────────────────────────┼────────────────────────┘
            │                         │
┌───────────▼─────────────────────────▼────────────────────────┐
│                    infrastructure                             │
│        TickerRepositoryImpl    PositionRepositoryImpl        │
│        PremiumRepositoryImpl   (JPA + Spring Data)           │
└──────────────────────────────────────────────────────────────┘
```

---

## 🔌 API Endpoints

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

## 📁 File Structure

```
apps/api/src/main/kotlin/io/premiumspread/
├── domain/
│   ├── ticker/
│   │   ├── Ticker.kt              ✅ Entity
│   │   ├── Premium.kt             ✅ Entity (converted)
│   │   ├── Quote.kt               ✅ @Embeddable
│   │   ├── Symbol.kt              ✅ @Embeddable
│   │   ├── Currency.kt            ✅ Enum
│   │   ├── Exchange.kt            ✅ Enum
│   │   ├── ExchangeRegion.kt      ✅ Enum
│   │   ├── TickerRepository.kt    ✅ Interface
│   │   ├── PremiumRepository.kt   ✅ Interface
│   │   └── DomainExceptions.kt    ✅
│   └── position/
│       ├── Position.kt            ✅ Entity
│       ├── PositionPnl.kt         ✅ VO
│       ├── PositionStatus.kt      ✅ Enum
│       ├── PositionRepository.kt  ✅ Interface
│       └── PositionExceptions.kt  ✅
├── application/
│   ├── ticker/
│   │   ├── TickerDtos.kt          ✅
│   │   ├── TickerIngestFacade.kt  ✅
│   │   ├── PremiumDtos.kt         ✅
│   │   └── PremiumFacade.kt       ✅
│   └── position/
│       ├── PositionDtos.kt        ✅
│       └── PositionFacade.kt      ✅
├── infrastructure/persistence/
│   ├── TickerJpaRepository.kt     ✅
│   ├── TickerRepositoryImpl.kt    ✅
│   ├── PremiumJpaRepository.kt    ✅
│   ├── PremiumRepositoryImpl.kt   ✅
│   ├── PositionJpaRepository.kt   ✅
│   └── PositionRepositoryImpl.kt  ✅
└── interfaces/api/
    ├── TickerController.kt        ✅
    ├── PremiumController.kt       ✅
    ├── PositionController.kt      ✅
    └── GlobalExceptionHandler.kt  ✅

apps/api/src/main/resources/db/migration/
├── V1__create_ticker_table.sql    ✅
├── V2__create_premium_table.sql   ✅
└── V3__create_position_table.sql  ✅

apps/api/src/test/kotlin/.../
├── TestFixtures.kt                ✅ Test helpers
├── domain/
│   ├── ticker/
│   │   ├── SymbolTest.kt          ✅ 2 tests
│   │   ├── QuoteTest.kt           ✅ 2 tests
│   │   ├── TickerTest.kt          ✅ 1 test
│   │   └── PremiumTest.kt         ✅ 6 tests
│   └── position/
│       └── PositionTest.kt        ✅ 9 tests
├── application/
│   ├── ticker/
│   │   ├── TickerIngestFacadeTest.kt  ✅ 3 tests
│   │   └── PremiumFacadeTest.kt       ✅ 8 tests
│   └── position/
│       └── PositionFacadeTest.kt      ✅ 11 tests
└── interfaces/api/
    ├── TickerControllerTest.kt    ✅ 3 tests
    ├── PremiumControllerTest.kt   ✅ 6 tests
    └── PositionControllerTest.kt  ✅ 11 tests
```

---

## 🧪 Test Status

```
Total Tests: 62 passed ✅

Domain Tests: 20 passed ✅
├── SymbolTest ............ 2 ✅
├── QuoteTest ............. 2 ✅
├── TickerTest ............ 1 ✅
├── PremiumTest ........... 6 ✅
└── PositionTest .......... 9 ✅

Application Tests: 22 passed ✅
├── TickerIngestFacadeTest. 3 ✅
├── PremiumFacadeTest ..... 8 ✅
└── PositionFacadeTest ... 11 ✅

Controller Tests: 20 passed ✅
├── TickerControllerTest .. 3 ✅
├── PremiumControllerTest . 6 ✅
└── PositionControllerTest 11 ✅
```

---

## 🚀 Quick Commands

```bash
# Compile
./gradlew :apps:api:compileKotlin

# Test (domain + application + api - no Docker)
./gradlew :apps:api:test --tests "io.premiumspread.domain.*" \
  --tests "io.premiumspread.application.*" \
  --tests "io.premiumspread.interfaces.*"

# Test (all - requires Docker)
./gradlew :apps:api:test

# Run application (requires MySQL)
./gradlew :apps:api:bootRun
```

---

## 📌 Key Decisions

| Decision | Choice |
|----------|--------|
| FX Provider | Included in `Exchange` enum as `FX_PROVIDER` |
| Premium observedAt | Max of input tickers' observedAt |
| Position base currency | KRW fixed |
| entryPremiumRate | Stored in Position |
| Premium storage | Converted to JPA Entity |
| Symbol storage | @Embeddable with invoke operator |

---

## ⏭️ Next Actions

### Completed ✅
- [x] **#12** UseCase Unit Tests - Mock Repository로 Facade 테스트 (22 tests)
- [x] **#16** API Controller Tests - @WebMvcTest slice 테스트 (20 tests)

### Requires Docker
- [ ] **#7** Repository Integration Tests - TestContainers MySQL
- [ ] **#17** Integration Tests - 전체 흐름 테스트
- [ ] **#18** E2E Tests - HTTP 기반 테스트

## 📊 Git Status

```
Commits (feature/premium):
34ace18 chore: update configurations and existing domain
dba85f5 docs: add implementation guide and progress tracker
e6315b8 feat: add REST API layer (controllers)
1f93b0c feat: add application layer (UseCase/Facade)
6128260 feat: add repository layer and database schema
b2f9a84 feat: implement Position domain entity

PR: 생성 완료
```

---

## 🐛 Known Issues

1. **TestContainers**: Docker not available in WSL2 → Skip integration tests

---

## 📝 Resume Checklist

```bash
# 1. Check current status
cat claudedocs/IMPLEMENTATION.md

# 2. Check git status
git status

# 3. Continue work
# "계속" or specific task number
```

---

*Last updated: 2026-01-28 (UseCase + Controller Tests 완료)*
