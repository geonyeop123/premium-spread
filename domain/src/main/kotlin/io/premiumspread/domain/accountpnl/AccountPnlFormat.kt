package io.premiumspread.domain.accountpnl

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 메일·Discord 공용 숫자·시각 서식. locale 과 무관하다. `AccountPnlMailContent` 의 서식 요소를 그대로 옮긴 것이다. */
internal object AccountPnlFormat {
    const val RUNBOOK = "docs/runbooks/account-pnl-alert.md"
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val SHORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT)

    fun label(category: AccountReadFailureCategory): String = when (category) {
        AccountReadFailureCategory.BITHUMB_AUTH -> "빗썸 인증 실패(허용 IP 확인)"
        AccountReadFailureCategory.BINANCE_AUTH -> "바이낸스 인증 실패"
        AccountReadFailureCategory.NETWORK -> "네트워크·요청 한도 오류"
        AccountReadFailureCategory.STALE_MARKET_DATA -> "시세 지연"
        AccountReadFailureCategory.STALE_FX -> "환율 수집 지연"
        AccountReadFailureCategory.INSUFFICIENT_DEPTH -> "호가 깊이 부족"
        AccountReadFailureCategory.UNEXPECTED -> "기타 오류(로그 확인)"
    }

    /** 메일용 — `yyyy-MM-dd HH:mm:ss (instant)`. */
    fun time(instant: Instant, zone: ZoneId): String =
        "${TIME_FORMAT.format(instant.atZone(zone))} ($instant)"

    /** Discord 용 — `yyyy-MM-dd HH:mm z` (Asia/Seoul → `KST`). */
    fun shortTime(instant: Instant, zone: ZoneId): String = SHORT_TIME_FORMAT.format(instant.atZone(zone))

    fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    fun format(pattern: String, value: BigDecimal): String =
        DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).apply { roundingMode = RoundingMode.HALF_UP }
            .format(value)

    fun sign(value: BigDecimal): String = if (value.signum() < 0) "-" else "+"

    fun percent(value: BigDecimal): String = sign(value) + format("0.00", value.abs()) + "%"

    /** 부호 포함 원 단위 금액. 반올림 후 0 이면 `+0원`. */
    fun krw(value: BigDecimal): String {
        val rounded = value.setScale(0, RoundingMode.HALF_UP)
        return sign(rounded) + format("#,##0", rounded.abs()) + "원"
    }

    /** 부호 없는 원 단위 천 단위 구분 표기. */
    fun plainKrw(value: BigDecimal): String = format("#,##0", value.abs().setScale(0, RoundingMode.HALF_UP))

    fun isZero(value: BigDecimal): Boolean = value.setScale(0, RoundingMode.HALF_UP).signum() == 0

    /** 차감 항목(수수료). 반올림 후 0 이면 부호 없이 `0원`. */
    fun cost(value: BigDecimal): String =
        if (isZero(value)) "0원" else "-${plainKrw(value)}원"

    /** 부호 있는 금액이되 반올림 후 0 이면 `0원`. */
    fun krwOrZero(value: BigDecimal): String = if (isZero(value)) "0원" else krw(value)

    fun costUsdt(value: BigDecimal): String =
        if (value.signum() == 0) "0 USDT" else "-${plain(value.abs())} USDT"

    fun signedUsdt(value: BigDecimal): String =
        if (value.signum() == 0) "0 USDT" else "${sign(value)}${plain(value.abs())} USDT"
}
