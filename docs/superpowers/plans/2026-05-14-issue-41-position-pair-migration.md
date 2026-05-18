# Plan: Position 페어 모델 마이그레이션 V12 + Entity 재구조화

- Issue: #41
- Spec: `docs/superpowers/specs/2026-05-14-issue-41-position-pair-migration-design.md`
- Branch: `feat/issue-41-position-pair-migration`

## 태스크 개요

1. T1 — Flyway V12 마이그레이션 SQL
2. T2 — `Position` Entity 페어 필드 재구조화 + 검증 + `entryPremiumRate` 계산
3. T3 — `PositionCommand.Create` 페어 필드로 변경
4. T4 — `PositionService.create` 시그니처 호환
5. T5 — `PositionCriteria.Open` + `PositionResult.Detail` 페어 필드로 변경
6. T6 — `PositionFacade.openPosition` 페어 필드 흐름 갱신
7. T7 — `PositionRequest.Open` + `PositionResponse.Detail` 페어 필드로 변경
8. T8 — `PositionController.open` 변환 로직 갱신
9. T9 — `http/api/positions.http` 페어 샘플로 갱신
10. T10 — `TestFixtures.PositionFixtures` 페어 필드로 갱신
11. T11 — `PositionTest` 검증 케이스 + `entryPremiumRate` 계산 회귀 테스트
12. T12 — `PositionServiceTest` 시그니처 호환
13. T13 — `PositionFacadeTest` 시그니처 호환
14. T14 — `PositionControllerTest`, `PositionControllerE2ETest` 시그니처 호환
15. T15 — `PositionRepositoryTest`, `PositionCacheWriterTest` 컴파일 호환
16. T16 — 빌드 + 테스트 통과 검증

각 태스크는 **컴파일 가능한 상태로 유지**되어야 한다 (도미노식 변경이라 중간 상태가 컴파일 실패할 수 있으므로 T2~T15는 한 묶음으로 진행). T16에서 최종 검증.

---

## T1. Flyway V12 마이그레이션 SQL

**파일 (신규):** `apps/api/src/main/resources/db/migration/V12__restructure_position_to_pair.sql`

```sql
-- V12: Position 도메인을 한국 long + 해외 short 페어 모델로 재구조화
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
```

**검증:** 정수형 INT NOT NULL DEFAULT 1, MySQL 8에서 `ALTER TABLE ... CHANGE COLUMN`이 정상 동작.

---

## T2. Position Entity 재구조화

**파일:** `apps/api/src/main/kotlin/io/premiumspread/domain/position/Position.kt` (전면 교체)

### 변경 요지

