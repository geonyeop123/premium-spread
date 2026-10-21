package io.premiumspread.infrastructure.batch.accountpnl

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 실계정 순손익 모니터링 설정. 기본값은 꺼짐이며 credential 은 환경변수로만 주입한다.
 *
 * credential 이 담긴 모든 타입은 [toString] 에서 값을 `***`(있음) 또는 `<empty>`(없음)로만 표시한다.
 */
@ConfigurationProperties(prefix = "account-pnl-monitor")
data class AccountPnlMonitorProperties(
    val enabled: Boolean = false,
    val recipient: String = "",
    val bithumb: BithumbCredentials = BithumbCredentials(),
    val binance: BinanceCredentials = BinanceCredentials(),
) {
    override fun toString(): String =
        "AccountPnlMonitorProperties(enabled=$enabled, recipient=${mask(recipient)}, bithumb=$bithumb, binance=$binance)"

    data class BithumbCredentials(
        val baseUrl: String = "https://api.bithumb.com",
        val accessKey: String = "",
        val secretKey: String = "",
    ) {
        override fun toString(): String =
            "BithumbCredentials(baseUrl=$baseUrl, accessKey=${mask(accessKey)}, secretKey=${mask(secretKey)})"
    }

    data class BinanceCredentials(
        val baseUrl: String = "https://fapi.binance.com",
        val apiKey: String = "",
        val secretKey: String = "",
    ) {
        override fun toString(): String =
            "BinanceCredentials(baseUrl=$baseUrl, apiKey=${mask(apiKey)}, secretKey=${mask(secretKey)})"
    }

    companion object {
        private fun mask(value: String): String = if (value.isBlank()) "<empty>" else "***"
    }
}
