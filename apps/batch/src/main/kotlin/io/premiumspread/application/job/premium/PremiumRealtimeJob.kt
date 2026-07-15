package io.premiumspread.application.job.premium

import io.premiumspread.application.common.JobConfig
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.market.TickerReadPort
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.premium.PremiumRealtimeWritePort
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.premium.PremiumThresholdEvaluator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class PremiumRealtimeJob(
    private val tickerReader: TickerReadPort,
    private val fxRateReader: FxRateReadPort,
    private val premiumWriter: PremiumRealtimeWritePort,
    private val thresholdEvaluator: PremiumThresholdEvaluator,
    private val marketProvider: BatchMarketProvider,
    private val executor: JobExecutor,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.PREMIUM_REALTIME)) {
            calculate()
        }

    private fun calculate(): JobResult {
        return try {
            val market = marketProvider.defaultMarket()
            val bithumbTicker = tickerReader.findLatest(market.pair.koreaExchange, market.koreaQuote)
            val binanceTicker = tickerReader.findLatest(market.pair.foreignExchange, market.foreignQuote)
            val fxSnapshot = fxRateReader.findLatest(market.fxBase, market.fxQuote)

            if (bithumbTicker == null || binanceTicker == null || fxSnapshot == null) {
                log.warn(
                    "Missing data for premium calculation - korea({}): {}, foreign({}): {}, FX: {}",
                    market.pair.koreaExchange,
                    bithumbTicker != null,
                    market.pair.foreignExchange,
                    binanceTicker != null,
                    fxSnapshot != null,
                )
                return JobResult.Skipped("missing_data")
            }

            if (bithumbTicker.price <= BigDecimal.ZERO || binanceTicker.price <= BigDecimal.ZERO ||
                fxSnapshot.rate <= BigDecimal.ZERO
            ) {
                log.warn(
                    "Invalid price detected - korea({}): {}, foreign({}): {}, FX: {}",
                    market.pair.koreaExchange,
                    bithumbTicker.price,
                    market.pair.foreignExchange,
                    binanceTicker.price,
                    fxSnapshot.rate,
                )
                return JobResult.Skipped("invalid_price")
            }

            val calculation = PremiumPolicy.calculate(
                koreaPrice = bithumbTicker.price,
                foreignPriceUsd = binanceTicker.price,
                fxRate = fxSnapshot.rate,
            )
            val premium = PremiumSnapshot(
                pair = market.pair,
                premiumRate = calculation.storagePremiumRate,
                koreaPrice = bithumbTicker.price,
                foreignPrice = binanceTicker.price,
                foreignPriceInKrw = calculation.foreignPriceInKrw,
                fxRate = fxSnapshot.rate,
                fxSource = fxSnapshot.source,
                observedAt = maxOf(bithumbTicker.observedAt, binanceTicker.observedAt, fxSnapshot.observedAt),
                fxObservedAt = fxSnapshot.observedAt,
            )

            premiumWriter.saveCurrent(premium)
            premiumWriter.saveSecond(premium)

            runCatching {
                premiumWriter.saveHistory(premium)
            }.onFailure { e ->
                log.warn("saveHistory failed (non-critical): {}", e.message)
            }

            thresholdEvaluator.evaluate(premium)

            log.debug(
                "Calculated premium: {}% (korea {}: {}, foreign {}: {} = {} {})",
                premium.premiumRate,
                market.pair.koreaExchange,
                premium.koreaPrice,
                market.pair.foreignExchange,
                premium.foreignPrice,
                premium.foreignPriceInKrw,
                market.fxQuote,
            )

            JobResult.Success
        } catch (e: Exception) {
            log.error("Failed to calculate premium", e)
            JobResult.Failure(e)
        }
    }
}
