package io.premiumspread.domain.alertmessage

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 알림 문구 공용 서식. locale 무관(Locale.ROOT), 반올림 HALF_UP. zone 없는 시각 표기 함수는 두지 않는다. */
internal object AlertFormat {
    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT)
    private val ZONE_FORMAT = DateTimeFormatter.ofPattern("z", Locale.ROOT)
    private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val MONTH_DAY_CLOCK_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    /** `yyyy-MM-dd HH:mm z` — Asia/Seoul → `2026-10-20 09:00 KST`. 메일 모든 시각. */
    fun stamp(instant: Instant, zone: ZoneId): String = STAMP_FORMAT.format(instant.atZone(zone))

    /** [instant] 와 [basis] 의 zone 날짜가 같으면 `HH:mm`, 다르면 `MM-dd HH:mm`. */
    fun clock(instant: Instant, basis: Instant, zone: ZoneId): String {
        val local = instant.atZone(zone)
        return if (local.toLocalDate() == basis.atZone(zone).toLocalDate()) {
            CLOCK_FORMAT.format(local)
        } else {
            MONTH_DAY_CLOCK_FORMAT.format(local)
        }
    }

    /** `MM-dd HH:mm`. */
    fun monthDayClock(instant: Instant, zone: ZoneId): String = MONTH_DAY_CLOCK_FORMAT.format(instant.atZone(zone))

    /** [at] 시점 zone 약어(`z`) — Asia/Seoul → `KST`. */
    fun zoneAbbreviation(at: Instant, zone: ZoneId): String = ZONE_FORMAT.format(at.atZone(zone))

    /** 정시간 → `N시간`, 정분 → `N분`, 그 밖 `N초`. */
    fun threshold(duration: Duration): String = when {
        duration.nano == 0 && duration.seconds % 3600 == 0L -> "${duration.toHours()}시간"
        duration.nano == 0 && duration.seconds % 60 == 0L -> "${duration.toMinutes()}분"
        else -> "${duration.seconds}초"
    }

    /** 분 단위 버림. 음수 0. `<60분` → `N분`, 정시간 → `N시간`, 그 밖 `N시간 M분`. */
    fun duration(duration: Duration): String {
        val minutes = maxOf(duration.toMinutes(), 0L)
        return when {
            minutes < 60 -> "${minutes}분"
            minutes % 60 == 0L -> "${minutes / 60}시간"
            else -> "${minutes / 60}시간 ${minutes % 60}분"
        }
    }

    fun exchange(exchange: Exchange): String = when (exchange) {
        Exchange.UPBIT -> "업비트"
        Exchange.BITHUMB -> "빗썸"
        Exchange.BINANCE -> "바이낸스"
        Exchange.FX_PROVIDER -> "환율 제공자"
    }

    /** `{exchange(korea)}{separator}{exchange(foreign)}`. */
    fun exchangePair(pair: MarketPair, separator: String): String =
        exchange(pair.koreaExchange) + separator + exchange(pair.foreignExchange)

    fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    fun format(pattern: String, value: BigDecimal): String =
        DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).apply { roundingMode = RoundingMode.HALF_UP }
            .format(value)

    /** 음수 `-`, 그 밖 `+`. */
    fun sign(value: BigDecimal): String = if (value.signum() < 0) "-" else "+"

    /** `+1.11%` / `-0.10%`. */
    fun percent(value: BigDecimal): String = sign(value) + format("0.00", value.abs()) + "%"

    /** `+1.25%p`. */
    fun percentPoint(value: BigDecimal): String = sign(value) + format("0.00", value.abs()) + "%p"

    /** `1.78%` / `-0.50%` (양수 부호 없음). 반올림 후 0 이면 부호 없이 `0.00%`. */
    fun premium(value: BigDecimal): String {
        val rounded = value.setScale(2, RoundingMode.HALF_UP)
        return (if (rounded.signum() < 0) "-" else "") + format("0.00", rounded.abs()) + "%"
    }

    /** 원 단위 반올림 후 부호 — `+469,353원`, 0 이면 `+0원`. */
    fun krw(value: BigDecimal): String {
        val rounded = value.setScale(0, RoundingMode.HALF_UP)
        return sign(rounded) + format("#,##0", rounded.abs()) + "원"
    }

    private fun isZero(value: BigDecimal): Boolean = value.setScale(0, RoundingMode.HALF_UP).signum() == 0

    /** 반올림 0 이면 `0원`, 아니면 [krw]. */
    fun krwOrZero(value: BigDecimal): String = if (isZero(value)) "0원" else krw(value)

    /** 차감 항목. 반올림 0 이면 `0원`, 아니면 `-16,173원`(절댓값 앞에 `-`). */
    fun cost(value: BigDecimal): String =
        if (isZero(value)) "0원" else "-${format("#,##0", value.abs().setScale(0, RoundingMode.HALF_UP))}원"

    /** `158,500,000원` (부호 없음). */
    fun krwPrice(value: BigDecimal): String = format("#,##0", value.abs()) + "원"

    /** `111,200 USDT` / `70,000.5 USDT`. */
    fun usdtPrice(value: BigDecimal): String = format("#,##0.####", value.abs()) + " USDT"

    /** `14.79 USDT` (부호 없음). */
    fun usdt2(value: BigDecimal): String = format("#,##0.00", value.abs()) + " USDT"

    /** `+3.2 USDT`, 0 이면 `0 USDT`. */
    fun signedUsdt(value: BigDecimal): String =
        if (value.signum() == 0) "0 USDT" else "${sign(value)}${plain(value.abs())} USDT"

    /** `+4.20 USDT` / `-851.20 USDT`. 소수 둘째 자리 반올림 후 0 이면 `0.00 USDT`. */
    fun signedUsdt2(value: BigDecimal): String {
        val rounded = value.setScale(2, RoundingMode.HALF_UP)
        return if (rounded.signum() == 0) "0.00 USDT" else sign(rounded) + usdt2(rounded)
    }

    /** `1,400.00`. */
    fun rate(value: BigDecimal): String = format("#,##0.00", value)

    /** 한글 음절·자모·호환 자모·CJK 통합·전각 2, 그 밖 1. */
    fun displayWidth(text: String): Int = text.codePoints().map { if (isWide(it)) 2 else 1 }.sum()

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0x1100..0x11FF ||
            codePoint in 0x3130..0x318F ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xFF01..0xFF60
}
