package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.ownermarket.OwnerMarketSignalJob
import io.premiumspread.config.OwnerMarketAlertEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 채널이 준비되고 시장 알림이 켜졌을 때만 존재한다. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(OwnerMarketAlertEnabledCondition::class)
class OwnerMarketSignalScheduler(
    private val job: OwnerMarketSignalJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-market-signal.fixed-rate:30000}")
    fun evaluate() {
        job.run()
    }
}
