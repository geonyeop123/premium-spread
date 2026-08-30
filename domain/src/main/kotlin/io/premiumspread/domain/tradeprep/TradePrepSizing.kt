package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ECO-5 §2 사이징 관계식의 순수 함수와, lot/step 반올림 뒤 재판정까지의 전체 사이징
 * 파이프라인이다 (design.md §3, D12). BigDecimal만 쓴다 — Double로 돈을 다루지 않는다.
 *
 * ```
 * R = B_k / (X · B_b)      두 계정 잔고 비율
 * L = R / (1 + P)          레버리지는 독립 변수가 아니라 R 의 함수다
 * Q = B_k / K              K = F · X · (1+P), 양쪽 100% 투입 시 물량
 * ```
 *
 * `B_k` 빗썸 원화 잔고 · `B_b` 바이낸스 USD 잔고 · `X` 환율 · `F` 해외가(USD) ·
 * `P` 진입 프리미엄. `premiumRatePercent`는 [io.premiumspread.domain.premium.PremiumSnapshot]과
 * 같은 percent 단위다(예: `3.50` = 3.50%, 이 함수 내부에서 `/100`으로 fraction 전환한다).
 */
object TradePrepSizing {
    const val CALCULATION_SCALE = 10
    private val HUNDRED = BigDecimal("100")

    /** R = B_k / (X · B_b) — 두 계정 잔고 비율. */
    fun balanceRatio(koreaBalance: BigDecimal, fxRate: BigDecimal, foreignBalance: BigDecimal): BigDecimal {
        require(koreaBalance >= BigDecimal.ZERO) { "Korea balance must not be negative: $koreaBalance" }
        require(fxRate > BigDecimal.ZERO) { "FX rate must be positive: $fxRate" }
        require(foreignBalance > BigDecimal.ZERO) { "Foreign balance must be positive: $foreignBalance" }
        return divide(koreaBalance, fxRate.multiply(foreignBalance))
    }

    /** L = R / (1+P) — 레버리지는 R 의 함수다. */
    fun leverage(balanceRatio: BigDecimal, premiumRatePercent: BigDecimal): BigDecimal {
        val onePlusP = onePlusPremium(premiumRatePercent)
        require(onePlusP > BigDecimal.ZERO) { "1+P must be positive, got premium $premiumRatePercent" }
        return divide(balanceRatio, onePlusP)
    }

    /** Q = B_k / K, K = F · X · (1+P) — 양쪽 100% 투입 시 물량. */
    fun quantity(
        koreaBalance: BigDecimal,
        foreignPrice: BigDecimal,
        fxRate: BigDecimal,
        premiumRatePercent: BigDecimal,
    ): BigDecimal {
        require(koreaBalance >= BigDecimal.ZERO) { "Korea balance must not be negative: $koreaBalance" }
        require(foreignPrice > BigDecimal.ZERO) { "Foreign price must be positive: $foreignPrice" }
        require(fxRate > BigDecimal.ZERO) { "FX rate must be positive: $fxRate" }
        val onePlusP = onePlusPremium(premiumRatePercent)
        val k = foreignPrice.multiply(fxRate).multiply(onePlusP)
        require(k > BigDecimal.ZERO) { "K must be positive, got premium $premiumRatePercent" }
        return divide(koreaBalance, k)
    }

    /** 빗썸 비중 = B_k / (B_k + X·B_b) — ECO-5 §2 정지 캡 환산식. 물량(Q)에 무관하다. */
    fun koreaShare(koreaBalance: BigDecimal, fxRate: BigDecimal, foreignBalance: BigDecimal): BigDecimal {
        require(koreaBalance >= BigDecimal.ZERO) { "Korea balance must not be negative: $koreaBalance" }
        require(fxRate > BigDecimal.ZERO) { "FX rate must be positive: $fxRate" }
        require(foreignBalance > BigDecimal.ZERO) { "Foreign balance must be positive: $foreignBalance" }
        val foreignInKrw = fxRate.multiply(foreignBalance)
        val total = koreaBalance.add(foreignInKrw)
        require(total > BigDecimal.ZERO) { "Total balance must be positive: $total" }
        return divide(koreaBalance, total)
    }

