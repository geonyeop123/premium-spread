# Plan: Position PnL 수식 KRW 손익 확장

- Issue: #43
- Spec: `docs/superpowers/specs/2026-05-18-issue-43-position-pnl-krw-design.md`
- Branch: `feat/issue-43-position-pnl-krw`
- Date: 2026-05-18

## 태스크 개요

1. T1 — `PositionPnl` data class 필드 확장 + `isProfit` 시맨틱 변경
2. T2 — `Position.calculatePremiumDiff` 제거 → `Position.calculatePnl(snapshot)` 추가
3. T3 — `PositionResult.Pnl` (application) 필드 확장
4. T4 — `PositionResponse.Pnl` (interfaces) 필드 확장
5. T5 — `PositionFacade.calculatePnl` 갱신 (findLatestBySymbol → findLatestSnapshotBySymbol, position.calculatePnl(snapshot) 호출)
6. T6 — `PositionTest` 회귀 케이스: 사용자 예시 + boundary
7. T7 — `PositionFacadeTest` 회귀 케이스: snapshot mock
8. T8 — `PositionControllerTest` 응답 필드 회귀
9. T9 — `PositionControllerE2ETest` 응답 필드 회귀
10. T10 — `http/api/positions.http` PnL 응답 샘플 갱신 (선택)
11. T11 — 빌드 + 테스트 검증

T1~T10은 시그니처가 연쇄 변경되므로 한 단위로 진행. T11에서 일괄 빌드.

---

## T1. PositionPnl 필드 확장

**파일:** `apps/api/src/main/kotlin/io/premiumspread/domain/position/PositionPnl.kt`

```kotlin
package io.premiumspread.domain.position

import java.math.BigDecimal
import java.time.Instant

data class PositionPnl(
    val premiumDiff: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val currentPremiumRate: BigDecimal,
    val koreaPnl: BigDecimal,
    val foreignPnlKrw: BigDecimal,
    val totalPnlKrw: BigDecimal,
    val koreaCurrentValue: BigDecimal,
    val totalPnlPercent: BigDecimal,
    val calculatedAt: Instant,
) {
    fun isProfit(): Boolean = totalPnlKrw > BigDecimal.ZERO
}
```

---

## T2. Position.calculatePnl 신규, calculatePremiumDiff 제거

**파일:** `apps/api/src/main/kotlin/io/premiumspread/domain/position/Position.kt`

기존 `calculatePremiumDiff(currentPremiumRate)` 메서드 제거. 신규 (PremiumSnapshot에 직접 의존하지 않고 4개 BigDecimal 인자 받음):

```kotlin
import java.math.RoundingMode

// 클래스 내부에 메서드 추가
fun calculatePnl(
    currentKoreaPrice: BigDecimal,
    currentForeignPrice: BigDecimal,
    currentFxRate: BigDecimal,
    currentPremiumRate: BigDecimal,
): PositionPnl {
    require(currentKoreaPrice > BigDecimal.ZERO) { "currentKoreaPrice must be positive" }
    require(currentForeignPrice > BigDecimal.ZERO) { "currentForeignPrice must be positive" }
    require(currentFxRate > BigDecimal.ZERO) { "currentFxRate must be positive" }

    val koreaPnl = currentKoreaPrice
        .subtract(koreaEntryPrice)
        .multiply(koreaQuantity)

    val foreignPnlUsd = foreignEntryPrice
        .subtract(currentForeignPrice)
        .multiply(foreignQuantity)

    val foreignPnlKrw = foreignPnlUsd.multiply(currentFxRate)

    val totalPnlKrw = koreaPnl.add(foreignPnlKrw)

    val koreaCurrentValue = currentKoreaPrice.multiply(koreaQuantity)

    // koreaCurrentValue는 위 require로 양수 보장됨 (currentKoreaPrice > 0, koreaQuantity > 0(엔티티 검증))
    val totalPnlPercent = totalPnlKrw
        .divide(koreaCurrentValue, 10, RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(2, RoundingMode.HALF_UP)

    val premiumDiff = currentPremiumRate.subtract(entryPremiumRate)

    return PositionPnl(
        premiumDiff = premiumDiff,
        entryPremiumRate = entryPremiumRate,
        currentPremiumRate = currentPremiumRate,
        koreaPnl = koreaPnl,
        foreignPnlKrw = foreignPnlKrw,
        totalPnlKrw = totalPnlKrw,
        koreaCurrentValue = koreaCurrentValue,
        totalPnlPercent = totalPnlPercent,
        calculatedAt = Instant.now(),
    )
}
```

기존 `calculatePremiumDiff` 메서드 제거. `PremiumSnapshot` 관련 import는 Position에 추가하지 않는다.

---

