package io.premiumspread.config

import io.premiumspread.domain.ownermarket.MarketAlertSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

/**
 * owner 시장 알림 설정 (`owner-alert.market.*`, design §9.1). `enabled` 는 조건만 읽는다.
 * 비밀 값이 없다. 잘못된 값은 [MarketAlertSettings] init 메시지로 기동 실패한다.
 */
@ConfigurationProperties(prefix = "owner-alert.market")
data class OwnerMarketAlertProperties(
    val swingWindow: Duration = Duration.ofMinutes(10),
    val swingThreshold: BigDecimal = BigDecimal("0.5"),
    val swingCooldown: Duration = Duration.ofMinutes(30),
    val upperThreshold: BigDecimal? = null,
    val lowerThreshold: BigDecimal? = null,
    val thresholdHysteresis: BigDecimal = BigDecimal("0.1"),
    val extremeMinDelta: BigDecimal = BigDecimal("0.1"),
    val extremeHourCooldown: Duration = Duration.ofMinutes(30),
    val extremeDayCooldown: Duration = Duration.ofHours(2),
    val summaryHour: Int = 9,
    val summaryPnlMaxAge: Duration = Duration.ofMinutes(10),
) {
    fun toSettings(): MarketAlertSettings = MarketAlertSettings(
        swingWindow,
        swingThreshold,
        swingCooldown,
        upperThreshold,
        lowerThreshold,
        thresholdHysteresis,
        extremeMinDelta,
        extremeHourCooldown,
        extremeDayCooldown,
        summaryHour,
        summaryPnlMaxAge,
    )
}