    /**
     * 원시 `R·L·Q` 계산 → lot/step 보수적 반올림(내림) → 양 leg 중 작은 쪽 채택 →
     * 채택 물량 기준 leverage 재계산 → 캡 재판정까지의 전체 파이프라인이다 (D12).
     *
     * `koreaShare`(빗썸 비중)는 두 계정 **잔고**만의 함수라 물량(`Q`) 반올림에 무관하게 그대로다
     * ([koreaShare] 참고). 반올림이 바꾸는 것은 물량과, 물량에서 다시 유도하는 leverage다 —
     * `finalLeverage = finalQuantity · F / B_b`. 물량이 내림으로만 줄어들므로
     * `finalLeverage <= rawLeverage` 가 항상 성립해 레버 캡을 새로 어기지는 않는다. 대신 반올림이
     * 두 leg 의 최소 거래 단위 아래로 물량을 만들면(`finalQuantity <= 0`) 캡 위반과 무관하게
     * 계획을 만들지 않는다 ([TradePrepSizingResult.isPlannable]).
     */
    fun size(
        koreaBalance: BigDecimal,
        foreignBalance: BigDecimal,
        fxRate: BigDecimal,
        foreignPrice: BigDecimal,
        premiumRatePercent: BigDecimal,
        policy: TradePrepPolicy,
    ): TradePrepSizingResult {
        val ratio = balanceRatio(koreaBalance, fxRate, foreignBalance)
        val rawLeverage = leverage(ratio, premiumRatePercent)
        val rawQuantity = quantity(koreaBalance, foreignPrice, fxRate, premiumRatePercent)
        val share = koreaShare(koreaBalance, fxRate, foreignBalance)

        val koreaRounded = policy.roundKoreaQuantity(rawQuantity)
        val foreignRounded = policy.roundForeignQuantity(rawQuantity)
        val finalQuantity = koreaRounded.min(foreignRounded)

        val finalLeverage =
            if (finalQuantity > BigDecimal.ZERO) {
                divide(finalQuantity.multiply(foreignPrice), foreignBalance)
            } else {
                BigDecimal.ZERO
            }

        val capVerdict = policy.judge(finalLeverage, share)

        return TradePrepSizingResult(
            balanceRatio = ratio,
            rawLeverage = rawLeverage,
            rawQuantity = rawQuantity,
            koreaRoundedQuantity = koreaRounded,
            foreignRoundedQuantity = foreignRounded,
            finalQuantity = finalQuantity,
            finalLeverage = finalLeverage,
            capVerdict = capVerdict,
        )
    }

    private fun onePlusPremium(premiumRatePercent: BigDecimal): BigDecimal =
        BigDecimal.ONE.add(premiumRatePercent.divide(HUNDRED, CALCULATION_SCALE, RoundingMode.HALF_UP))

    private fun divide(numerator: BigDecimal, denominator: BigDecimal): BigDecimal =
        numerator.divide(denominator, CALCULATION_SCALE, RoundingMode.HALF_UP)
}

/**
 * 사이징 산출 read model. [TradePrepSizing.size]의 반환값이며 T2(entity)·T5(Facade)가 소비한다.
 *
 * - [rawQuantity] — 반올림 전, 양쪽 100% 투입 시 물량(`Q`).
 * - [koreaRoundedQuantity]/[foreignRoundedQuantity] — 각 거래소 lot/step 으로 내림한 물량.
 * - [finalQuantity] — 두 반올림 물량 중 작은 쪽(D12). 두 leg 가 실제로 체결할 수 있는 공통 수량이다.
 * - [finalLeverage] — [finalQuantity] 기준으로 재계산한 레버리지. [capVerdict]는 이 값을 판정한다.
 */
data class TradePrepSizingResult(
    val balanceRatio: BigDecimal,
    val rawLeverage: BigDecimal,
    val rawQuantity: BigDecimal,
    val koreaRoundedQuantity: BigDecimal,
    val foreignRoundedQuantity: BigDecimal,
    val finalQuantity: BigDecimal,
    val finalLeverage: BigDecimal,
    val capVerdict: CapVerdict,
) {
    /** 반올림 후 물량이 0 이하이거나 캡을 위반하면 계획을 만들지 않는다 (D12). */
    val isPlannable: Boolean get() = finalQuantity > BigDecimal.ZERO && !capVerdict.isViolated
}
