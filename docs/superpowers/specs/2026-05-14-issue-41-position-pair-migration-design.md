# Position 페어 모델 마이그레이션 V12 + Entity 재구조화

- Issue: #41 (Parent: #40)
- Branch: `feat/issue-41-position-pair-migration`
- Date: 2026-05-14

## 배경

현재 `Position` 엔티티는 단일 거래소(한국 region 강제)만 모델링한다. 실제 비즈니스(김프 헷지: 한국 long + 해외 short)는 양쪽 거래소 페어로 진입하므로 도메인 모델이 어긋난다. 또한 클라이언트가 보낸 `entryPrice/entryFxRate/entryPremiumRate`를 검증 없이 그대로 저장하고, `quantity/entryPrice/entryFxRate`는 PnL 계산에 사용되지 않는 죽은 필드 상태이다.

본 작업은 Parent #40의 첫 단계로, **DB 스키마와 도메인 엔티티를 페어 모델로 재구조화**한다. API 분기(#42), PnL 확장(#43), 프론트엔드(#44)는 후속 이슈에서 다룬다.

## 목표 / 스코프

### 포함

- Flyway `V12__restructure_position_to_pair.sql`
- `Position` Entity 페어 필드로 재구조화 + 검증 규칙
- `entryPremiumRate` 서버 계산 로직 (도메인)
- 기존 Entity 호환성 깨지므로 함께 컴파일/실행 가능하게 만드는 최소한의 도미노 처리:
  - `Position` 엔티티 시그니처에 의존하는 `PositionFacade`, `PositionService`, `PositionRepository`, `PositionRepositoryImpl`, `PositionCacheWriter`, DTO들, Controller, 테스트 픽스처를 **컴파일이 가능한 형태로 임시 정리**
  - AUTO/MANUAL 엔드포인트 분기와 본격적 비즈니스 로직 재작성은 #42 범위. 본 이슈에서는 기존 `POST /positions` 한 개를 새 Entity 구조에 맞춰 **최소 동작**시키되 (요청 본문도 페어 필드 그대로 받기), 입력 검증 보강, PnL 재정의는 후속에서 한다

### 제외 (후속 이슈)

