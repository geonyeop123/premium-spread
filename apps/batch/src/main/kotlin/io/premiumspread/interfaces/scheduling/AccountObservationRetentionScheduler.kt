package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.accountpnl.AccountObservationRetentionJob
import io.premiumspread.config.AccountObservationRetentionEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 관측 보존 정리 trigger (#148). 모니터 on/off 와 무관하며 `retention.enabled`(기본 true)만 본다. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(AccountObservationRetentionEnabledCondition::class)
class AccountObservationRetentionScheduler(private val job: AccountObservationRetentionJob) {
    @Scheduled(fixedDelayString = "\${account-pnl-monitor.position-history.retention.interval:1h}")
    fun purge() {
        job.run()
    }
}
