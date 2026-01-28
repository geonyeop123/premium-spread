# Implementation Workflow: Premium Spread MVP

**Generated**: 2026-01-28
**Strategy**: Systematic
**Project**: Crypto Premium Spread Trading Platform

---

## Executive Summary

본 워크플로우는 김치 프리미엄 트레이딩 플랫폼의 MVP 구현을 위한 체계적인 개발 계획입니다.
현재 도메인 레이어(Ticker, Premium, Quote 등)가 구현되어 있으며, 이를 기반으로 Repository, UseCase, API 레이어를 순차적으로 구축합니다.

---

## Current State Analysis

### Implemented (Complete)
| Component | Status | Location |
|-----------|--------|----------|
| Symbol VO | ✅ Done | `domain/ticker/Symbol.kt` |
| Currency enum | ✅ Done | `domain/ticker/Currency.kt` |
| Exchange enum | ✅ Done | `domain/ticker/Exchange.kt` |
| ExchangeRegion enum | ✅ Done | `domain/ticker/ExchangeRegion.kt` |
| Quote VO | ✅ Done | `domain/ticker/Quote.kt` |
| Ticker Entity | ✅ Done | `domain/ticker/Ticker.kt` |
| Premium VO | ✅ Done | `domain/ticker/Premium.kt` |
| Domain Exceptions | ✅ Done | `domain/ticker/DomainExceptions.kt` |
| BaseEntity | ✅ Done | `modules/jpa/.../BaseEntity.kt` |
| JPA Config | ✅ Done | `modules/jpa/.../JpaConfig.kt` |
| TestContainers | ✅ Done | `modules/jpa/testFixtures/` |

### Test Coverage
| Test File | Cases | Status |
|-----------|-------|--------|
| SymbolTest | 2 | ✅ Pass |
| QuoteTest | 2 | ✅ Pass |
| TickerTest | 1 | ✅ Pass |
| PremiumTest | 6 | ✅ Pass |

### Pending Implementation
| Component | Priority | Phase |
|-----------|----------|-------|
| Position Entity | High | Phase 1 |
| TickerRepository | High | Phase 2 |
| PremiumRepository | High | Phase 2 |
| PositionRepository | Medium | Phase 2 |
| TickerIngestUseCase | High | Phase 3 |
| PremiumCreateUseCase | High | Phase 3 |
| PositionCreateUseCase | Medium | Phase 3 |
| REST API Endpoints | High | Phase 4 |
| Database Migrations | High | Phase 2 |

---

## Phase 1: Position Domain Completion

### Objective
Position 엔티티 구현 및 프리미엄 차익 계산 로직 완성

### Tasks

#### 1.1 Position Entity 구현
**Priority**: High
**Estimated Complexity**: Medium
**Dependencies**: None (domain layer is standalone)

```
Location: apps/api/src/main/kotlin/io/premiumspread/domain/position/
```

**Deliverables**:
- [ ] `Position.kt` - Position 엔티티
  - symbol: Symbol
  - exchange: Exchange
  - quantity: BigDecimal (> 0)
  - entryPrice: BigDecimal (> 0)
  - entryFxRate: BigDecimal (> 0)
  - entryPremiumRate: BigDecimal (scale=2)
  - entryObservedAt: Instant
  - Factory method: `Position.create(...)`

- [ ] `PositionPnl.kt` - PnL 계산 결과 VO
  - premiumDiff: BigDecimal
  - estimatedPnlKrw: BigDecimal
  - currentPremiumRate: BigDecimal
  - calculatedAt: Instant

**Validation Rules**:
- quantity > 0
- entryPrice > 0
- entryFxRate > 0
- exchange.region == KOREA (포지션 기준은 한국 거래소)

#### 1.2 Position Domain Tests
**Priority**: High
**Dependencies**: 1.1

```
Location: apps/api/src/test/kotlin/io/premiumspread/domain/position/
```

**Test Cases**:
- [ ] `PositionTest.kt`
  - 정상 생성 케이스
  - quantity 0/음수 → 예외
  - entryPrice 0/음수 → 예외
  - entryFxRate 0/음수 → 예외
  - 외국 거래소 Position 생성 시도 → 예외 (정책에 따라)

- [ ] `PositionPnlTest.kt`
  - premiumDiff 계산 정확성
  - currentPremium > entryPremium → 손실
  - currentPremium < entryPremium → 이익

### Phase 1 Checkpoint
- [ ] All Position domain tests pass
- [ ] Position entity follows immutable pattern
- [ ] PnL calculation is in domain, not service

---

