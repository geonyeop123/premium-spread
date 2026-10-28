package io.premiumspread.config

import io.premiumspread.application.common.JobConfigProvider
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.job.ownerops.OwnerOpsSignalJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDeliveryJob
import io.premiumspread.application.notification.owneralert.OwnerAlertDispatcher
import io.premiumspread.domain.accountpnl.AccountPnlAlertStore
import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.accountpnl.OwnerAlertOutbox
import io.premiumspread.domain.batch.BatchMarketProvider
import io.premiumspread.domain.job.JobFailureStreakReadPort
import io.premiumspread.domain.job.JobLastSuccessReadPort
import io.premiumspread.domain.market.LatestMarketTickReadPort
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.PropertyResolver
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.type.ClassMetadata
import java.time.Clock
import java.time.ZoneId

/**
 * owner 알림 채널을 쓸 수 있을 때(design §9.2 `ready`)만 참이다. infra `OwnerAlertChannelRequirements.ready` 의 app 사본이다 —
 * app 은 infrastructure 타입을 import 하지 않는다. 두 쪽의 일치는 `OwnerAlertDeliveryWiringTest` 의 48조합 표가 고정한다.
 * 로그는 [OwnerAlertDeliveryConfiguration] 평가 때만, env 이름과 boolean 만 남기고 값은 남기지 않는다.
 */
class OwnerAlertDeliveryEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val logging = (metadata as? ClassMetadata)?.className == OwnerAlertDeliveryConfiguration::class.java.name
        return OwnerAlertReadiness.ready(context.environment, logging)
    }
}

/**
 * [OwnerAlertDeliveryEnabledCondition] ∧ `owner-alert.ops.enabled`(빈 값 = true).
 * 꺼져 있으면 [OwnerOpsAlertConfiguration] 평가 때 INFO.
 */
class OwnerOpsAlertEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        if (!OwnerAlertReadiness.ready(env, logging = false)) return false
        val raw = env.getProperty(OPS_ENABLED_KEY)?.trim().orEmpty()
        val enabled = raw.isEmpty() || raw.equals("true", ignoreCase = true)
        val logging = (metadata as? ClassMetadata)?.className == OwnerOpsAlertConfiguration::class.java.name
        if (!enabled && logging) log.info("owner-alert ops alerts disabled by OWNER_ALERT_OPS_ENABLED")
        return enabled
    }

    private companion object {
        const val OPS_ENABLED_KEY = "owner-alert.ops.enabled"
        val log = LoggerFactory.getLogger(OwnerOpsAlertEnabledCondition::class.java)
    }
}

private object OwnerAlertReadiness {
    private const val EMAIL_ENABLED_KEY = "owner-alert.email.enabled"
    private const val SMTP_ENABLED_KEY = "notification.email.enabled"
    private const val DISCORD_ENABLED_KEY = "owner-alert.discord.enabled"
    private const val RECIPIENT_KEY = "account-pnl-monitor.recipient"
    private const val RECIPIENT_ENV = "ACCOUNT_PNL_ALERT_RECIPIENT"
    private val WEBHOOK_KEYS = listOf(
        "owner-alert.discord.webhook.pnl",
        "owner-alert.discord.webhook.market",
        "owner-alert.discord.webhook.ops",
    )
    private val log = LoggerFactory.getLogger(OwnerAlertDeliveryEnabledCondition::class.java)

    fun ready(env: PropertyResolver, logging: Boolean): Boolean {
        val emailRequested = env.getProperty(EMAIL_ENABLED_KEY)?.trim().orEmpty()
            .let { it.isEmpty() || it.equals("true", ignoreCase = true) }
        // SMTP 플래그는 trim 없이 비교한다 — EmailAutoConfiguration 의 havingValue = "true" 와 같은 규칙.
        val emailEffective = emailRequested && env.getProperty(SMTP_ENABLED_KEY).equals("true", ignoreCase = true)
        val discordRequested = env.getProperty(DISCORD_ENABLED_KEY)?.trim().equals("true", ignoreCase = true)
        val webhookConfigured = WEBHOOK_KEYS.any { !env.getProperty(it).isNullOrBlank() }
        val discordEffective = discordRequested && webhookConfigured
        val recipientMissing = emailEffective && env.getProperty(RECIPIENT_KEY).isNullOrBlank()
        val discordMisconfigured = discordRequested && !webhookConfigured

        val ready = !recipientMissing && !discordMisconfigured && (emailEffective || discordEffective)
        if (!ready && logging) {
            val missing = if (recipientMissing) listOf(RECIPIENT_ENV) else emptyList()
            when {
                discordMisconfigured -> log.warn(
                    "owner-alert channel not ready; owner alert delivery and ops alerts not started: " +
                        "missing={}, discordWebhookMissing=true",
                    missing,
                )
                recipientMissing -> log.info(
                    "owner-alert email channel has no owner recipient; " +
                        "owner alert delivery and ops alerts not started: missing={}",
                    missing,
                )
            }
        }
        return ready
    }
}

/** PnL 모니터와 독립으로 owner 알림 outbox 를 전달한다 (design §9.4). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(OwnerAlertDeliveryEnabledCondition::class)
class OwnerAlertDeliveryConfiguration {
    /** 메일 sender bean 이 없으면 [OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER] 를 넘긴다. */
    @Bean
    fun ownerAlertDeliveryJob(
        store: AccountPnlAlertStore,
        mailSender: ObjectProvider<AccountPnlMailSender>,
        discordSender: ObjectProvider<OwnerAlertDiscordSender>,
        executor: JobExecutor,
        clock: Clock,
        jobConfigs: JobConfigProvider,
    ): OwnerAlertDeliveryJob = OwnerAlertDeliveryJob(
        store,
        mailSender.ifAvailable ?: OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER,
        discordSender.ifAvailable,
        executor,
        clock,
        jobConfigs,
    )
}

/** 환율·시세·Job 연속 실패 운영 신호를 outbox 에 쌓는다 (design §8). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBatchScheduling
@Conditional(OwnerOpsAlertEnabledCondition::class)
@EnableConfigurationProperties(OwnerOpsAlertProperties::class)
class OwnerOpsAlertConfiguration {
    @Bean
    @Suppress("LongParameterList")
    fun ownerOpsSignalJob(
        lastSuccess: JobLastSuccessReadPort,
        failureStreaks: JobFailureStreakReadPort,
        ticks: LatestMarketTickReadPort,
        market: BatchMarketProvider,
        outbox: OwnerAlertOutbox,
        routing: OwnerAlertRouting,
        executor: JobExecutor,
        clock: Clock,
        properties: OwnerOpsAlertProperties,
        @Value("\${aggregation.zone:Asia/Seoul}") zone: String,
        jobConfigs: JobConfigProvider,
    ): OwnerOpsSignalJob = OwnerOpsSignalJob(
        lastSuccess,
        failureStreaks,
        ticks,
        market,
        outbox,
        routing,
        executor,
        clock,
        properties.toSettings(),
        ZoneId.of(zone),
        jobConfigs,
    )
}
