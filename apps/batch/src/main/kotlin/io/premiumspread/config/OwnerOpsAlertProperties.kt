package io.premiumspread.config

import io.premiumspread.domain.ownerops.OpsAlertSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * owner 운영 알림 임계값 (`owner-alert.ops.*`, design §9.1). `enabled` 는 조건만 읽는다 — 빈 값이 Boolean 바인딩 실패가 되지 않게 두지 않는다.
 * 비밀 값이 없다. 잘못된 값은 [OpsAlertSettings] init 메시지로 기동 실패한다.
 */
@ConfigurationProperties(prefix = "owner-alert.ops")
data class OwnerOpsAlertProperties(
    val marketStaleAfter: Duration = Duration.ofMinutes(5),
    val jobFailureCount: Int = 3,
    val jobFailureDuration: Duration = Duration.ofMinutes(5),
    val realertInterval: Duration = Duration.ofHours(6),
) {
    fun toSettings(): OpsAlertSettings =
        OpsAlertSettings(marketStaleAfter, jobFailureCount, jobFailureDuration, realertInterval)
}