## Phase 2: Infrastructure Layer (Repository)

### Objective
Repository 인터페이스 정의 및 JPA 구현체 작성

### Tasks

#### 2.1 Domain Repository Interfaces
**Priority**: High
**Dependencies**: Phase 1

```
Location: apps/api/src/main/kotlin/io/premiumspread/domain/ticker/
Location: apps/api/src/main/kotlin/io/premiumspread/domain/position/
```

**Deliverables**:
- [ ] `TickerRepository.kt` (interface in domain)
  ```kotlin
  interface TickerRepository {
      fun save(ticker: Ticker): Ticker
      fun findById(id: Long): Ticker?
      fun findLatest(exchange: Exchange, quote: Quote): Ticker?
      fun findByExchangeAndSymbol(exchange: Exchange, symbol: Symbol): List<Ticker>
  }
  ```

- [ ] `PremiumRepository.kt` (interface in domain)
  ```kotlin
  interface PremiumRepository {
      fun save(premium: Premium): Premium
      fun findLatest(symbol: Symbol): Premium?
      fun findByPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium>
  }
  ```

- [ ] `PositionRepository.kt` (interface in domain)
  ```kotlin
  interface PositionRepository {
      fun save(position: Position): Position
      fun findById(id: Long): Position?
      fun findOpenPositions(): List<Position>
  }
  ```

#### 2.2 JPA Repository Implementations
**Priority**: High
**Dependencies**: 2.1

```
Location: apps/api/src/main/kotlin/io/premiumspread/infrastructure/persistence/
```

**Deliverables**:
- [ ] `TickerJpaRepository.kt` - Spring Data JPA interface
- [ ] `TickerRepositoryImpl.kt` - Domain interface implementation
- [ ] `PremiumJpaRepository.kt`
- [ ] `PremiumRepositoryImpl.kt`
- [ ] `PositionJpaRepository.kt`
- [ ] `PositionRepositoryImpl.kt`

#### 2.3 Premium Entity Conversion
**Priority**: High
**Dependencies**: 2.1

**현재 상태**: Premium은 VO로 구현됨 (ID 없음)
**결정 필요**: 영구 저장을 위해 Entity로 변환 필요

**Options**:
1. Premium을 JPA Entity로 변환 (권장)
2. PremiumEntity 별도 생성, Premium VO와 매핑

**Deliverables**:
- [ ] `Premium.kt` 수정 또는 `PremiumEntity.kt` 생성
- [ ] 기존 테스트 유지/수정

#### 2.4 Database Schema (Flyway)
**Priority**: High
**Dependencies**: 2.2

```
Location: apps/api/src/main/resources/db/migration/
```

**Deliverables**:
- [ ] `V1__create_ticker_table.sql`
  ```sql
  CREATE TABLE ticker (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      exchange VARCHAR(50) NOT NULL,
      exchange_region VARCHAR(20) NOT NULL,
      base_code VARCHAR(20) NOT NULL,
      base_type VARCHAR(10) NOT NULL,
      quote_currency VARCHAR(10) NOT NULL,
      price DECIMAL(30, 10) NOT NULL,
      observed_at TIMESTAMP(6) NOT NULL,
      created_at TIMESTAMP(6) NOT NULL,
      updated_at TIMESTAMP(6) NOT NULL,
      deleted_at TIMESTAMP(6),
      INDEX idx_ticker_lookup (exchange, base_code, quote_currency, observed_at DESC)
  );
  ```

- [ ] `V2__create_premium_table.sql`
  ```sql
  CREATE TABLE premium (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      symbol VARCHAR(20) NOT NULL,
      korea_ticker_id BIGINT NOT NULL,
      foreign_ticker_id BIGINT NOT NULL,
      fx_ticker_id BIGINT NOT NULL,
      premium_rate DECIMAL(10, 2) NOT NULL,
      observed_at TIMESTAMP(6) NOT NULL,
      created_at TIMESTAMP(6) NOT NULL,
      updated_at TIMESTAMP(6) NOT NULL,
      deleted_at TIMESTAMP(6),
      FOREIGN KEY (korea_ticker_id) REFERENCES ticker(id),
      FOREIGN KEY (foreign_ticker_id) REFERENCES ticker(id),
      FOREIGN KEY (fx_ticker_id) REFERENCES ticker(id),
      INDEX idx_premium_symbol (symbol, observed_at DESC)
  );
  ```

