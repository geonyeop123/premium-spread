package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.fx.FxIngestionJob
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnBatchScheduling
class ExchangeRateScheduler(
    private val job: FxIngestionJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRateString = "\${batch.scheduling.exchange-rate.fixed-rate:1800000}")
    fun fetchExchangeRate() {
        job.run()
    }

    @Scheduled(
        initialDelayString = "\${batch.scheduling.exchange-rate.startup-delay:5000}",
        fixedDelay = Long.MAX_VALUE,
    )
    fun fetchExchangeRateOnStartup() {
        log.info("Fetching initial exchange rate...")
        job.run()
    }
}
