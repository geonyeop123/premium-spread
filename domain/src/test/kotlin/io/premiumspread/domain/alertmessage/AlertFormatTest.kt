package io.premiumspread.domain.alertmessage

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class AlertFormatTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private fun bd(value: String) = BigDecimal(value)

    @Test
    fun `stamp 는 zone 약어를 붙인 분 단위 시각이다`() {
        assertThat(AlertFormat.stamp(Instant.parse("2026-10-20T00:00:00Z"), seoul)).isEqualTo("2026-10-20 09:00 KST")
    }

    @Test
    fun `clock 은 zone 날짜가 같으면 시각만 다르면 월일을 붙인다`() {
        val basis = Instant.parse("2026-10-20T00:00:00Z")
        assertThat(AlertFormat.clock(Instant.parse("2026-10-20T00:30:00Z"), basis, seoul)).isEqualTo("09:30")
        assertThat(AlertFormat.clock(Instant.parse("2026-10-17T00:30:00Z"), basis, seoul)).isEqualTo("10-17 09:30")
    }

    @Test
    fun `clock 은 UTC 날짜가 달라도 KST 날짜가 같으면 시각만 쓴다`() {
        val basis = Instant.parse("2026-10-20T00:00:00Z")
        assertThat(AlertFormat.clock(Instant.parse("2026-10-19T15:30:00Z"), basis, seoul)).isEqualTo("00:30")
    }

    @Test
    fun `monthDayClock 과 zoneAbbreviation`() {
        assertThat(AlertFormat.monthDayClock(Instant.parse("2026-10-27T12:00:00Z"), seoul)).isEqualTo("10-27 21:00")
        assertThat(AlertFormat.zoneAbbreviation(Instant.parse("2026-10-27T12:00:00Z"), seoul)).isEqualTo("KST")
    }

    @Test
    fun `threshold 는 정시간 정분 그 밖을 구분한다`() {
        assertThat(AlertFormat.threshold(Duration.ofMinutes(30))).isEqualTo("30분")
        assertThat(AlertFormat.threshold(Duration.ofHours(6))).isEqualTo("6시간")
        assertThat(AlertFormat.threshold(Duration.ofSeconds(90))).isEqualTo("90초")
    }

    @Test
    fun `duration 은 분 단위 버림이고 음수는 0분이다`() {
        assertThat(AlertFormat.duration(Duration.ZERO)).isEqualTo("0분")
        assertThat(AlertFormat.duration(Duration.ofMinutes(-5))).isEqualTo("0분")
        assertThat(AlertFormat.duration(Duration.ofMinutes(59))).isEqualTo("59분")
        assertThat(AlertFormat.duration(Duration.ofMinutes(60))).isEqualTo("1시간")
        assertThat(AlertFormat.duration(Duration.ofMinutes(365))).isEqualTo("6시간 5분")
        assertThat(AlertFormat.duration(Duration.ofMinutes(720))).isEqualTo("12시간")
        assertThat(AlertFormat.duration(Duration.ofSeconds(119))).isEqualTo("1분")
    }

    @Test
    fun `거래소 한글 표기`() {
        assertThat(AlertFormat.exchange(Exchange.UPBIT)).isEqualTo("업비트")
        assertThat(AlertFormat.exchange(Exchange.BITHUMB)).isEqualTo("빗썸")
        assertThat(AlertFormat.exchange(Exchange.BINANCE)).isEqualTo("바이낸스")
        assertThat(AlertFormat.exchange(Exchange.FX_PROVIDER)).isEqualTo("환율 제공자")
    }

    @Test
    fun `exchangePair 는 pair 자신의 거래소를 구분자로 잇는다`() {
        val pair = MarketPair(Symbol("BTC"), Exchange.UPBIT, Exchange.BINANCE)
        assertThat(AlertFormat.exchangePair(pair, "/")).isEqualTo("업비트/바이낸스")
        assertThat(AlertFormat.exchangePair(MarketPair.default(Symbol("BTC")), " / ")).isEqualTo("빗썸 / 바이낸스")
    }

    @Test
    fun `premium 은 양수에 부호가 없고 소수 둘째 자리 반올림이다`() {
        assertThat(AlertFormat.premium(bd("1.775"))).isEqualTo("1.78%")
        assertThat(AlertFormat.premium(bd("-0.5"))).isEqualTo("-0.50%")
        assertThat(AlertFormat.premium(bd("0"))).isEqualTo("0.00%")
    }

    @Test
    fun `가격 서식`() {
        assertThat(AlertFormat.krwPrice(bd("158500000"))).isEqualTo("158,500,000원")
        assertThat(AlertFormat.usdtPrice(bd("111200"))).isEqualTo("111,200 USDT")
        assertThat(AlertFormat.usdtPrice(bd("70000.5"))).isEqualTo("70,000.5 USDT")
        assertThat(AlertFormat.usdt2(bd("14.7896"))).isEqualTo("14.79 USDT")
        assertThat(AlertFormat.signedUsdt2(bd("4.2"))).isEqualTo("+4.20 USDT")
        assertThat(AlertFormat.signedUsdt2(bd("-851.2"))).isEqualTo("-851.20 USDT")
        assertThat(AlertFormat.signedUsdt2(bd("0"))).isEqualTo("0.00 USDT")
        assertThat(AlertFormat.rate(bd("1400"))).isEqualTo("1,400.00")
    }

    @Test
    fun `displayWidth 는 한글을 2 그 밖을 1 로 센다`() {
        assertThat(AlertFormat.displayWidth("마지막 성공")).isEqualTo(11)
        assertThat(AlertFormat.displayWidth("10분 저점")).isEqualTo(9)
        assertThat(AlertFormat.displayWidth("abc")).isEqualTo(3)
        assertThat(AlertFormat.displayWidth("")).isEqualTo(0)
    }

    @Test
    fun `삭제된 옛 서식과 같은 출력을 리터럴로 고정한다`() {
        val inputs = listOf("0", "-0.4", "0.5", "-0.5", "1234567.5")
        val expected = listOf(
            "+0.00%|+0.00%p|+0원|0원|0원|0|0 USDT|+|0.00",
            "-0.40%|-0.40%p|+0원|0원|0원|-0.4|-0.4 USDT|-|-0.40",
            "+0.50%|+0.50%p|+1원|+1원|-1원|0.5|+0.5 USDT|+|0.50",
            "-0.50%|-0.50%p|-1원|-1원|-1원|-0.5|-0.5 USDT|-|-0.50",
            "+1234567.50%|+1234567.50%p|+1,234,568원|+1,234,568원|-1,234,568원|1234567.5|+1234567.5 USDT|+|1,234,567.50",
        )
        inputs.zip(expected).forEach { (input, line) ->
            val x = bd(input)
            val actual = listOf(
                AlertFormat.percent(x), AlertFormat.percentPoint(x), AlertFormat.krw(x), AlertFormat.krwOrZero(x),
                AlertFormat.cost(x), AlertFormat.plain(x), AlertFormat.signedUsdt(x), AlertFormat.sign(x),
                AlertFormat.format("#,##0.00", x),
            ).joinToString("|")
            assertThat(actual).`as`(input).isEqualTo(line)
        }
    }
}