- [ ] `V3__create_position_table.sql`
  ```sql
  CREATE TABLE position (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      symbol VARCHAR(20) NOT NULL,
      exchange VARCHAR(50) NOT NULL,
      quantity DECIMAL(30, 10) NOT NULL,
      entry_price DECIMAL(30, 10) NOT NULL,
      entry_fx_rate DECIMAL(20, 6) NOT NULL,
      entry_premium_rate DECIMAL(10, 2) NOT NULL,
      entry_observed_at TIMESTAMP(6) NOT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
      created_at TIMESTAMP(6) NOT NULL,
      updated_at TIMESTAMP(6) NOT NULL,
      deleted_at TIMESTAMP(6),
      INDEX idx_position_status (status)
  );
  ```

#### 2.5 Repository Integration Tests
**Priority**: Medium
**Dependencies**: 2.2, 2.4

```
Location: apps/api/src/test/kotlin/io/premiumspread/infrastructure/persistence/
```

**Test Cases**:
- [ ] `TickerRepositoryTest.kt`
  - save and findById
  - findLatest returns most recent
  - unique constraint handling

- [ ] `PremiumRepositoryTest.kt`
  - save with ticker references
  - findByPeriod date range query

- [ ] `PositionRepositoryTest.kt`
  - save and findOpenPositions
  - status filtering

### Phase 2 Checkpoint
- [ ] All repository tests pass with TestContainers
- [ ] Flyway migrations execute successfully
- [ ] Domain remains framework-agnostic

---

## Phase 3: Application Layer (UseCase)

### Objective
유스케이스 구현으로 비즈니스 흐름 오케스트레이션

### Tasks

#### 3.1 Application DTOs
**Priority**: High
**Dependencies**: Phase 2

```
Location: apps/api/src/main/kotlin/io/premiumspread/application/ticker/
Location: apps/api/src/main/kotlin/io/premiumspread/application/position/
```

**Deliverables**:
- [ ] `TickerIngestCriteria.kt`
  ```kotlin
  data class TickerIngestCriteria(
      val exchange: String,
      val baseCode: String,
      val quoteCurrency: String,
      val price: BigDecimal,
      val observedAt: Instant
  )
  ```

- [ ] `TickerResult.kt`
- [ ] `PremiumCreateCriteria.kt`
- [ ] `PremiumResult.kt`
- [ ] `PositionOpenCriteria.kt`
- [ ] `PositionResult.kt`

#### 3.2 TickerIngestUseCase
**Priority**: High
**Dependencies**: 3.1

```
Location: apps/api/src/main/kotlin/io/premiumspread/application/ticker/
```

**Deliverables**:
- [ ] `TickerIngestFacade.kt`
  ```kotlin
  @Service
  class TickerIngestFacade(
      private val tickerRepository: TickerRepository
  ) {
      fun ingest(criteria: TickerIngestCriteria): TickerResult {
          val ticker = Ticker.create(...)
          return tickerRepository.save(ticker).toResult()
      }
  }
  ```

**Responsibilities**:
- DTO → Domain 변환
- 중복 체크 (같은 exchange+quote+observedAt)
- 저장 및 결과 반환

#### 3.3 PremiumCreateUseCase
**Priority**: High
**Dependencies**: 3.2

```
Location: apps/api/src/main/kotlin/io/premiumspread/application/premium/
```

**Deliverables**:
- [ ] `PremiumCreateFacade.kt`
  ```kotlin
  @Service
  class PremiumCreateFacade(
      private val tickerRepository: TickerRepository,
      private val premiumRepository: PremiumRepository
  ) {
      fun calculateAndSave(symbol: Symbol): PremiumResult {
          val koreaTicker = tickerRepository.findLatest(UPBIT, Quote.coin(symbol, KRW))
          val foreignTicker = tickerRepository.findLatest(BINANCE, Quote.coin(symbol, USD))
          val fxTicker = tickerRepository.findLatest(FX_PROVIDER, Quote.fx(USD, KRW))

          val premium = Premium.create(koreaTicker, foreignTicker, fxTicker)
          return premiumRepository.save(premium).toResult()
      }
  }
  ```

**Responsibilities**:
- 최신 Ticker 3종 조회
- Premium 도메인 생성 (규칙 검증은 도메인이 담당)
- 저장 및 결과 반환

#### 3.4 PositionUseCase
**Priority**: Medium
**Dependencies**: 3.3

```
Location: apps/api/src/main/kotlin/io/premiumspread/application/position/
```

**Deliverables**:
- [ ] `PositionFacade.kt`
  - `openPosition(criteria: PositionOpenCriteria): PositionResult`
  - `calculateCurrentPnl(positionId: Long): PositionPnlResult`
  - `closePosition(positionId: Long): PositionResult`

