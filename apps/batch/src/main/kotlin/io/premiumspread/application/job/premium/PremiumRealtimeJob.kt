package io.premiumspread.application.job.premium

import io.premiumspread.application.common.JobResult
import io.premiumspread.cache.FxCacheService
import io.premiumspread.cache.PositionCacheService
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.calculator.PremiumCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class PremiumRealtimeJob(
    private val tickerCacheService: TickerCacheService,
    private val fxCacheService: FxCacheService,
    private val premiumCacheService: PremiumCacheService,
    private val positionCacheService: PositionCacheService,
    private val premiumCalculator: PremiumCalculator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BITHUMB = "bithumb"
        private const val BINANCE = "binance"
        private const val BTC = "btc"
    }

    fun run(): JobResult {
        return try {
            val bithumbTicker = tickerCacheService.get(BITHUMB, BTC)
            val binanceTicker = tickerCacheService.get(BINANCE, BTC)
            val fxRate = fxCacheService.getUsdKrw()

            if (bithumbTicker == null || binanceTicker == null || fxRate == null) {
                log.warn(
                    "Missing data for premium calculation - Bithumb: {}, Binance: {}, FX: {}",
                    bithumbTicker != null,
                    binanceTicker != null,
                    fxRate != null,
                )
                return JobResult.Skipped("missing_data")
            }

            if (bithumbTicker.price <= BigDecimal.ZERO || binanceTicker.price <= BigDecimal.ZERO || fxRate <= BigDecimal.ZERO) {
                log.warn(
                    "Invalid price detected - Bithumb: {}, Binance: {}, FX: {}",
                    bithumbTicker.price,
                    binanceTicker.price,
                    fxRate,
                )
                return JobResult.Skipped("invalid_price")
            }

            val premium = premiumCalculator.calculate(
                koreaTicker = bithumbTicker,
                foreignTicker = binanceTicker,
                fxRate = fxRate,
            )

            premiumCacheService.save(premium)
            premiumCacheService.saveToSeconds(premium)

            // TODO(refactor): 포지션 기능 분리 시 이 조건부 히스토리 저장 로직 제거 검토
            if (positionCacheService.hasOpenPosition()) {
                premiumCacheService.saveHistory(premium)
                log.debug("Saved premium history for open positions")
            }

            log.debug(
                "Calculated premium: {}% (Korea: {} KRW, Foreign: {} USDT = {} KRW)",
                premium.premiumRate,
                premium.koreaPrice,
                premium.foreignPrice,
                premium.foreignPriceInKrw,
            )

            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to calculate premium", e)
            JobResult.Failure(e)
        }
    }
}
