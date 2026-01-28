# Premium Spread MVP - Implementation Guide & Progress

> **Project**: 김치 프리미엄 트레이딩 플랫폼
> **Last Updated**: 2026-01-28
> **Branch**: `feature/premium`

---

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    IMPLEMENTATION PROGRESS                       │
├─────────────────────────────────────────────────────────────────┤
│  Phase 1: Domain         [##########] 100%  ✅ Complete         │
│  Phase 2: Infrastructure [########░░]  80%  🔄 In Progress      │
│  Phase 3: Application    [########░░]  80%  🔄 In Progress      │
│  Phase 4: API            [#######░░░]  75%  🔄 In Progress      │
│  Phase 5: Integration    [░░░░░░░░░░]   0%  ⏳ Pending          │
├─────────────────────────────────────────────────────────────────┤
│  Overall: 13/18 tasks (72%)                                     │
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

### Phase 3: Application Layer

| # | Task | Status | Files |
|---|------|--------|-------|
| 8 | Application DTOs | ✅ | `TickerDtos.kt`, `PremiumDtos.kt`, `PositionDtos.kt` |
| 9 | TickerIngestUseCase | ✅ | `TickerIngestFacade.kt` |
| 10 | PremiumUseCase | ✅ | `PremiumFacade.kt` |
| 11 | PositionUseCase | ✅ | `PositionFacade.kt` |
| 12 | UseCase Tests | ⏳ | *Next* |

### Phase 4: API Layer

| # | Task | Status | Files |
|---|------|--------|-------|
| 13 | Controllers | ✅ | `TickerController.kt`, `PremiumController.kt`, `PositionController.kt` |
| 14 | API DTOs | ✅ | Request/Response in controllers |
| 15 | Exception Handler | ✅ | `GlobalExceptionHandler.kt` |
| 16 | API Tests | ⏳ | *Next* |

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

apps/api/src/test/kotlin/.../domain/
├── ticker/
│   ├── SymbolTest.kt              ✅ 2 tests
│   ├── QuoteTest.kt               ✅ 2 tests
│   ├── TickerTest.kt              ✅ 1 test
│   └── PremiumTest.kt             ✅ 6 tests
└── position/
    └── PositionTest.kt            ✅ 10 tests
```

---

## 🧪 Test Status

```
Domain Tests: 21 passed ✅
├── SymbolTest ............ 2 ✅
├── QuoteTest ............. 2 ✅
├── TickerTest ............ 1 ✅
├── PremiumTest ........... 6 ✅
└── PositionTest ......... 10 ✅
```

---

## 🚀 Quick Commands

```bash
# Compile
./gradlew :apps:api:compileKotlin

# Test (domain only - no Docker)
./gradlew :apps:api:test --tests "io.premiumspread.domain.*"

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

### Ready Now
- [ ] **#12** UseCase Unit Tests (Mock Repository)
- [ ] **#16** API Controller Tests

### Requires Docker
- [ ] **#7** Repository Integration Tests

### After Dependencies
- [ ] **#17** Integration Tests
- [ ] **#18** E2E Tests

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

*Last generated: 2026-01-28*