#### 3.5 UseCase Unit Tests
**Priority**: High
**Dependencies**: 3.2, 3.3, 3.4

```
Location: apps/api/src/test/kotlin/io/premiumspread/application/
```

**Test Cases**:
- [ ] `TickerIngestFacadeTest.kt`
  - 정상 수집 저장
  - 중복 처리 정책

- [ ] `PremiumCreateFacadeTest.kt`
  - 정상 프리미엄 계산 및 저장
  - Ticker 누락 시 예외
  - Mock Repository 활용

- [ ] `PositionFacadeTest.kt`
  - 포지션 생성
  - PnL 계산 흐름

### Phase 3 Checkpoint
- [ ] All facade tests pass
- [ ] Transaction boundaries are correct
- [ ] Error handling is consistent

---

## Phase 4: Interface Layer (REST API)

### Objective
REST API 엔드포인트 구현 및 OpenAPI 문서화

### Tasks

#### 4.1 API Controllers
**Priority**: High
**Dependencies**: Phase 3

```
Location: apps/api/src/main/kotlin/io/premiumspread/interfaces/api/
```

**Deliverables**:
- [ ] `TickerController.kt`
  ```kotlin
  @RestController
  @RequestMapping("/api/v1/tickers")
  class TickerController(
      private val tickerIngestFacade: TickerIngestFacade
  ) {
      @PostMapping
      fun ingest(@RequestBody request: TickerIngestRequest): ResponseEntity<TickerResponse>

      @GetMapping("/latest")
      fun getLatest(@RequestParam exchange: String, @RequestParam symbol: String): ResponseEntity<TickerResponse>
  }
  ```

- [ ] `PremiumController.kt`
  ```kotlin
  @RestController
  @RequestMapping("/api/v1/premiums")
  class PremiumController(
      private val premiumCreateFacade: PremiumCreateFacade
  ) {
      @GetMapping("/current/{symbol}")
      fun getCurrent(@PathVariable symbol: String): ResponseEntity<PremiumResponse>

      @GetMapping("/history/{symbol}")
      fun getHistory(
          @PathVariable symbol: String,
          @RequestParam from: Instant,
          @RequestParam to: Instant
      ): ResponseEntity<List<PremiumResponse>>
  }
  ```

- [ ] `PositionController.kt`
  ```kotlin
  @RestController
  @RequestMapping("/api/v1/positions")
  class PositionController(
      private val positionFacade: PositionFacade
  ) {
      @PostMapping
      fun open(@RequestBody request: PositionOpenRequest): ResponseEntity<PositionResponse>

      @GetMapping("/{id}/pnl")
      fun getPnl(@PathVariable id: Long): ResponseEntity<PositionPnlResponse>
  }
  ```

#### 4.2 Request/Response DTOs
**Priority**: High
**Dependencies**: 4.1

```
Location: apps/api/src/main/kotlin/io/premiumspread/interfaces/api/dto/
```

**Deliverables**:
- [ ] `TickerIngestRequest.kt`
- [ ] `TickerResponse.kt`
- [ ] `PremiumResponse.kt`
- [ ] `PositionOpenRequest.kt`
- [ ] `PositionResponse.kt`
- [ ] `PositionPnlResponse.kt`

#### 4.3 Exception Handling
**Priority**: Medium
**Dependencies**: 4.1

```
Location: apps/api/src/main/kotlin/io/premiumspread/interfaces/api/
```

**Deliverables**:
- [ ] `GlobalExceptionHandler.kt`
  ```kotlin
  @RestControllerAdvice
  class GlobalExceptionHandler {
      @ExceptionHandler(DomainException::class)
      fun handleDomainException(ex: DomainException): ResponseEntity<ErrorResponse>

      @ExceptionHandler(InvalidTickerException::class)
      fun handleInvalidTicker(ex: InvalidTickerException): ResponseEntity<ErrorResponse>
  }
  ```

- [ ] `ErrorResponse.kt`
  ```kotlin
  data class ErrorResponse(
      val code: String,
      val message: String,
      val timestamp: Instant = Instant.now()
  )
  ```

#### 4.4 API Tests
**Priority**: High
**Dependencies**: 4.1, 4.2, 4.3

```
Location: apps/api/src/test/kotlin/io/premiumspread/interfaces/api/
```

**Test Cases**:
- [ ] `TickerControllerTest.kt`
  - POST /api/v1/tickers → 201 Created
  - GET /api/v1/tickers/latest → 200 OK
  - Invalid input → 400 Bad Request

