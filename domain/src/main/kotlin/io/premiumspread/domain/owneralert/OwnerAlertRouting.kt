package io.premiumspread.domain.owneralert

/** owner 알림 outbox 행의 채널. DB `account_pnl_mail.channel` 값 = enum 이름. */
enum class OwnerAlertChannel { EMAIL, DISCORD }

/** Discord webhook(채널) 논리 대상. DB `discord_target` 값 = enum 이름. URL 은 adapter 설정이 소유한다. */
enum class DiscordTarget { PNL, MARKET, OPS }

/** Discord 푸시 수준. SILENT = flags 4096, SOUND = 기본, HERE = 기본 + content "@here". DB `discord_push` 값 = enum 이름. */
enum class DiscordPush { SILENT, SOUND, HERE }

data class DiscordRoute(val target: DiscordTarget, val push: DiscordPush)

data class OwnerAlertRoute(val email: Boolean, val discord: DiscordRoute?)

/**
 * owner 알림 종류와 기본 경로 — 라우팅 표의 유일한 코드 위치(design §3.2).
 * 이 enum 전체에 대한 `when` 을 코드 어디에도 두지 않는다 (#89~#91 이 값을 더한다).
 */
enum class OwnerAlertKind(val defaultRoute: OwnerAlertRoute) {
    PROFIT(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE))),
    HOURLY_REPORT(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))),
    MONITORING_OUTAGE(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    MONITORING_RECOVERED(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.OPS, DiscordPush.SOUND))),
    POSITION_CHANGED(OwnerAlertRoute(email = true, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE))),
    NET_PERCENT_SWING(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.SOUND))),
    UNRECOVERED_REMINDER(OwnerAlertRoute(email = false, discord = DiscordRoute(DiscordTarget.PNL, DiscordPush.SILENT))),
    ;

    /** 설정 key — `HOURLY_REPORT` → `hourly-report`. */
    val key: String get() = name.lowercase().replace('_', '-')

    companion object {
        /** trim·소문자화한 [key] 와 같은 종류. 없으면 null. */
        fun fromKey(key: String): OwnerAlertKind? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** env 로 받은 종류별 덮어쓰기. null/false 는 "지정 안 함". */
data class OwnerAlertRouteOverride(
    val email: Boolean? = null,
    val discordTarget: DiscordTarget? = null,
    val discordDisabled: Boolean = false,
    val push: DiscordPush? = null,
) {
    init {
        require(!(discordDisabled && discordTarget != null)) { "discordTarget must not be set when discord is disabled." }
    }
}

/** 이번 기동에서 실제로 쓸 수 있는 채널 (sender bean 존재). */
data class OwnerAlertChannels(val email: Boolean, val discord: Boolean)

class OwnerAlertRouting(
    val channels: OwnerAlertChannels,
    private val overrides: Map<OwnerAlertKind, OwnerAlertRouteOverride> = emptyMap(),
) {
    /** 기본 경로 ⊕ override ⊕ 채널 가용성 (design §3.3 규칙 전문). */
    fun resolve(kind: OwnerAlertKind): OwnerAlertRoute {
        val default = kind.defaultRoute
        val override = overrides[kind] ?: OwnerAlertRouteOverride()

        val configuredDiscord = when {
            override.discordDisabled -> null

            else -> default.discord?.let { DiscordRoute(override.discordTarget ?: it.target, override.push ?: it.push) }
                ?: override.discordTarget?.let { DiscordRoute(it, override.push ?: DiscordPush.SOUND) }
        }
        val configuredEmail = override.email ?: default.email

        val discord = if (channels.discord) configuredDiscord else null
        // Discord 가 없으면 메일이 유일한 채널이므로 명시적 email=false 가 아닌 한 메일로 간다 (사용자 확정 D1).
        val email = channels.email && (if (channels.discord) configuredEmail else (override.email ?: true))
        return OwnerAlertRoute(email, discord)
    }

    override fun toString(): String = "OwnerAlertRouting(channels=$channels, overrides=$overrides)"

    companion object {
        /** 메일만 — 기존 동작. Job 생성자 기본값. */
        val EMAIL_ONLY: OwnerAlertRouting = OwnerAlertRouting(OwnerAlertChannels(email = true, discord = false))
    }
}
