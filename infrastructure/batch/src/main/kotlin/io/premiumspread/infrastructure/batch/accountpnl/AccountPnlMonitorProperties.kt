package io.premiumspread.infrastructure.batch.accountpnl

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

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
        val mode: String? = null,
        val manual: ManualPosition = ManualPosition(),
    ) {
        /** 바인딩 시점에 한 번 해석한다. 잘못된 값이면 기동 실패이며 메시지에 입력 원문을 넣지 않는다. */
        private val parsedMode: BinanceLegMode = BinanceLegMode.parse(mode)

        fun resolvedMode(): BinanceLegMode = parsedMode

        override fun toString(): String =
            "BinanceCredentials(baseUrl=$baseUrl, apiKey=${mask(apiKey)}, secretKey=${mask(secretKey)}, " +
                "mode=${resolvedMode()}, manual=$manual)"
    }

    /** 바이낸스 leg 조회 방식. 해석 규칙: trim 후 비면 [API], `api`/`manual` 대소문자 무관, 그 밖은 기동 실패. */
    enum class BinanceLegMode {
        API,
        MANUAL,
        ;

        companion object {
            internal const val INVALID_MODE_MESSAGE = "account-pnl-monitor.binance.mode must be api or manual"

            /** 조건(`AccountPnlMonitorRequirements`)과 바인딩이 같은 규칙·같은 메시지를 쓰도록 공유한다. */
            internal fun parse(raw: String?): BinanceLegMode {
                val trimmed = raw?.trim().orEmpty()
                return when {
                    trimmed.isEmpty() -> API
                    trimmed.equals("api", ignoreCase = true) -> API
                    trimmed.equals("manual", ignoreCase = true) -> MANUAL
                    else -> error(INVALID_MODE_MESSAGE)
                }
            }
        }
    }

    /**
     * 바이낸스 수동 입력 포지션. 모든 값은 `String?` 원문으로 바인딩해 여기서 직접 해석한다 — Spring 변환기의
     * 실패 메시지·원인 체인에 입력 값이 실리지 않게 하고, 빈 값을 항상 "미입력"으로 보기 위해서다.
     * 해석 실패 예외는 property key 와 규칙만 담고 `cause` 를 연결하지 않는다.
     */
    data class ManualPosition(
        val shortQuantity: String? = null,
        val entryPrice: String? = null,
        val entryFeeUsdt: String? = null,
        val fundingUsdt: String? = null,
        val takerFeeRate: String? = null,
    ) {
        private val parsedShortQuantity: BigDecimal? =
            parse(shortQuantity, "$KEY_PREFIX.short-quantity must be a non-negative decimal") { it.signum() >= 0 }
        private val parsedEntryPrice: BigDecimal? =
            parse(entryPrice, "$KEY_PREFIX.entry-price must be a positive decimal") { it.signum() > 0 }
        private val parsedEntryFeeUsdt: BigDecimal? =
            parse(entryFeeUsdt, "$KEY_PREFIX.entry-fee-usdt must be a non-negative decimal") { it.signum() >= 0 }
        private val parsedFundingUsdt: BigDecimal? =
            parse(fundingUsdt, "$KEY_PREFIX.funding-usdt must be a decimal") { true }
        private val parsedTakerFeeRate: BigDecimal? =
            parse(takerFeeRate, "$KEY_PREFIX.taker-fee-rate must be a decimal in [0, 1)") {
                it.signum() >= 0 && it < BigDecimal.ONE
            }

        fun resolvedShortQuantity(): BigDecimal? = parsedShortQuantity

        fun resolvedEntryPrice(): BigDecimal? = parsedEntryPrice

        fun resolvedEntryFeeUsdt(): BigDecimal? = parsedEntryFeeUsdt

        fun resolvedFundingUsdt(): BigDecimal? = parsedFundingUsdt

        fun resolvedTakerFeeRate(): BigDecimal = parsedTakerFeeRate ?: DEFAULT_TAKER_FEE_RATE

        override fun toString(): String =
            "ManualPosition(shortQuantity=${presence(shortQuantity)}, entryPrice=${presence(entryPrice)}, " +
                "entryFeeUsdt=${presence(entryFeeUsdt)}, fundingUsdt=${presence(fundingUsdt)}, " +
                "takerFeeRate=${if (takerFeeRate.isNullOrBlank()) "default" else "set"})"

        companion object {
            val DEFAULT_TAKER_FEE_RATE: BigDecimal = BigDecimal("0.0005")
            private const val KEY_PREFIX = "account-pnl-monitor.binance.manual"

            private fun presence(value: String?): String = if (value.isNullOrBlank()) "<empty>" else "set"

            /** 빈 값은 null. 형식·범위 위반은 [message] 만 담은 예외 — 입력 값과 `cause` 를 싣지 않는다. */
            private fun parse(raw: String?, message: String, valid: (BigDecimal) -> Boolean): BigDecimal? {
                val trimmed = raw?.trim().orEmpty()
                if (trimmed.isEmpty()) return null
                val value = trimmed.toBigDecimalOrNull()
                require(value != null && valid(value)) { message }
                return value
            }
        }
    }

    companion object {
        private fun mask(value: String): String = if (value.isBlank()) "<empty>" else "***"
    }
}