- [ ] `PremiumControllerTest.kt`
  - GET /api/v1/premiums/current/BTC → 200 OK
  - Symbol not found → 404 Not Found

- [ ] `PositionControllerTest.kt`
  - POST /api/v1/positions → 201 Created
  - GET /api/v1/positions/{id}/pnl → 200 OK

### Phase 4 Checkpoint
- [ ] All API tests pass
- [ ] OpenAPI docs generated at /swagger-ui.html
- [ ] Error responses are consistent

---

## Phase 5: Integration & E2E Testing

### Objective
전체 흐름 통합 테스트 및 E2E 검증

### Tasks

#### 5.1 Integration Tests
**Priority**: High
**Dependencies**: Phase 4

```
Location: apps/api/src/test/kotlin/io/premiumspread/integration/
```

**Test Scenarios**:
- [ ] `PremiumCalculationIntegrationTest.kt`
  1. Ingest Korea BTC/KRW ticker
  2. Ingest Foreign BTC/USD ticker
  3. Ingest FX USD/KRW ticker
  4. Calculate premium
  5. Verify stored premium matches expected

- [ ] `PositionLifecycleIntegrationTest.kt`
  1. Create premium
  2. Open position with entry premium
  3. Update tickers (price change)
  4. Calculate new premium
  5. Calculate PnL
  6. Verify profit/loss calculation

#### 5.2 E2E API Tests
**Priority**: Medium
**Dependencies**: 5.1

**Test Scenarios**:
- [ ] Full API flow with real HTTP calls
- [ ] Concurrent ticker ingestion
- [ ] Rate limiting (if implemented)

### Phase 5 Checkpoint
- [ ] All integration tests pass
- [ ] Performance acceptable under load
- [ ] No data integrity issues

---

## Dependency Graph

```
Phase 1 (Position Domain)
    │
    ▼
Phase 2 (Repository/Infrastructure)
    │
    ├── 2.1 Repository Interfaces
    │       │
    │       ▼
    ├── 2.2 JPA Implementations
    │       │
    │       ▼
    ├── 2.3 Premium Entity Conversion
    │       │
    │       ▼
    └── 2.4 Database Schema (Flyway)
            │
            ▼
Phase 3 (Application Layer)
    │
    ├── 3.1 DTOs
    │       │
    │       ▼
    ├── 3.2 TickerIngestUseCase
    │       │
    │       ▼
    ├── 3.3 PremiumCreateUseCase
    │       │
    │       ▼
    └── 3.4 PositionUseCase
            │
            ▼
Phase 4 (REST API)
    │
    ├── 4.1 Controllers
    │       │
    │       ▼
    ├── 4.2 Request/Response DTOs
    │       │
    │       ▼
    └── 4.3 Exception Handling
            │
            ▼
Phase 5 (Integration Testing)
```

---

## Commit Strategy

### Commit Message Convention
```
<type>: <subject>

<body>

<footer>
```

**Types**:
- `test`: 테스트 추가/수정
- `feat`: 새 기능
- `refactor`: 코드 리팩토링
- `fix`: 버그 수정
- `docs`: 문서 수정
- `chore`: 빌드/설정 변경

### PR Strategy
각 Phase 완료 시 PR 생성:
- `feat/position-domain` (Phase 1)
- `feat/repository-layer` (Phase 2)
- `feat/usecase-layer` (Phase 3)
- `feat/api-layer` (Phase 4)
- `feat/integration-tests` (Phase 5)

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Premium VO → Entity 변환 시 테스트 깨짐 | 기존 테스트 먼저 백업, 점진적 마이그레이션 |
| Ticker 데이터 급증 | MVP에서는 무시, 추후 TTL/파티셔닝 설계 |
| 환율 API 장애 | FX_PROVIDER fallback 또는 캐싱 전략 |
| 동시성 이슈 (Position 생성) | @Transactional + 낙관적 락 적용 |

---

## Quality Gates

### Per-Phase Gates
- [ ] All tests pass (unit + integration)
- [ ] No compiler warnings
- [ ] KtLint passes
- [ ] Code coverage >= 80% for domain layer

### Final MVP Gates
- [ ] All 5 phases complete
- [ ] API documentation complete
- [ ] No critical/high security issues
- [ ] Performance: < 100ms for premium calculation

---

## Next Steps

**To execute this plan, run**:
```
/sc:implement workflow_premium-spread-mvp.md --phase 1
```

This will begin implementation of Phase 1 (Position Domain) following TDD workflow.

---

*Generated by /sc:workflow - SuperClaude Workflow Generator*
