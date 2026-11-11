package io.premiumspread.interfaces.scheduling

import io.premiumspread.application.job.accountpnl.AccountRealizedPnlJob
import io.premiumspread.config.AccountPnlMonitorEnabledCondition
import io.premiumspread.config.RealizedPnlEnabledCondition
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 모니터와 같은 조건에 `realized-pnl.enabled`(기본 true)가 더해졌을 때만 존재한다. Job 은 한 번만 호출한다. */
@Component
@ConditionalOnBatchScheduling
@Conditional(AccountPnlMonitorEnabledCondition::class, RealizedPnlEnabledCondition::class)
class AccountRealizedPnlScheduler(private val job: AccountRealizedPnlJob) {
    @Scheduled(fixedRateString = "\${batch.scheduling.account-realized-pnl.fixed-rate:300000}")
    fun settle() {
        job.run()
    }
}
