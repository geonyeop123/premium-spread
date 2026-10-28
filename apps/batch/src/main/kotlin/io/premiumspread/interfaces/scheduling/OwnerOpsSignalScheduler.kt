package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.ownerops.OwnerOpsSignalJob
import io.premiumspread.config.OwnerOpsAlertEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 채널이 준비되고 ops 알림이 켜졌을 때만 존재한다. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(OwnerOpsAlertEnabledCondition::class)
class OwnerOpsSignalScheduler(
    private val job: OwnerOpsSignalJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-ops-signal.fixed-rate:30000}")
    fun evaluate() {
        job.run()
    }
}
