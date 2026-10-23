package io.premiumspread.infrastructure.batch.owneralert

import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouteOverride
import io.premiumspread.infrastructure.batch.notification.DiscordWebhooks
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.net.URISyntaxException

/**
 * owner 알림 채널 설정. 모든 값은 `String?` 원문으로 받아 여기서 해석한다 — Spring 변환기의 실패 메시지·원인 체인에
 * 입력 값(특히 webhook token)이 실리지 않게. 해석 실패 예외는 property key 와 규칙만 담고 cause 를 연결하지 않는다.
 * webhook 형식은 discord.enabled 와 무관하게 검증한다 (design §7.3 검증 시점 결정).
 * 수신 주소는 여기 없다 — OwnerAlertChannelRequirements.recipient 가 읽는다 (design §7.2 I-3).
 */
@ConfigurationProperties(prefix = "owner-alert")
data class OwnerAlertProperties(
    val email: Email = Email(),
    val discord: Discord = Discord(),
    val routes: Map<String, RouteOverride> = emptyMap(),
) {
    private val parsedOverrides: Map<OwnerAlertKind, OwnerAlertRouteOverride> = parseOverrides(routes)

    fun resolvedOverrides(): Map<OwnerAlertKind, OwnerAlertRouteOverride> = parsedOverrides

    data class Email(val enabled: String? = null) {
        /** 비었으면 true, 아니면 equalsIgnoreCase("true") — 조건(OwnerAlertChannelRequirements)과 같은 규칙. */
        fun resolvedEnabled(): Boolean {
            val trimmed = enabled?.trim().orEmpty()
            return trimmed.isEmpty() || trimmed.equals("true", ignoreCase = true)
        }
    }

    data class Discord(val enabled: String? = null, val webhook: Webhook = Webhook()) {
        /** equalsIgnoreCase("true"). */
        fun resolvedEnabled(): Boolean = enabled?.trim().equals("true", ignoreCase = true)
    }

    data class Webhook(val pnl: String? = null, val market: String? = null, val ops: String? = null) {
        private val parsed: Map<DiscordTarget, URI> = buildMap {
            parseWebhook(pnl, "pnl")?.let { put(DiscordTarget.PNL, it) }
            parseWebhook(market, "market")?.let { put(DiscordTarget.MARKET, it) }
            parseWebhook(ops, "ops")?.let { put(DiscordTarget.OPS, it) }
        }

        fun isAnyConfigured(): Boolean = parsed.isNotEmpty()

        /** 하나도 없으면 IllegalStateException("owner-alert.discord.webhook requires at least one URL") */
        fun toDiscordWebhooks(): DiscordWebhooks {
            check(parsed.isNotEmpty()) { "owner-alert.discord.webhook requires at least one URL" }
            return DiscordWebhooks(parsed[DiscordTarget.PNL], parsed[DiscordTarget.MARKET], parsed[DiscordTarget.OPS])
        }

        override fun toString(): String =
            "Webhook(pnl=${presence(pnl)}, market=${presence(market)}, ops=${presence(ops)})"

        private companion object {
            /** 빈 값은 null. 형식 위반은 key 와 규칙만 담은 예외 — 입력 값과 `cause`(URISyntaxException)를 싣지 않는다. */
            fun parseWebhook(raw: String?, name: String): URI? {
                val trimmed = raw?.trim().orEmpty()
                if (trimmed.isEmpty()) return null
                val uri = try {
                    URI(trimmed)
                } catch (_: URISyntaxException) {
                    null
                }
                require(
                    uri != null && uri.isAbsolute && "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrBlank(),
                ) { "owner-alert.discord.webhook.$name must be an https URL" }
                return uri
            }
        }
    }

    data class RouteOverride(val email: String? = null, val discord: String? = null, val push: String? = null)

    companion object {
        private fun presence(value: String?): String = if (value.isNullOrBlank()) "<empty>" else "set"

        private fun parseOverrides(routes: Map<String, RouteOverride>): Map<OwnerAlertKind, OwnerAlertRouteOverride> =
            buildMap {
                routes.forEach { (key, route) ->
                    val kind = requireNotNull(OwnerAlertKind.fromKey(key)) {
                        "owner-alert.routes.$key is not a known alert kind " +
                            "(known: ${OwnerAlertKind.entries.joinToString(", ") { it.key }})"
                    }
                    val prefix = "owner-alert.routes.$key"
                    val email = route.email?.trim().orEmpty().let {
                        when {
                            it.isEmpty() -> null
                            it.equals("true", ignoreCase = true) -> true
                            it.equals("false", ignoreCase = true) -> false
                            else -> throw IllegalArgumentException("$prefix.email must be true or false")
                        }
                    }
                    val discord = route.discord?.trim().orEmpty()
                    val discordDisabled = discord.equals("none", ignoreCase = true)
                    val target = if (discord.isEmpty() || discordDisabled) {
                        null
                    } else {
                        requireNotNull(DiscordTarget.entries.firstOrNull { it.name.equals(discord, ignoreCase = true) }) {
                            "$prefix.discord must be pnl, market, ops or none"
                        }
                    }
                    val pushRaw = route.push?.trim().orEmpty()
                    val push = if (pushRaw.isEmpty()) {
                        null
                    } else {
                        requireNotNull(DiscordPush.entries.firstOrNull { it.name.equals(pushRaw, ignoreCase = true) }) {
                            "$prefix.push must be silent, sound or here"
                        }
                    }
                    if (email != null || target != null || discordDisabled || push != null) {
                        put(kind, OwnerAlertRouteOverride(email, target, discordDisabled, push))
                    }
                }
            }
    }
}
