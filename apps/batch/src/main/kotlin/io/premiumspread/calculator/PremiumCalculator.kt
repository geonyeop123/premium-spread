package io.premiumspread.calculator

import io.premiumspread.cache.PremiumCacheData
import io.premiumspread.client.TickerData
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class PremiumCalculator {

    companion object {
        private val HUNDRED = BigDecimal("100")
        private const val SCALE = 4
    }

    /**
     * 김치 프리미엄 계산
     *
     * 프리미엄율 = ((한국가격 - 해외가격*환율) / (해외가격*환율)) * 100
     *
     * @param koreaTicker 한국 거래소 티커 (KRW 기준)
     * @param foreignTicker 해외 거래소 티커 (USD 기준)
     * @param fxRate USD/KRW 환율
     * @return 프리미엄 데이터
     */
    fun calculate(
        koreaTicker: TickerData,
        foreignTicker: TickerData,
        fxRate: BigDecimal,
    ): PremiumCacheData {
        // 해외 가격을 원화로 환산
        val foreignPriceInKrw = foreignTicker.price.multiply(fxRate)
            .setScale(0, RoundingMode.HALF_UP)

        // 프리미엄율 계산: ((한국가 - 해외가원화) / 해외가원화) * 100
        val priceDiff = koreaTicker.price.subtract(foreignPriceInKrw)
        val premiumRate = priceDiff
            .divide(foreignPriceInKrw, SCALE + 2, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(SCALE, RoundingMode.HALF_UP)

        return PremiumCacheData(
            symbol = koreaTicker.symbol,
            premiumRate = premiumRate,
            koreaPrice = koreaTicker.price,
            foreignPrice = foreignTicker.price,
            foreignPriceInKrw = foreignPriceInKrw,
            fxRate = fxRate,
            observedAt = maxOf(koreaTicker.timestamp, foreignTicker.timestamp),
        )
    }

    /**
     * 프리미엄율만 계산 (간단 버전)
     */
    fun calculateRate(
        koreaPrice: BigDecimal,
        foreignPrice: BigDecimal,
        fxRate: BigDecimal,
    ): BigDecimal {
        val foreignPriceInKrw = foreignPrice.multiply(fxRate)
        val priceDiff = koreaPrice.subtract(foreignPriceInKrw)

        return priceDiff
            .divide(foreignPriceInKrw, SCALE + 2, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(SCALE, RoundingMode.HALF_UP)
    }
}
