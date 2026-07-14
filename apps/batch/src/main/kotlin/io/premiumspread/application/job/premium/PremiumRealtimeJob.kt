package io.premiumspread.application.job.premium

import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.PremiumUpdatedEvent
import io.premiumspread.cache.FxCacheService
import io.premiumspread.cache.PremiumCacheService
import io.premiumspread.cache.TickerCacheService
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.ticker.Symbol
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class PremiumRealtimeJob(
    private val tickerCacheService: TickerCacheService,
    private val fxCacheService: FxCacheService,
    private val premiumCacheService: PremiumCacheService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BITHUMB = "bithumb"
        private const val BINANCE = "binance"
        private const val BTC = "btc"
    }

    fun run(): JobResult {
        return try {
            val bithumbTicker = tickerCacheService.getSnapshot(BITHUMB, BTC)
            val binanceTicker = tickerCacheService.getSnapshot(BINANCE, BTC)
            val fxSnapshot = fxCacheService.getUsdKrwSnapshot()

            if (bithumbTicker == null || binanceTicker == null || fxSnapshot == null) {
                log.warn(
                    "Missing data for premium calculation - Bithumb: {}, Binance: {}, FX: {}",
                    bithumbTicker != null,
                    binanceTicker != null,
                    fxSnapshot != null,
                )
                return JobResult.Skipped("missing_data")
            }

            if (bithumbTicker.price <= BigDecimal.ZERO || binanceTicker.price <= BigDecimal.ZERO || fxSnapshot.rate <= BigDecimal.ZERO) {
                log.warn(
                    "Invalid price detected - Bithumb: {}, Binance: {}, FX: {}",
                    bithumbTicker.price,
                    binanceTicker.price,
                    fxSnapshot.rate,
                )
                return JobResult.Skipped("invalid_price")
            }

            val pair = MarketPair.default(Symbol(bithumbTicker.symbol))

            val calculation = PremiumPolicy.calculate(
                koreaPrice = bithumbTicker.price,
                foreignPriceUsd = binanceTicker.price,
                fxRate = fxSnapshot.rate,
            )
            val premium = PremiumSnapshot(
                pair = pair,
                premiumRate = calculation.storagePremiumRate,
                koreaPrice = bithumbTicker.price,
                foreignPrice = binanceTicker.price,
                foreignPriceInKrw = calculation.foreignPriceInKrw,
                fxRate = fxSnapshot.rate,
                fxSource = fxSnapshot.source,
                observedAt = maxOf(bithumbTicker.observedAt, binanceTicker.observedAt, fxSnapshot.observedAt),
                fxObservedAt = fxSnapshot.observedAt,
            )

            premiumCacheService.save(premium)
            premiumCacheService.saveToSeconds(premium)

            runCatching {
                premiumCacheService.saveHistory(premium)
            }.onFailure { e ->
                log.warn("saveHistory failed (non-critical): {}", e.message)
            }

            eventPublisher.publishEvent(
                PremiumUpdatedEvent(symbol = premium.symbol, premiumRate = premium.premiumRate),
            )

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
