package io.premiumspread.domain.premium

import java.math.BigDecimal
import java.math.RoundingMode

object PremiumPolicy {
    const val CALCULATION_SCALE = 10
    const val STORAGE_SCALE = 4
    const val ENTITY_SCALE = 2
    const val API_DISPLAY_SCALE = 2
    private val HUNDRED = BigDecimal("100")

    fun calculate(
        koreaPrice: BigDecimal,
        foreignPriceUsd: BigDecimal,
        fxRate: BigDecimal,
    ): PremiumCalculation {
        require(koreaPrice > BigDecimal.ZERO) { "Korea price must be positive: $koreaPrice" }
        require(foreignPriceUsd > BigDecimal.ZERO) { "Foreign price must be positive: $foreignPriceUsd" }
        require(fxRate > BigDecimal.ZERO) { "FX rate must be positive: $fxRate" }

        val foreignPriceInKrw = foreignPriceUsd.multiply(fxRate)
        val calculatedRate = koreaPrice.subtract(foreignPriceInKrw)
            .divide(foreignPriceInKrw, CALCULATION_SCALE, RoundingMode.HALF_UP)
            .multiply(HUNDRED)

        return PremiumCalculation(
            foreignPriceInKrw = foreignPriceInKrw,
            storagePremiumRate = calculatedRate.setScale(STORAGE_SCALE, RoundingMode.HALF_UP),
            entityPremiumRate = normalizeEntity(calculatedRate),
            apiDisplayPremiumRate = normalizeDisplay(calculatedRate),
        )
    }

    fun normalizeEntity(premiumRate: BigDecimal): BigDecimal =
        premiumRate.setScale(ENTITY_SCALE, RoundingMode.HALF_UP)

    fun normalizeDisplay(premiumRate: BigDecimal): BigDecimal =
        premiumRate.setScale(API_DISPLAY_SCALE, RoundingMode.HALF_UP)

    fun calculateRate(
        koreaPrice: BigDecimal,
        foreignPriceUsd: BigDecimal,
        fxRate: BigDecimal,
    ): BigDecimal = calculate(koreaPrice, foreignPriceUsd, fxRate).storagePremiumRate
}

data class PremiumCalculation(
    val foreignPriceInKrw: BigDecimal,
    val storagePremiumRate: BigDecimal,
    val entityPremiumRate: BigDecimal,
    val apiDisplayPremiumRate: BigDecimal,
)