## T3. PositionResult.Pnl 필드 확장

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionDtos.kt`

```kotlin
data class Pnl(
    val positionId: Long,
    val premiumDiff: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val currentPremiumRate: BigDecimal,
    val koreaPnl: BigDecimal,
    val foreignPnlKrw: BigDecimal,
    val totalPnlKrw: BigDecimal,
    val koreaCurrentValue: BigDecimal,
    val totalPnlPercent: BigDecimal,
    val isProfit: Boolean,
    val calculatedAt: Instant,
) {
    companion object {
        fun from(positionId: Long, pnl: PositionPnl): Pnl = Pnl(
            positionId = positionId,
            premiumDiff = pnl.premiumDiff,
            entryPremiumRate = pnl.entryPremiumRate,
            currentPremiumRate = pnl.currentPremiumRate,
            koreaPnl = pnl.koreaPnl,
            foreignPnlKrw = pnl.foreignPnlKrw,
            totalPnlKrw = pnl.totalPnlKrw,
            koreaCurrentValue = pnl.koreaCurrentValue,
            totalPnlPercent = pnl.totalPnlPercent,
            isProfit = pnl.isProfit(),
            calculatedAt = pnl.calculatedAt,
        )
    }
}
```

`PositionResult.Detail` / `.Summary`는 변경 없음.

---

## T4. PositionResponse.Pnl 필드 확장

**파일:** `apps/api/src/main/kotlin/io/premiumspread/interfaces/api/position/PositionDtos.kt`

```kotlin
data class Pnl(
    val positionId: Long,
    val premiumDiff: BigDecimal,
    val entryPremiumRate: BigDecimal,
    val currentPremiumRate: BigDecimal,
    val koreaPnl: BigDecimal,
    val foreignPnlKrw: BigDecimal,
    val totalPnlKrw: BigDecimal,
    val koreaCurrentValue: BigDecimal,
    val totalPnlPercent: BigDecimal,
    val isProfit: Boolean,
    val calculatedAt: Instant,
) {
    companion object {
        fun from(result: PositionResult.Pnl): Pnl = Pnl(
            positionId = result.positionId,
            premiumDiff = result.premiumDiff,
            entryPremiumRate = result.entryPremiumRate,
            currentPremiumRate = result.currentPremiumRate,
            koreaPnl = result.koreaPnl,
            foreignPnlKrw = result.foreignPnlKrw,
            totalPnlKrw = result.totalPnlKrw,
            koreaCurrentValue = result.koreaCurrentValue,
            totalPnlPercent = result.totalPnlPercent,
            isProfit = result.isProfit,
            calculatedAt = result.calculatedAt,
        )
    }
}
```

---

## T5. PositionFacade.calculatePnl 갱신

**파일:** `apps/api/src/main/kotlin/io/premiumspread/application/position/PositionFacade.kt`

Facade가 snapshot을 분해해서 Position의 순수 도메인 메서드에 전달:

```kotlin
@Transactional(readOnly = true)
fun calculatePnl(positionId: Long, memberId: Long): PositionResult.Pnl {
    val position = positionService.findById(positionId)
        ?: throw PositionNotFoundException("Position not found: $positionId")
    verifyOwnership(position, memberId)

    val snapshot = premiumService.findLatestSnapshotBySymbol(Symbol(position.symbol.code))
        ?: throw PremiumNotFoundException("Premium not found for symbol: ${position.symbol.code}")

    val pnl = position.calculatePnl(
        currentKoreaPrice = snapshot.koreaPrice,
        currentForeignPrice = snapshot.foreignPrice,
        currentFxRate = snapshot.fxRate,
        currentPremiumRate = snapshot.premiumRate,
    )
    return PositionResult.Pnl.from(positionId, pnl)
}
```

다른 메서드(`openAutoPosition`, `openManualPosition`, 기타)는 변경 없음.

---

## T6. PositionTest 회귀

**파일:** `apps/api/src/test/kotlin/io/premiumspread/domain/position/PositionTest.kt`

`calculatePremiumDiff` 회귀 케이스를 제거하고 `calculatePnl` 회귀 케이스로 교체.

신규 케이스 (모두 필수):

1. **사용자 예시 회귀** — 0.157/0.15 fixture로 totalPnlKrw ≈ 1,808,138 (isEqualByComparingTo), totalPnlPercent = 9.73, isProfit = true
2. **한국 손실 + 해외 손실 (양쪽 손실)** — totalPnlKrw < 0, isProfit = false
3. **isProfit과 premiumDiff 부호 불일치** — 케이스 구성: 한국이 크게 이익이고 해외가 손실이지만 합쳐도 양수이며, 동시에 premiumRate는 축소되지 않은 경우 (또는 그 반대). isProfit이 **totalPnlKrw 기준임을 명시 단언** (시맨틱 변경 회귀)
4. **시세 양수 검증** — `currentKoreaPrice = 0` / 음수, `currentForeignPrice = 0` / 음수, `currentFxRate = 0` / 음수 각각 IllegalArgumentException

```kotlin
@Test
fun `PnL을 페어 기반 KRW 손익으로 계산한다 - 사용자 예시 회귀`() {
    val position = createPosition(
        koreaEntryPrice = BigDecimal("161493792"),
        koreaQuantity = BigDecimal("0.157"),
        foreignEntryPrice = BigDecimal("118100"),
        foreignQuantity = BigDecimal("0.15"),
        entryFxRate = BigDecimal("1521.6"),
    )

    val pnl = position.calculatePnl(
        currentKoreaPrice = BigDecimal("118326000"),
        currentForeignPrice = BigDecimal("79699.1"),
        currentFxRate = BigDecimal("1490.5"),
        currentPremiumRate = BigDecimal("-0.39"),
    )

    assertThat(pnl.koreaPnl).isEqualByComparingTo(BigDecimal("-6777343.344"))
    assertThat(pnl.foreignPnlKrw).isEqualByComparingTo(BigDecimal("8585481.2175"))
    assertThat(pnl.totalPnlKrw).isEqualByComparingTo(BigDecimal("1808137.8735"))
    assertThat(pnl.koreaCurrentValue).isEqualByComparingTo(BigDecimal("18577182"))
    assertThat(pnl.totalPnlPercent).isEqualByComparingTo(BigDecimal("9.73"))
    assertThat(pnl.isProfit()).isTrue()
}
```

기존 `calculatePremiumDiff 회귀` 테스트는 위로 대체.

---

## T7. PositionFacadeTest 회귀

**파일:** `apps/api/src/test/kotlin/io/premiumspread/application/position/PositionFacadeTest.kt`

기존 `calculatePnl` 케이스를 페어 기반으로 변경. mock 대상도 `findLatestBySymbol` → `findLatestSnapshotBySymbol`.

케이스 (필수):

1. `calculatePnl` 정상 — snapshot mock + Position fixture → 응답에 페어 필드 모두 포함
2. `calculatePnl` snapshot 없을 때 → `PremiumNotFoundException`
3. `calculatePnl` 소유권 위반 → `PositionNotFoundException`

---

## T8. PositionControllerTest 회귀

**파일:** `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerTest.kt`

`GET /positions/{id}/pnl` 케이스의 응답 단언을 페어 필드 포함으로 갱신.

```kotlin
@Test
fun `포지션 PnL을 조회한다`() {
    every { positionFacade.calculatePnl(...) } returns pnlResultFixture()

    mockMvc.get("/api/v1/positions/1/pnl") { with(user(testUserDetails)) }
        .andExpect {
            status { isOk() }
            jsonPath("$.totalPnlKrw") { exists() }
            jsonPath("$.koreaPnl") { exists() }
            jsonPath("$.foreignPnlKrw") { exists() }
            jsonPath("$.koreaCurrentValue") { exists() }
            jsonPath("$.totalPnlPercent") { exists() }
            jsonPath("$.isProfit") { isBoolean() }
        }
}
```

`pnlResultFixture()`는 페어 기반 값을 반환하는 내부 헬퍼.

---

## T9. PositionControllerE2ETest 회귀

**파일:** `apps/api/src/test/kotlin/io/premiumspread/interfaces/api/position/PositionControllerE2ETest.kt`

`GET /positions/{id}/pnl` 통합 케이스에서 신선한 PremiumSnapshot 사전 삽입 후 응답에 페어 필드가 모두 포함되는지 검증.

snapshot 없을 때 404 회귀, 소유권 위반 회귀 유지.

---

## T10. http/api/positions.http PnL 응답 샘플 갱신 (선택)

`http/api/positions.http`의 `### 포지션 PnL 조회` 섹션이 있다면 응답 예시 주석을 페어 필드 포함으로 갱신. 코드 동작과 별개이며 가독성용.

