package io.premiumspread.infrastructure.batch.owneralert

import io.premiumspread.email.EmailSender
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.core.env.PropertyResolver
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * owner 알림 채널 판정과 메일 채널 필수 키·수신 주소의 단일 소유자 (design §7.2, 판정 I-3).
 * app `AccountPnlMonitorEnabledCondition` 이 같은 규칙을 따로 갖는다. 판정은 값이 비었는지만 읽고, [recipient] 만 값을 돌려준다(로그 금지).
 */
internal object OwnerAlertChannelRequirements {
    const val EMAIL_ENABLED_KEY = "owner-alert.email.enabled"
    const val SMTP_ENABLED_KEY = "notification.email.enabled"
    const val DISCORD_ENABLED_KEY = "owner-alert.discord.enabled"

    /** 수신 주소 key. env ACCOUNT_PNL_ALERT_RECIPIENT 는 application.yml 이 연결한다 — key·env 는 호환 때문에 유지. */
    const val RECIPIENT_KEY = "account-pnl-monitor.recipient"
    val WEBHOOK_KEYS = listOf(
        "owner-alert.discord.webhook.pnl",
        "owner-alert.discord.webhook.market",
        "owner-alert.discord.webhook.ops",
    )

    /** 비었거나 없으면 true(기본 켜짐), 아니면 equalsIgnoreCase("true"). */
    fun emailRequested(env: PropertyResolver): Boolean {
        val raw = env.getProperty(EMAIL_ENABLED_KEY)?.trim().orEmpty()
        return raw.isEmpty() || raw.equals("true", ignoreCase = true)
    }

    /**
     * 메일 요청 ∧ SMTP 켜짐. SMTP 는 trim 없이 equalsIgnoreCase("true") — EmailAutoConfiguration 의
     * `@ConditionalOnProperty(havingValue = "true")` 와 같은 규칙이라야 EmailSender bean 존재와 어긋나지 않는다.
     */
    fun emailEffective(env: PropertyResolver): Boolean =
        emailRequested(env) && env.getProperty(SMTP_ENABLED_KEY).equals("true", ignoreCase = true)

    /** trim 후 equalsIgnoreCase("true") — OwnerAlertProperties.Discord.resolvedEnabled 와 같은 규칙. */
    fun discordRequested(env: PropertyResolver): Boolean =
        env.getProperty(DISCORD_ENABLED_KEY)?.trim().equals("true", ignoreCase = true)

    /** webhook key 중 공백 아닌 값이 하나 이상. 형식은 보지 않는다 — 형식 검증은 OwnerAlertProperties 바인딩이 한다. */
    fun webhookConfigured(env: PropertyResolver): Boolean = WEBHOOK_KEYS.any { !env.getProperty(it).isNullOrBlank() }

    fun discordEffective(env: PropertyResolver): Boolean = discordRequested(env) && webhookConfigured(env)

    fun discordMisconfigured(env: PropertyResolver): Boolean = discordRequested(env) && !webhookConfigured(env)

    fun anyChannel(env: PropertyResolver): Boolean = emailEffective(env) || discordEffective(env)

    fun recipientRequired(env: PropertyResolver): Boolean = emailEffective(env)

    /** 채널 쪽 필수 키 중 비어 있는 key — recipientRequired ∧ RECIPIENT_KEY 공백이면 [RECIPIENT_KEY], 아니면 []. */
    fun missingKeys(env: PropertyResolver): List<String> =
        if (recipientRequired(env) && env.getProperty(RECIPIENT_KEY).isNullOrBlank()) listOf(RECIPIENT_KEY) else emptyList()

    /** missingKeys.isEmpty() && !discordMisconfigured && anyChannel — #90 OwnerAlertChannelConfiguration 의 조건. */
    fun ready(env: PropertyResolver): Boolean = missingKeys(env).isEmpty() && !discordMisconfigured(env) && anyChannel(env)

    /** 메일 sender 생성용 수신 주소(원문). 공백이면 값 없는 IllegalStateException. */
    fun recipient(env: PropertyResolver): String {
        val value = env.getProperty(RECIPIENT_KEY)
        check(!value.isNullOrBlank()) { "$RECIPIENT_KEY is required when the owner email channel is on" }
        return value
    }
}

/** owner 메일 채널이 실제로 쓰일 때(emailEffective) 참이다. */
class OwnerAlertEmailRequestedCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        OwnerAlertChannelRequirements.emailEffective(context.environment)
}

/** Discord 가 켜졌고 webhook 이 하나 이상 있을 때(discordEffective) 참이다. */
class DiscordWebhookConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        OwnerAlertChannelRequirements.discordEffective(context.environment)
}

/** [EmailSender bean ∧ emailEffective] ∨ [discordEffective]. */
class OwnerAlertChannelAvailableCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnBean(EmailSender::class)
    @Conditional(OwnerAlertEmailRequestedCondition::class)
    class EmailChannel

    @Conditional(DiscordWebhookConfiguredCondition::class)
    class DiscordChannel
}
