package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.config.AccountPnlMonitorEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 기능이 켜지고 scheduling 이 활성일 때만 존재한다. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(AccountPnlMonitorEnabledCondition::class)
class AccountPnlMonitorScheduler(
    private val job: AccountPnlMonitorJob,
) {
    @Scheduled(fixedRateString = "\${batch.scheduling.account-pnl-monitor.fixed-rate:60000}")
    fun monitor() {
        job.run()
    }
}
