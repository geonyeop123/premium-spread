package io.premiumspread.config

import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.accountpnl.AccountPnlMonitorJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDispatcher
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.ForeignPerpAccountReadPort
import io.premiumspread.domain.accountpnl.KoreaSpotAccountReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.FxRateReadPort
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.type.ClassMetadata
import java.time.Clock

/**
 * 실계정 순손익 기능이 명시적으로 켜졌고 수신자(메일 채널이 쓰일 때만)·빗썸 credential·바이낸스 모드별 필수값이 모두 있으며
 * owner 알림 채널(메일·Discord)이 하나 이상 준비됐을 때만 참이다 (design §7.2 표 순서 1~7).
 *
 * 앱 구성 클래스는 auto-configuration 보다 먼저 평가되므로 adapter bean 존재(`@ConditionalOnBean`)를 조건으로
 * 쓸 수 없다. 그래서 infrastructure(`OwnerAlertChannelRequirements`·`AccountPnlMonitorRequirements`)와 같은 환경 설정
 * 키·규칙을 직접 읽는다 (app 은 infrastructure 타입을 import 하지 않는다). 두 쪽의 일치는 `AccountPnlMonitorWiringTest` 의
 * 모드 × 누락 키 행렬과 채널 조합 행렬이 고정한다. 로그에는 해석된 모드와 env 이름만 남기고 설정 값은 남기지 않는다.
 */
class AccountPnlMonitorEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        // 1
        if (!env.getProperty("account-pnl-monitor.enabled").equals("true", ignoreCase = true)) return false
        val logging = (metadata as? ClassMetadata)?.className == AccountPnlMonitorConfiguration::class.java.name
        // 2
        val manualMode = isManualMode(env.getProperty(MODE_KEY))

        val emailRequested = env.getProperty(OWNER_EMAIL_ENABLED_KEY)?.trim().orEmpty()
            .let { it.isEmpty() || it.equals("true", ignoreCase = true) }
        // SMTP 플래그(notification.email.enabled)에만 해당하는 규칙: EmailAutoConfiguration 의
        // @ConditionalOnProperty(havingValue = "true") 와 같다(trim 없음).
        val smtpOn = env.getProperty(SMTP_ENABLED_KEY).equals("true", ignoreCase = true)
        val emailEffective = emailRequested && smtpOn
        val discordRequested = env.getProperty(DISCORD_ENABLED_KEY)?.trim().equals("true", ignoreCase = true)
        val webhookConfigured = WEBHOOKS.any { (key, _) -> !env.getProperty(key).isNullOrBlank() }
        val discordEffective = discordRequested && webhookConfigured

        // 3
        val required = (if (emailEffective) listOf(RECIPIENT) else emptyList()) + COMMON + if (manualMode) MANUAL else API
        val missing = required.filter { (key, _) -> env.getProperty(key).isNullOrBlank() }.map { (_, envName) -> envName }
        if (missing.isNotEmpty()) {
            if (logging) {
                log.warn(
                    "account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode={}, missing={}",
                    if (manualMode) "manual" else "api",
                    missing,
                )
            }
            return false
        }
        // 4
        if (discordRequested && !webhookConfigured) {
            if (logging) {
                log.warn(
                    "owner-alert discord enabled but no webhook configured; monitor not started: set one of {}",
                    WEBHOOKS.map { (_, envName) -> envName },
                )
            }
            return false
        }
        // 5
        if (!emailEffective && !discordEffective) {
            if (logging) log.warn("account-pnl-monitor enabled but email disabled; monitor not started")
            return false
        }
        // 6
        if (emailRequested && !smtpOn && discordEffective && logging) {
            log.warn("owner-alert email enabled but NOTIFICATION_EMAIL_ENABLED is not true; email channel off")
        }
        // 7
        return true
    }

    private companion object {
        val log = LoggerFactory.getLogger(AccountPnlMonitorEnabledCondition::class.java)
        const val MODE_KEY = "account-pnl-monitor.binance.mode"
        const val INVALID_MODE_MESSAGE = "account-pnl-monitor.binance.mode must be api or manual"

        /** emailEffective 일 때만 필수 (= infra `OwnerAlertChannelRequirements.recipientRequired`). */
        val RECIPIENT = "account-pnl-monitor.recipient" to "ACCOUNT_PNL_ALERT_RECIPIENT"
        val COMMON = listOf(
            "account-pnl-monitor.bithumb.access-key" to "BITHUMB_ACCESS_KEY",
            "account-pnl-monitor.bithumb.secret-key" to "BITHUMB_SECRET_KEY",
        )
        val API = listOf(
            "account-pnl-monitor.binance.api-key" to "BINANCE_API_KEY",
            "account-pnl-monitor.binance.secret-key" to "BINANCE_SECRET_KEY",
        )
        val MANUAL = listOf(
            "account-pnl-monitor.binance.manual.short-quantity" to "BINANCE_MANUAL_SHORT_QUANTITY",
            "account-pnl-monitor.binance.manual.entry-price" to "BINANCE_MANUAL_ENTRY_PRICE",
        )

        const val OWNER_EMAIL_ENABLED_KEY = "owner-alert.email.enabled"
        const val SMTP_ENABLED_KEY = "notification.email.enabled"
        const val DISCORD_ENABLED_KEY = "owner-alert.discord.enabled"
        val WEBHOOKS = listOf(
            "owner-alert.discord.webhook.pnl" to "OWNER_ALERT_DISCORD_WEBHOOK_PNL",
            "owner-alert.discord.webhook.market" to "OWNER_ALERT_DISCORD_WEBHOOK_MARKET",
            "owner-alert.discord.webhook.ops" to "OWNER_ALERT_DISCORD_WEBHOOK_OPS",
        )

        /** infra `BinanceLegMode` 해석과 같은 규칙: 공백/없음 → api, api/manual 대소문자 무관, 그 밖 → 값 없는 예외. */
        fun isManualMode(raw: String?): Boolean {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed.isEmpty() || trimmed.equals("api", ignoreCase = true) -> false
                trimmed.equals("manual", ignoreCase = true) -> true
                else -> error(INVALID_MODE_MESSAGE)
            }
        }
    }
}

/** scheduling 이 꺼지면(`batch.scheduling.enabled=false`) Job 도 만들지 않는다 (dod AC11). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@ConditionalOnProperty(prefix = "account-pnl-monitor", name = ["enabled"], havingValue = "true")
@Conditional(AccountPnlMonitorEnabledCondition::class)
class AccountPnlMonitorConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun accountPnlMonitorJob(
        korea: KoreaSpotAccountReadPort,
        foreign: ForeignPerpAccountReadPort,
        fx: FxRateReadPort,
        lastSuccess: JobLastSuccessReadPort,
        store: AccountPnlAlertStore,
        mailSender: ObjectProvider<AccountPnlMailSender>,
        executor: JobExecutor,
        clock: Clock,
        jobConfigs: JobConfigProvider,
        @Value("\${account-pnl-monitor.hourly-report.enabled:true}") hourlyReportEnabled: Boolean,
        routing: OwnerAlertRouting,
        discordSender: ObjectProvider<OwnerAlertDiscordSender>,
    ): AccountPnlMonitorJob = AccountPnlMonitorJob(
        korea,
        foreign,
        fx,
        lastSuccess,
        store,
        mailSender.ifAvailable ?: OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER,
        executor,
        clock,
        jobConfigs = jobConfigs,
        hourlyReportEnabled = hourlyReportEnabled,
        routing = routing,
        discordSender = discordSender.ifAvailable,
    )
}
