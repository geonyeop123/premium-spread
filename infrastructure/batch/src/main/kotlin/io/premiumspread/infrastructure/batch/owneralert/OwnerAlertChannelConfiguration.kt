package io.premiumspread.infrastructure.batch.owneralert

import io.premiumspread.domain.accountpnl.AccountPnlMailSender
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertDiscordSender
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.email.EmailSender
import io.premiumspread.infrastructure.batch.notification.AccountPnlEmailSenderAdapter
import io.premiumspread.infrastructure.batch.notification.DiscordWebhookSenderAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/** OwnerAlertChannelRequirements.ready(env). 로그 없음. */
class OwnerAlertChannelReadyCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        OwnerAlertChannelRequirements.ready(context.environment)
}

/** owner 알림 채널(메일·Discord) sender 와 라우팅. PnL 모니터와 독립이다 (owner-ops-alerts design §9.3). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("\${batch.scheduling.enabled:true} and \${scheduling.enabled:true}")
@Conditional(OwnerAlertChannelReadyCondition::class, OwnerAlertChannelAvailableCondition::class)
@EnableConfigurationProperties(OwnerAlertProperties::class)
class OwnerAlertChannelConfiguration {
    /** owner 메일 채널. 수신 주소는 [OwnerAlertChannelRequirements.recipient] 가 읽는다 (design §7.2 I-3). */
    @Bean
    @ConditionalOnBean(EmailSender::class)
    @Conditional(OwnerAlertEmailRequestedCondition::class)
    fun accountPnlMailSender(emailSender: EmailSender, environment: Environment): AccountPnlMailSender =
        AccountPnlEmailSenderAdapter(emailSender, OwnerAlertChannelRequirements.recipient(environment))

    /** owner Discord 채널. 기동 INFO 는 대상별 `set`/`fallback(…)` 만 남기고 URL 은 남기지 않는다. */
    @Bean
    @Conditional(DiscordWebhookConfiguredCondition::class)
    fun ownerAlertDiscordSender(ownerAlert: OwnerAlertProperties): DiscordWebhookSenderAdapter {
        val webhooks = ownerAlert.discord.webhook.toDiscordWebhooks()
        log.info(
            "owner-alert discord webhooks: pnl={}, market={}, ops={}",
            webhooks.describe(DiscordTarget.PNL),
            webhooks.describe(DiscordTarget.MARKET),
            webhooks.describe(DiscordTarget.OPS),
        )
        return DiscordWebhookSenderAdapter(webhooks)
    }

    /** 채널 플래그를 sender bean 존재에서 만든다 — 라우팅과 sender 가 어긋날 수 없다 (design §7.2). */
    @Bean
    fun ownerAlertRouting(
        ownerAlert: OwnerAlertProperties,
        mailSender: ObjectProvider<AccountPnlMailSender>,
        discordSender: ObjectProvider<OwnerAlertDiscordSender>,
    ): OwnerAlertRouting {
        val routing = OwnerAlertRouting(
            OwnerAlertChannels(email = mailSender.ifAvailable != null, discord = discordSender.ifAvailable != null),
            ownerAlert.resolvedOverrides(),
        )
        log.info("owner-alert routes: {}", describeRoutes(routing))
        return routing
    }

    /** 종류별 해석된 경로 요약. URL·수신 주소는 담지 않는다. */
    private fun describeRoutes(routing: OwnerAlertRouting): String = OwnerAlertKind.entries.joinToString(", ") { kind ->
        val route = routing.resolve(kind)
        val parts = listOfNotNull(
            "email".takeIf { route.email },
            route.discord?.let { "discord(${it.target},${it.push})" },
        )
        "${kind.key}=${parts.joinToString("+").ifEmpty { "off" }}"
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OwnerAlertChannelConfiguration::class.java)
    }
}
