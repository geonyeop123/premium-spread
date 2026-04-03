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
        private const val INTERMEDIATE_SCALE = 10  // 중간 나눗셈 정밀도 (domain Premium.kt DIVISION_SCALE과 동일)
        private const val STORAGE_SCALE = 4        // Redis/DB 저장 정밀도
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
        require(koreaTicker.price > BigDecimal.ZERO) { "Korea ticker price must be positive: ${koreaTicker.price}" }
        require(foreignTicker.price > BigDecimal.ZERO) { "Foreign ticker price must be positive: ${foreignTicker.price}" }
        require(fxRate > BigDecimal.ZERO) { "FX rate must be positive: $fxRate" }

        // 해외 가격을 원화로 환산 (소수점 유지 — 중간 정수 반올림 제거)
        val foreignPriceInKrw = foreignTicker.price.multiply(fxRate)

        // 프리미엄율 계산: ((한국가 - 해외가원화) / 해외가원화) * 100
        val priceDiff = koreaTicker.price.subtract(foreignPriceInKrw)
        val premiumRate = priceDiff
            .divide(foreignPriceInKrw, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(STORAGE_SCALE, RoundingMode.HALF_UP)

        return PremiumCacheData(
            symbol = koreaTicker.symbol,
            premiumRate = premiumRate,
            koreaPrice = koreaTicker.price,
            foreignPrice = foreignTicker.price,
            foreignPriceInKrw = foreignPriceInKrw.setScale(0, RoundingMode.HALF_UP), // 표시용 정수 반올림
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
        require(koreaPrice > BigDecimal.ZERO) { "Korea price must be positive: $koreaPrice" }
        require(foreignPrice > BigDecimal.ZERO) { "Foreign price must be positive: $foreignPrice" }
        require(fxRate > BigDecimal.ZERO) { "FX rate must be positive: $fxRate" }

        val foreignPriceInKrw = foreignPrice.multiply(fxRate)
        val priceDiff = koreaPrice.subtract(foreignPriceInKrw)

        return priceDiff
            .divide(foreignPriceInKrw, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(STORAGE_SCALE, RoundingMode.HALF_UP)
    }
}
