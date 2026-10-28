package io.premiumspread.application.notification.owneralert

import io.premiumspread.application.common.DefaultJobConfigProvider
import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** owner 알림 outbox 독립 전달 1회분. PnL 모니터와 무관하게 돈다 (owner-ops-alerts design §7). */
class OwnerAlertDeliveryJob(
    private val store: AccountPnlAlertStore,
    mailSender: AccountPnlMailSender,
    discordSender: OwnerAlertDiscordSender?,
    private val executor: JobExecutor,
    private val clock: Clock,
    private val jobConfigs: JobConfigProvider = DefaultJobConfigProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dispatcher =
        OwnerAlertDispatcher(store, mailSender, discordSender, clock, OWNER_PREFIX + UUID.randomUUID())

    fun run(): JobResult = executor.execute(jobConfigs.get(JobId.OWNER_ALERT_DELIVERY)) { deliver() }

    private fun deliver(): JobResult {
        try {
            val recovered = store.recoverStale(clock.instant(), STALE_CLAIM_AFTER)
            if (recovered > 0) log.warn("Recovered {} stale owner alert claims", recovered)
        } catch (exception: Exception) {
            log.error("Owner alert store failed during recoverStale", exception)
            return JobResult.Failure(exception)
        }
        return dispatcher.dispatch()
    }

    companion object {
        const val OWNER_PREFIX = "owner-alert-delivery:"
        val STALE_CLAIM_AFTER: Duration = Duration.ofMinutes(5)
    }
}
