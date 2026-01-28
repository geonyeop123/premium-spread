# Premium Spread MVP - Implementation Guide & Progress

> **Project**: 프리미엄 트레이딩 플랫폼
> **Last Updated**: 2026-01-28
> **Branch**: `feature/premium`
> **Status**: Domain Service + Command 패턴 리팩토링 완료, Integration 테스트 대기

## Resume Instructions

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

## Task Board

### Phase 1: Domain Layer ✅

| #  | Task            | Status | Files                                                                                 |
|----|-----------------|--------|---------------------------------------------------------------------------------------|
| 1  | Position Entity | ✅      | `Position.kt`, `PositionPnl.kt`, `PositionStatus.kt`                                  |
| 2  | Position Tests  | ✅      | `PositionTest.kt` (9 cases)                                                           |
| 2a | Domain Services | ✅      | `TickerService.kt`, `PremiumService.kt`, `PositionService.kt`                         |
| 2b | Domain Commands | ✅      | `TickerCommand.kt`, `PremiumCommand.kt`, `PositionCommand.kt`                         |
| 2c | Service Tests   | ✅      | `TickerServiceTest.kt` (7), `PremiumServiceTest.kt` (6), `PositionServiceTest.kt` (6) |

### Phase 2: Infrastructure Layer

| # | Task                  | Status | Files                                                                  |
|---|-----------------------|--------|------------------------------------------------------------------------|
| 3 | Repository Interfaces | ✅      | `TickerRepository.kt`, `PremiumRepository.kt`, `PositionRepository.kt` |
| 4 | JPA Implementations   | ✅      | `*JpaRepository.kt`, `*RepositoryImpl.kt`                              |
| 5 | Premium → Entity      | ✅      | `Premium.kt` (converted), `Symbol.kt` (@Embeddable)                    |
| 6 | Flyway Migrations     | ✅      | `V1__ticker.sql`, `V2__premium.sql`, `V3__position.sql`                |
| 7 | Repository Tests      | ⏳      | *Requires Docker*                                                      |

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

### Phase 5: Integration

| #  | Task              | Status | Blocked By   |
|----|-------------------|--------|--------------|
| 17 | Integration Tests | ⏳      | #7, #12, #16 |
| 18 | E2E Tests         | ⏳      | #17          |

---

## Architecture

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
                           │ uses Domain Services
┌──────────────────────────▼──────────────────────────────────┐
│                        domain                                │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                    Services                            │  │
│  │  TickerService    PremiumService    PositionService   │  │
│  │  (Command pattern for domain creation)                 │  │
│  └───────────────────────┬───────────────────────────────┘  │
│                          │                                   │
│  ┌─────────────────┐     │     ┌─────────────────┐          │
│  │     ticker/     │     │     │    position/    │          │
│  │  Ticker         │     │     │  Position       │          │
│  │  Premium        │◄────┴────►│  PositionPnl    │          │
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

### Layered Dependencies

```
Controller → Facade → Service → Repository
                 ↘      ↓
                  Domain Entities/Commands
```

- **Controller**: HTTP 요청/응답 처리
- **Facade**: 유스케이스 오케스트레이션, DTO 변환
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
│   │   ├── Ticker.kt              ✅ Entity
│   │   ├── Premium.kt             ✅ Entity
│   │   ├── Quote.kt               ✅ @Embeddable
│   │   ├── Symbol.kt              ✅ @Embeddable
│   │   ├── Currency.kt            ✅ Enum
│   │   ├── Exchange.kt            ✅ Enum
│   │   ├── ExchangeRegion.kt      ✅ Enum
│   │   ├── TickerRepository.kt    ✅ Interface
│   │   ├── PremiumRepository.kt   ✅ Interface
│   │   ├── TickerService.kt       ✅ Domain Service (NEW)
│   │   ├── PremiumService.kt      ✅ Domain Service (NEW)
│   │   ├── TickerCommand.kt       ✅ Command (NEW)
│   │   ├── PremiumCommand.kt      ✅ Command (NEW)
│   │   └── DomainExceptions.kt    ✅
│   └── position/
│       ├── Position.kt            ✅ Entity
│       ├── PositionPnl.kt         ✅ VO
│       ├── PositionStatus.kt      ✅ Enum
│       ├── PositionRepository.kt  ✅ Interface
│       ├── PositionService.kt     ✅ Domain Service (NEW)
│       ├── PositionCommand.kt     ✅ Command (NEW)
│       └── PositionExceptions.kt  ✅
├── application/
│   ├── ticker/
│   │   ├── TickerDtos.kt          ✅
│   │   ├── TickerIngestFacade.kt  ✅ uses TickerService
│   │   ├── PremiumDtos.kt         ✅
│   │   └── PremiumFacade.kt       ✅ uses TickerService, PremiumService
│   └── position/
│       ├── PositionDtos.kt        ✅
│       └── PositionFacade.kt      ✅ uses PositionService, PremiumService
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
│   │   ├── PremiumTest.kt         ✅ 6 tests
│   │   ├── TickerServiceTest.kt   ✅ 7 tests (NEW)
│   │   └── PremiumServiceTest.kt  ✅ 6 tests (NEW)
│   └── position/
│       ├── PositionTest.kt        ✅ 9 tests
│       └── PositionServiceTest.kt ✅ 6 tests (NEW)
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