---

## T11. 빌드 + 테스트 검증

```bash
./gradlew :apps:api:compileKotlin :apps:api:compileTestKotlin
./gradlew :apps:api:test --tests "*Position*"
```

수용 기준:

- [ ] 컴파일 통과
- [ ] PositionTest 사용자 예시 회귀 통과 (totalPnlKrw ≈ 1808138, totalPnlPercent = 9.73)
- [ ] PositionFacadeTest snapshot mock 케이스 통과
- [ ] PositionControllerTest / E2E 응답 필드 회귀 통과
- [ ] 기타 Position 테스트 회귀 통과

---

## 진행 메모

- T1~T10은 시그니처 연쇄 변경 — 한 단위로 진행
- T11에서 일괄 빌드 검증
- 본 이슈에서는 #44 (프론트엔드)에 손대지 않음
- `isProfit` 시맨틱 변경(premiumDiff<0 → totalPnlKrw>0)은 의도된 breaking. 부호 불일치 회귀 테스트 필수
- `Position.calculatePnl`은 BigDecimal 4개 인자만 받음 → **PremiumSnapshot에 직접 의존하지 않음**. Facade가 분해해서 전달
- 시세 양수 검증으로 zero denominator 마스킹 방지 (require)
- snapshot 거래소 매칭(symbol 단일 기반)은 본 이슈 범위 밖 — premium 도메인 확장이 필요한 별도 작업
