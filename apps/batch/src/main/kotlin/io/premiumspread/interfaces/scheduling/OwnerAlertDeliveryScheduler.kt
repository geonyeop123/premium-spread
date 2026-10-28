package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJob
import io.premiumspread.config.OwnerAlertDeliveryEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 채널이 준비되고 scheduling 이 켜졌을 때만 존재한다. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(OwnerAlertDeliveryEnabledCondition::class)
class OwnerAlertDeliveryScheduler(
    private val job: OwnerAlertDeliveryJob,
    @Suppress("unused") private val scheduling: BatchSchedulingProperties,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.owner-alert-delivery.fixed-rate:15000}")
    fun deliver() {
        job.run()
    }
}