- 단일 거래소 필드 제거: `exchange`, `quantity`, `entryPrice`
- 페어 필드 추가: `koreaExchange`, `koreaQuantity`, `koreaEntryPrice`, `foreignExchange`, `foreignQuantity`, `foreignEntryPrice`, `foreignLeverage`
- 유지: `entryFxRate`, `entryPremiumRate`, `entryObservedAt`, `status`, `memberId`
- 팩토리 `create()` 시그니처 변경: `entryPremiumRate`는 인자에서 제거 (서버 계산)
- 검증 메서드 4개 → 6개로 확장 (region 두 개, 양수 다섯 개 → 합쳐서 5개의 양수 검증 + 레버리지 범위)
- `calculatePremiumDiff` 메서드 시그니처는 그대로 유지 (PnL 수식 변경은 #43)

### 전체 코드 (교체)

```kotlin
package io.premiumspread.domain.position

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.ExchangeRegion
import io.premiumspread.domain.ticker.Symbol
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

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

    fun calculatePremiumDiff(currentPremiumRate: BigDecimal): PositionPnl {
        val premiumDiff = currentPremiumRate.subtract(entryPremiumRate)
        return PositionPnl(
            premiumDiff = premiumDiff,
            entryPremiumRate = entryPremiumRate,
            currentPremiumRate = currentPremiumRate,
            calculatedAt = Instant.now(),
        )
    }

    fun close() {
        if (status == PositionStatus.CLOSED) {
            throw InvalidPositionException("Position is already closed.")
        }
        status = PositionStatus.CLOSED
    }

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
        ): Position {
            validateKoreaRegion(koreaExchange)
            validateForeignRegion(foreignExchange)
            validatePositive("koreaQuantity", koreaQuantity)
            validatePositive("koreaEntryPrice", koreaEntryPrice)
            validatePositive("foreignQuantity", foreignQuantity)
            validatePositive("foreignEntryPrice", foreignEntryPrice)
            validatePositive("entryFxRate", entryFxRate)
            validateLeverage(foreignLeverage)

            val entryPremiumRate = calculateEntryPremiumRate(
                koreaEntryPrice = koreaEntryPrice,
                foreignEntryPrice = foreignEntryPrice,
                entryFxRate = entryFxRate,
            )

            return Position(
                symbol = symbol,
                koreaExchange = koreaExchange,
                koreaQuantity = koreaQuantity,
                koreaEntryPrice = koreaEntryPrice,
                foreignExchange = foreignExchange,
                foreignQuantity = foreignQuantity,
                foreignEntryPrice = foreignEntryPrice,
                foreignLeverage = foreignLeverage,
                entryFxRate = entryFxRate,
                entryPremiumRate = entryPremiumRate,
                entryObservedAt = entryObservedAt,
                memberId = memberId,
            )
        }

        private fun validateKoreaRegion(exchange: Exchange) {
            if (exchange.region != ExchangeRegion.KOREA) {
                throw InvalidPositionException("Korea exchange must be KOREA region.")
            }
        }

        private fun validateForeignRegion(exchange: Exchange) {
            if (exchange.region != ExchangeRegion.FOREIGN) {
                throw InvalidPositionException("Foreign exchange must be FOREIGN region.")
            }
        }

        private fun validatePositive(name: String, value: BigDecimal) {
            if (value <= BigDecimal.ZERO) {
                throw InvalidPositionException("Position $name must be positive.")
            }
        }

        private fun validateLeverage(leverage: Int) {
            if (leverage < 1 || leverage > 125) {
                throw InvalidPositionException("Foreign leverage must be between 1 and 125.")
            }
        }

        private fun calculateEntryPremiumRate(
            koreaEntryPrice: BigDecimal,
            foreignEntryPrice: BigDecimal,
            entryFxRate: BigDecimal,
        ): BigDecimal {
            // Premium.calculatePremiumRate와 동일 정밀도 사용 (DIVISION_SCALE=10, PREMIUM_RATE_SCALE=2)
            val foreignKrw = foreignEntryPrice.multiply(entryFxRate)
            return koreaEntryPrice
                .subtract(foreignKrw)
                .divide(foreignKrw, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        }
    }
}
```

---

## T3. PositionCommand.Create 페어 필드로 변경

**파일:** `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionCommand.kt`

```kotlin
package io.premiumspread.domain.position

import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.time.Instant

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

`entryPremiumRate`는 제거 (서버가 Entity 팩토리에서 계산).

---

## T4. PositionService.create 시그니처 호환

**파일:** `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionService.kt`

`create()` 메서드 본체를 페어 필드로 갱신:

```kotlin
@Transactional
fun create(command: PositionCommand.Create): Position {
    val position = Position.create(
        memberId = command.memberId,
        symbol = Symbol(command.symbol),
        koreaExchange = command.koreaExchange,
        koreaQuantity = command.koreaQuantity,
        koreaEntryPrice = command.koreaEntryPrice,
        foreignExchange = command.foreignExchange,
        foreignQuantity = command.foreignQuantity,
        foreignEntryPrice = command.foreignEntryPrice,
        foreignLeverage = command.foreignLeverage,
        entryFxRate = command.entryFxRate,
        entryObservedAt = command.entryObservedAt,
    )
    return positionRepository.save(position)
}
```

다른 메서드(`save`, `findById`, `findAllOpen*`, `findAllClosed*`)는 변경 없음.

---

## T5. PositionCriteria.Open + PositionResult.Detail 페어 필드로 변경

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt`

```kotlin
class PositionCriteria private constructor() {
    data class Open(
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

class PositionResult private constructor() {
    data class Detail(
        val id: Long,
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
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
        val status: PositionStatus,
    ) {
        companion object {
            fun from(position: Position): Detail = Detail(
                id = position.id,
                memberId = position.memberId,
                symbol = position.symbol.code,
                koreaExchange = position.koreaExchange,
                koreaQuantity = position.koreaQuantity,
                koreaEntryPrice = position.koreaEntryPrice,
                foreignExchange = position.foreignExchange,
                foreignQuantity = position.foreignQuantity,
                foreignEntryPrice = position.foreignEntryPrice,
                foreignLeverage = position.foreignLeverage,
                entryFxRate = position.entryFxRate,
                entryPremiumRate = position.entryPremiumRate,
                entryObservedAt = position.entryObservedAt,
                status = position.status,
            )
        }
    }

    data class Pnl(...) // 기존 그대로
    data class Summary(...) // 기존 그대로
}
```

---

## T6. PositionFacade.openPosition 페어 흐름

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`

`openPosition` 메서드 본체만 갱신:

```kotlin
@Transactional
fun openPosition(criteria: PositionCriteria.Open): PositionResult.Detail {
    val command = PositionCommand.Create(
        memberId = criteria.memberId,
        symbol = criteria.symbol,
        koreaExchange = criteria.koreaExchange,
        koreaQuantity = criteria.koreaQuantity,
        koreaEntryPrice = criteria.koreaEntryPrice,
        foreignExchange = criteria.foreignExchange,
        foreignQuantity = criteria.foreignQuantity,
        foreignEntryPrice = criteria.foreignEntryPrice,
        foreignLeverage = criteria.foreignLeverage,
        entryFxRate = criteria.entryFxRate,
        entryObservedAt = criteria.entryObservedAt,
    )
    val position = positionService.create(command)
    return PositionResult.Detail.from(position)
}
```

다른 메서드(`findById`, `findAll*`, `calculatePnl`, `getSummary`, `closePosition`)는 변경 없음.

---

## T7. PositionRequest.Open + PositionResponse.Detail 페어 필드로 변경

**파일:** `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionDtos.kt`

```kotlin
class PositionRequest private constructor() {
    data class Open(
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryObservedAt: Instant,
    )
}

class PositionResponse private constructor() {
    data class Detail(
        val id: Long,
        val symbol: String,
        val koreaExchange: String,
        val koreaQuantity: BigDecimal,
        val koreaEntryPrice: BigDecimal,
        val foreignExchange: String,
        val foreignQuantity: BigDecimal,
        val foreignEntryPrice: BigDecimal,
        val foreignLeverage: Int,
        val entryFxRate: BigDecimal,
        val entryPremiumRate: BigDecimal,
        val entryObservedAt: Instant,
        val status: String,
    ) {
        companion object {
            fun from(result: PositionResult.Detail): Detail = Detail(
                id = result.id,
                symbol = result.symbol,
                koreaExchange = result.koreaExchange.name,
                koreaQuantity = result.koreaQuantity,
                koreaEntryPrice = result.koreaEntryPrice,
                foreignExchange = result.foreignExchange.name,
                foreignQuantity = result.foreignQuantity,
                foreignEntryPrice = result.foreignEntryPrice,
                foreignLeverage = result.foreignLeverage,
                entryFxRate = result.entryFxRate,
                entryPremiumRate = result.entryPremiumRate,
                entryObservedAt = result.entryObservedAt,
                status = result.status.name,
            )
        }
    }

    data class Pnl(...) // 기존 그대로
    data class Summary(...) // 기존 그대로
}
```

---

## T8. PositionController.open 변환 로직 갱신

**파일:** `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionController.kt`

`open` 메서드만 페어 필드로:

```kotlin
@PostMapping
fun open(
    @LoginMemberId memberId: Long,
    @RequestBody request: PositionRequest.Open,
): ResponseEntity<PositionResponse.Detail> {
    val criteria = PositionCriteria.Open(
        memberId = memberId,
        symbol = request.symbol,
        koreaExchange = Exchange.valueOf(request.koreaExchange),
        koreaQuantity = request.koreaQuantity,
        koreaEntryPrice = request.koreaEntryPrice,
        foreignExchange = Exchange.valueOf(request.foreignExchange),
        foreignQuantity = request.foreignQuantity,
        foreignEntryPrice = request.foreignEntryPrice,
        foreignLeverage = request.foreignLeverage,
        entryFxRate = request.entryFxRate,
        entryObservedAt = request.entryObservedAt,
    )
    val result = positionFacade.openPosition(criteria)
    return ResponseEntity.status(HttpStatus.CREATED).body(PositionResponse.Detail.from(result))
}
```

---

## T9. http/api/positions.http 페어 샘플 갱신

**파일:** `http/api/positions.http`

`POST /positions` 본문을 페어 필드로 교체. 사용자 예시값 사용:

```http
POST {{api_base}}/positions
Content-Type: application/json
Authorization: Bearer {{access_token}}

{
  "symbol": "BTC",
  "koreaExchange": "BITHUMB",
  "koreaQuantity": 0.157,
  "koreaEntryPrice": 161493792,
  "foreignExchange": "BINANCE",
  "foreignQuantity": 0.15,
  "foreignEntryPrice": 118100,
  "foreignLeverage": 5,
  "entryFxRate": 1521.6,
  "entryObservedAt": "2026-04-01T10:30:00Z"
}
```

기존 단일 거래소 형태 샘플은 삭제. 다른 엔드포인트(`/summary`, `/history`, `/{id}`, `/{id}/pnl`, `/{id}/close`)는 동일하게 유지하되 응답 예시는 페어 필드로 갱신.

---

## T10. TestFixtures.PositionFixtures 페어 필드 갱신

**파일:** `apps/api/src/test/kotlin/io/premiumspread/TestFixtures.kt`

```kotlin
object PositionFixtures {
    fun openPosition(
        memberId: Long = 1L,
        symbol: String = "BTC",
        koreaExchange: Exchange = Exchange.UPBIT,
        koreaQuantity: BigDecimal = BigDecimal("0.5"),
        koreaEntryPrice: BigDecimal = BigDecimal("129555000"),
        foreignExchange: Exchange = Exchange.BINANCE,
        foreignQuantity: BigDecimal = BigDecimal("0.5"),
        foreignEntryPrice: BigDecimal = BigDecimal("89500"),
        foreignLeverage: Int = 1,
        entryFxRate: BigDecimal = BigDecimal("1432.6"),
        entryObservedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        id: Long = 1L,
    ): Position {
        return Position.create(
            memberId = memberId,
            symbol = Symbol(symbol),
            koreaExchange = koreaExchange,
            koreaQuantity = koreaQuantity,
            koreaEntryPrice = koreaEntryPrice,
            foreignExchange = foreignExchange,
            foreignQuantity = foreignQuantity,
            foreignEntryPrice = foreignEntryPrice,
            foreignLeverage = foreignLeverage,
            entryFxRate = entryFxRate,
            entryObservedAt = entryObservedAt,
        ).withId(id)
    }
}
```

`entryPremiumRate` 기본값은 더 이상 인자가 아니므로 제거. 호출처에서 결과 단언 시 계산된 값을 검증하도록 변경.

---

## T11. PositionTest 검증 케이스 + entryPremiumRate 회귀

**파일:** `apps/api/src/test/kotlin/io/premiumspread/domain/position/PositionTest.kt` (전면 교체)

추가/갱신 케이스 (AssertJ):

1. `create` 정상 — 모든 페어 필드 정상값, 반환된 Position의 모든 필드가 입력과 일치
2. `entryPremiumRate` 계산 (결정론적 양수 케이스) — koreaEntryPrice=`110000`, foreignEntryPrice=`100`, entryFxRate=`1000` → `(110000 - 100000) / 100000 * 100 = 10.00`
3. `entryPremiumRate` 계산 (결정론적 음수 케이스) — koreaEntryPrice=`90000`, foreignEntryPrice=`100`, entryFxRate=`1000` → `-10.00`
4. `validateKoreaRegion` — koreaExchange에 `BINANCE`를 넣으면 `InvalidPositionException`
5. `validateForeignRegion` — foreignExchange에 `UPBIT`을 넣으면 `InvalidPositionException`
6. `validatePositive` — koreaQuantity = 0, 음수 / foreignQuantity = 0, 음수 / koreaEntryPrice / foreignEntryPrice / entryFxRate 각각 검증
7. `validateLeverage` — `foreignLeverage = 0` 실패, `= 126` 실패, `= 1`, `= 125` 통과
8. `close` 정상 → CLOSED
9. `close` 두 번 호출 → `InvalidPositionException`
10. `calculatePremiumDiff` 정상 (기존 그대로 유지되는지 회귀)

> Note on `1521.6` 사례: 직전 brainstorming의 사용자 예시값으로 역산한 fxRate는 정확히 `-10.12`로 떨어지지 않고 `-10.13`이 된다. 회귀 테스트는 사용자 데이터 재현이 아니라 수식 정확성 보장이 목적이므로, 위와 같이 깔끔한 값을 사용한다.

---

## T12. PositionServiceTest 시그니처 호환

**파일:** `apps/api/src/test/kotlin/io/premiumspread/domain/position/PositionServiceTest.kt`

`PositionCommand.Create` 호출부를 페어 필드로 갱신. mock 반환값도 `PositionFixtures.openPosition()` 사용. `entryPremiumRate` 검증은 Position.create로 계산된 값 사용.

---

## T13. PositionFacadeTest 시그니처 호환

**파일:** `apps/api/src/test/kotlin/io/premiumspread/application/position/PositionFacadeTest.kt`

`PositionCriteria.Open` 호출부 페어 필드로 갱신. PositionResult.Detail 단언도 페어 필드 검증.

---

## T14. PositionController 테스트 갱신

**파일:**
- `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`
- `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerE2ETest.kt`

`PositionRequest.Open` JSON 페이로드를 페어 필드로 갱신. 응답 검증도 페어 필드로.

E2E의 경우 실제 회원 인증과 함께 정상 흐름 + region 검증 위반 케이스 추가.

---

## T15. PositionRepositoryTest, PositionCacheWriterTest 컴파일 호환

**파일:**
- `apps/api/src/test/kotlin/io/premiumspread/infrastructure/position/PositionRepositoryTest.kt`
- `apps/api/src/test/kotlin/io/premiumspread/infrastructure/position/PositionCacheWriterTest.kt`

Position 직접 생성 코드는 `PositionFixtures.openPosition()`으로 통일. 캐시 키 동작은 변경 없음.

---

## T16. 빌드 + 테스트 검증

**명령:**

```bash
cd .worktrees/feat-issue-41-position-pair-migration

# 컴파일
./gradlew :apps:api:compileKotlin :apps:api:compileTestKotlin

# 단위 테스트
./gradlew :apps:api:test --tests "*Position*"

# 전체 unit (회귀)
./gradlew test

# 통합 테스트 (Docker 필요, 가능하면 실행)
./gradlew :apps:api:integrationTest --tests "*Position*"
```

**수용 기준:**

- [ ] `compileKotlin`, `compileTestKotlin` 모두 통과
- [ ] `PositionTest`의 11개 케이스 모두 통과
- [ ] `PositionFacadeTest`, `PositionServiceTest`, `PositionControllerTest`, `PositionControllerE2ETest`, `PositionRepositoryTest`, `PositionCacheWriterTest` 컴파일 + 통과
- [ ] V12 마이그레이션이 testcontainers MySQL에서 깨지지 않음

---

## 진행 메모

- T2~T15는 컴파일 깨짐이 있을 수 있으므로 하나의 작업 단위로 묶어 진행
- 중간 빌드는 마지막(T16)에서 1회만 수행
- 본 이슈에서는 #42, #43, #44 범위에 손대지 않는다
- **운영 배포 차단:** 본 이슈 V12 마이그레이션은 `TRUNCATE`를 포함하므로 dev/local에서만 안전. staging/prod 배포 전 별도 backfill 마이그레이션 또는 데이터 복원 스크립트 작성 필요. 이 사실은 PR 본문에 명시.
- **PnL 정확성 한계:** `PremiumService.findLatestBySymbol`은 페어를 모르므로 본 이슈만 머지된 dev 상태에서 `GET /positions/{id}/pnl` 정확성 보장 없음. #43에서 페어 인지 쿼리로 교체.