- AUTO/MANUAL 엔드포인트 분기 (#42)
- PnL KRW 손익 수식 (#43) — 본 이슈에서는 기존 `premiumDiff` 그대로 유지
- 프론트엔드 (#44)

## 도메인 결정

- 방향 고정: **한국 long, 해외 short** (Entity 팩토리 검증으로 강제)
- 한국은 spot 가정 — 레버리지 필드 없음
- 해외는 선물 가정 — `foreignLeverage` 필드 (1 ~ 125, 디폴트 1)
- `entryPremiumRate`는 서버 계산: `(koreaEntryPrice - foreignEntryPrice * entryFxRate) / (foreignEntryPrice * entryFxRate) * 100`
- 기존 dev 데이터는 TRUNCATE (페어 정보가 없으므로 마이그 불가)

## 데이터 모델

### V12 마이그레이션

```sql
-- V12__restructure_position_to_pair.sql
-- Position 도메인을 한국 long + 해외 short 페어 모델로 재구조화한다.
-- 기존 단일 거래소 컬럼을 한국 측 컬럼으로 rename 하고 해외 측 컬럼을 신규 추가한다.
-- 페어 정보를 채울 수 없으므로 기존 행은 TRUNCATE.

TRUNCATE TABLE position;

ALTER TABLE position
    CHANGE COLUMN exchange      korea_exchange      VARCHAR(50)     NOT NULL,
    CHANGE COLUMN quantity      korea_quantity      DECIMAL(30, 10) NOT NULL,
    CHANGE COLUMN entry_price   korea_entry_price   DECIMAL(30, 10) NOT NULL,
    ADD COLUMN    foreign_exchange     VARCHAR(50)     NOT NULL AFTER korea_entry_price,
    ADD COLUMN    foreign_quantity     DECIMAL(30, 10) NOT NULL AFTER foreign_exchange,
    ADD COLUMN    foreign_entry_price  DECIMAL(30, 10) NOT NULL AFTER foreign_quantity,
    ADD COLUMN    foreign_leverage     INT             NOT NULL DEFAULT 1 AFTER foreign_entry_price;

-- 기존 idx_position_status, idx_position_symbol 인덱스는 유지
-- member_id FK도 유지
```

### Entity 정의 (최종)

```kotlin
@Entity
@Table(name = "position")
class Position private constructor(
    @Embedded
    @AttributeOverride(name = "code", column = Column(name = "symbol"))
    val symbol: Symbol,

    @Enumerated(EnumType.STRING)
    @Column(name = "korea_exchange", nullable = false)
    val koreaExchange: Exchange,

    @Column(name = "korea_quantity", nullable = false, precision = 30, scale = 10)
    val koreaQuantity: BigDecimal,

    @Column(name = "korea_entry_price", nullable = false, precision = 30, scale = 10)
    val koreaEntryPrice: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "foreign_exchange", nullable = false)
    val foreignExchange: Exchange,

    @Column(name = "foreign_quantity", nullable = false, precision = 30, scale = 10)
    val foreignQuantity: BigDecimal,

    @Column(name = "foreign_entry_price", nullable = false, precision = 30, scale = 10)
    val foreignEntryPrice: BigDecimal,

    @Column(name = "foreign_leverage", nullable = false)
    val foreignLeverage: Int,

    @Column(name = "entry_fx_rate", nullable = false, precision = 20, scale = 6)
    val entryFxRate: BigDecimal,

    @Column(name = "entry_premium_rate", nullable = false, precision = 10, scale = 2)
    val entryPremiumRate: BigDecimal,

    @Column(name = "entry_observed_at", nullable = false)
    val entryObservedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PositionStatus = PositionStatus.OPEN,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,
) : BaseEntity() {

    fun close() { ... }

    companion object {
        fun create(
            memberId: Long,
            symbol: Symbol,
            koreaExchange: Exchange,
            koreaQuantity: BigDecimal,
            koreaEntryPrice: BigDecimal,
            foreignExchange: Exchange,
            foreignQuantity: BigDecimal,
            foreignEntryPrice: BigDecimal,
            foreignLeverage: Int,
            entryFxRate: BigDecimal,
            entryObservedAt: Instant,
        ): Position { ... }
    }
}
```

### 검증 규칙 (Position.create 내부)

| 항목 | 규칙 | 위반 시 |
|------|------|---------|
| koreaExchange.region | == `KOREA` | `InvalidPositionException("Korea exchange must be KOREA region.")` |
| foreignExchange.region | == `FOREIGN` | `InvalidPositionException("Foreign exchange must be FOREIGN region.")` |
| koreaQuantity, koreaEntryPrice, foreignQuantity, foreignEntryPrice, entryFxRate | > 0 | `InvalidPositionException("<field> must be positive.")` |
| foreignLeverage | 1 ≤ x ≤ 125 | `InvalidPositionException("Foreign leverage must be between 1 and 125.")` |

### entryPremiumRate 계산

```kotlin
private fun calculateEntryPremiumRate(
    koreaEntryPrice: BigDecimal,
    foreignEntryPrice: BigDecimal,
    entryFxRate: BigDecimal,
): BigDecimal {
    val foreignKrw = foreignEntryPrice.multiply(entryFxRate)
    return koreaEntryPrice
        .subtract(foreignKrw)
        .divide(foreignKrw, 10, RoundingMode.HALF_UP)   // Premium.calculatePremiumRate와 동일 DIVISION_SCALE
        .multiply(BigDecimal(100))
        .setScale(2, RoundingMode.HALF_UP)              // Premium과 동일 PREMIUM_RATE_SCALE
}
```

**정밀도:** `Premium.calculatePremiumRate`의 `DIVISION_SCALE=10, PREMIUM_RATE_SCALE=2`와 정확히 동일하게 맞춘다. 두 곳에서 같은 수식을 다른 정밀도로 계산하면 entry vs current 비교 시 ±0.01 차이가 누락 또는 부풀어진다.

**회귀 테스트 fixture** (결정론적 깔끔한 값):
- koreaEntryPrice = 110_000, foreignEntryPrice = 100, entryFxRate = 1000
- → foreignKrw = 100_000, diff = 10_000, premium = 10.00
- 0이 아닌 음수 케이스도 추가: koreaEntryPrice = 90_000, foreignEntryPrice = 100, entryFxRate = 1000 → premium = -10.00

## API/도메인 도미노 처리 (이슈 #41 범위)

기존 코드를 컴파일 가능 상태로 유지하기 위한 최소 변경. **본격적인 AUTO/MANUAL 분기는 #42에서 수행.**

### `PositionCommand.Create` 변경

```kotlin
class PositionCommand private constructor() {
    data class Create(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: Exchange,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: Exchange,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}
```

### `PositionService.create()`

새 시그니처 그대로 `Position.create()` 호출. 변경 없음.

### `PositionFacade.openPosition()`

`PositionCriteria.Open` 입력으로 받은 페어 필드로 Command 조립. 검증/계산은 Entity 팩토리에 위임. 기존 단일 거래소 입력 경로는 폐기.

### `PositionRepositoryImpl` / `PositionCacheWriter`

Position 엔티티의 페어 필드를 사용하지 않으므로 변경 없음. 인터페이스 호환만 유지.

### `PositionCriteria.Open` / `PositionRequest.Open` / `PositionResponse.Detail`

페어 필드로 갱신:
- 입력 12개 필드 (`PositionRequest.Open`): symbol, koreaExchange, koreaQuantity, koreaEntryPrice, foreignExchange, foreignQuantity, foreignEntryPrice, foreignLeverage, entryFxRate, entryObservedAt
- 응답 (`PositionResponse.Detail`): 위 + `entryPremiumRate` (서버 계산) + `status` + `id`

기존 `quantity/entryPrice` 1개 거래소 형태의 `PositionRequest.Open` / `PositionResponse.Detail`은 폐기.

### `PositionPnl` / `Position.calculatePremiumDiff` / `PositionResponse.Pnl`

본 이슈에서는 기존 동작 그대로 유지하되, `entryPremiumRate` 가 새 수식으로 저장되도록만 보장. PnL 응답은 #43에서 KRW 손익 포함하여 재구성.

### Controller / http 파일

`POST /api/v1/positions` 한 개 엔드포인트만 유지하되, Request 본문을 페어 필드로 갱신. http 샘플도 페어 필드로 갱신.

## 영향 범위

- `apps/api/src/main/resources/db/migration/V12__restructure_position_to_pair.sql` (신규)
- `apps/api/src/main/kotlin/io/premiumspread/domain/position/` 전체 (Entity, Command, Service, Repository interface는 시그니처 영향)
- `apps/api/src/main/kotlin/io/premiumspread/infrastructure/position/` (RepositoryImpl 컴파일만)
- `apps/api/src/main/kotlin/io/premiumspread/application/position/` (Facade, Criteria, Result)
- `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/` (Controller, Request/Response)
- `apps/api/src/test/kotlin/...` 기존 Position 관련 테스트 픽스처 / 테스트 케이스
- `http/api/position.http`

## 테스트 전략

### Unit

- `PositionTest`
  - `create()` 정상 케이스: 모든 검증 통과, `entryPremiumRate` 계산 정확성
  - region 위반 (한국 자리에 BINANCE 등, 해외 자리에 UPBIT 등)
  - 음수/0 quantity, price, fxRate
  - 레버리지 범위 외 (0, 126)
  - `close()` 동작 (기존 그대로)

- `PositionFacadeTest`
  - 페어 필드 입력으로 `openPosition` 성공
  - region 위반 시 예외 전파

### Integration

- 기존 `PositionControllerTest`/`PositionRepositoryTest`가 있다면 새 스키마로 마이그레이션
- V12 SQL이 testcontainers MySQL 실행 시 깨지지 않는지 통과 확인

## 위험 / 주의사항

- **DB 마이그레이션 데이터 손실 (CRITICAL)**: V12는 `TRUNCATE TABLE position`을 포함한다. Flyway는 환경 무관하게 실행되므로 **본 이슈가 머지된 상태에서 staging/prod 데이터베이스가 처음 V12 마이그레이션을 실행하면 모든 포지션이 영구 삭제된다**. 본 이슈는 **dev/local 환경에 데이터가 없다는 전제 하에서만** 안전하다. 운영 환경 배포 전에는 반드시 다음 중 하나를 수행해야 한다:
  - (a) 별도 backfill 마이그레이션(`V13__backfill_position_pair.sql`)으로 페어 정보를 채우는 expand/backfill/contract 패턴으로 재구성
  - (b) 또는 운영 데이터를 미리 백업하고 V12 적용 후 별도 스크립트로 데이터 복원
  - 이 사실은 PR 본문, ⑪-a 단계 문서 갱신, 그리고 Parent #40 머지 시 운영 배포 체크리스트에 명시한다.
- **본 이슈 PR이 머지되면 기존 `POST /positions` 클라이언트(프론트)는 깨진 상태**가 됨. 프론트 갱신은 #44에서. 본 단계에선 통합 테스트로만 검증.
- **PnL 부정확성**: `PositionFacade.calculatePnl`은 본 이슈 범위에서 변경하지 않는다. 그러나 `PremiumService.findLatestBySymbol(symbol)`은 **거래소 페어를 모르므로** 새 페어 모델 하에서는 한국/해외 거래소 조합과 무관한 최신 premium을 반환할 수 있다. 본 이슈만 머지된 dev 환경에서 `GET /positions/{id}/pnl` 결과는 entry premium은 페어 기준, current premium은 symbol 기준 임의 페어 → **정확성 보장 없음**. #43에서 페어 인지 PnL 쿼리로 교체될 때까지 dev 전용 동작으로 간주한다.
- `entry_premium_rate` 의 정밀도 (DECIMAL(10, 2)) 가 새 수식 결과(반올림)와 충돌하지 않는지 확인. (Premium 도메인과 동일 scale을 사용하므로 호환됨)

## Out of Scope (재확인)

- AUTO/MANUAL 엔드포인트 분기 → #42
- KRW PnL 수식 → #43
- 프론트엔드 변경 → #44