## Test Status

```
Total Tests: 81 passed ✅

Domain Tests: 39 passed ✅
├── SymbolTest ............. 2 ✅
├── QuoteTest .............. 2 ✅
├── TickerTest ............. 1 ✅
├── PremiumTest ............ 6 ✅
├── PositionTest ........... 9 ✅
├── TickerServiceTest ...... 7 ✅ (NEW)
├── PremiumServiceTest ..... 6 ✅ (NEW)
└── PositionServiceTest .... 6 ✅ (NEW)

Application Tests: 22 passed ✅
├── TickerIngestFacadeTest.. 3 ✅
├── PremiumFacadeTest ...... 8 ✅
└── PositionFacadeTest .... 11 ✅

Controller Tests: 20 passed ✅
├── TickerControllerTest ... 3 ✅
├── PremiumControllerTest .. 6 ✅
└── PositionControllerTest  11 ✅
```

---

## Quick Commands

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

---

## Recent Changes (2026-01-28)

### Refactoring: Domain Services + Command Pattern

**이전 구조:**

```
Facade → Repository (직접 엔티티 생성)
Facade → Facade (의존성 문제)
```

**현재 구조:**

```
Facade → Service → Repository
           ↓
         Command (엔티티 생성 파라미터)
```

**변경 이유:**

1. Facade-to-Facade 의존성 제거 (순환 의존 방지)
2. 도메인 생성 로직을 Service로 캡슐화
3. Command 패턴으로 생성 파라미터 명확화
4. 테스트 용이성 향상 (Service 단위 테스트 가능)

---

## Next Actions

### Completed ✅

- [x] **#2a** Domain Services - 도메인 엔티티 생성/조회 로직 캡슐화
- [x] **#2b** Domain Commands - Command 패턴 적용
- [x] **#2c** Service Tests - 19개 Service 단위 테스트 추가
- [x] **#12** UseCase Unit Tests - Mock Repository로 Facade 테스트 (22 tests)
- [x] **#16** API Controller Tests - @WebMvcTest slice 테스트 (20 tests)

### Requires Docker

- [ ] **#7** Repository Integration Tests - TestContainers MySQL
- [ ] **#17** Integration Tests - 전체 흐름 테스트
- [ ] **#18** E2E Tests - HTTP 기반 테스트

## Git Status

```
Commits (feature/premium):
1b8e58a refactor: move domain creation from Facade to Service with Command pattern
2d1c979 refactor: add domain Services and remove Facade-to-Facade dependencies
77be11c docs: project-overview, skill
e4f673f test: add UseCase and Controller unit tests
c1d3072 docs: update implementation guide with resume instructions
34ace18 chore: update configurations and existing domain

PR: 생성 완료
```

---

## Known Issues

1. **TestContainers**: Docker not available in WSL2 → Skip integration tests

---

## Resume Checklist

```bash
# 1. Check current status
cat claudedocs/IMPLEMENTATION.md

# 2. Check git status
git status

# 3. Continue work
# "계속" or specific task number
```

---

*Last updated: 2026-01-28 (Domain Services + Command 패턴 리팩토링 완료)*
