package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AccountAssetBreakdownTest {
    private val observedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val fx = BigDecimal("1400")
    private val koreaValue = BigDecimal("42161000")
    private val balance = ForeignPerpBalance(BigDecimal("10000.50"), BigDecimal("8000.25"))

    private fun level() = PriceLevel(BigDecimal.ONE, BigDecimal.ONE)

    private fun korea(krw: String? = "1234567", usdt: String? = "1412") = KoreaSpotAccountSnapshot(
        quantity = BigDecimal.ONE,
        averageBuyPrice = BigDecimal.ONE,
        averageBuyPriceModified = false,
        entryFeeKrw = null,
        sellFeeRate = BigDecimal.ONE,
        bidLevels = listOf(level()),
        lastPrice = BigDecimal.ONE,
        bidObservedAt = observedAt,
        krwBalance = krw?.let(::BigDecimal),
        usdtLastPriceKrw = usdt?.let(::BigDecimal),
    )

    private fun foreign(
        bal: ForeignPerpBalance? = balance,
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
    ) = ForeignPerpAccountSnapshot(
        signedQuantity = BigDecimal.ONE.negate(),
        entryPrice = BigDecimal.ONE,
        entryCommissionUsdt = null,
        fundingUsdt = null,
        takerFeeRate = BigDecimal.ONE,
        askLevels = listOf(level()),
        lastPrice = BigDecimal.ONE,
        askObservedAt = observedAt,
        positionSource = source,
        balance = bal,
    )

    private fun of(
        k: KoreaSpotAccountSnapshot = korea(),
        f: ForeignPerpAccountSnapshot = foreign(),
        rate: BigDecimal = fx,
    ) = AccountAssetBreakdown.of(k, f, koreaValue, rate)

    private fun assertSame(actual: BigDecimal?, expected: String) {
        assertThat(actual).isNotNull
        assertThat(actual!!.compareTo(BigDecimal(expected))).isZero()
    }

    @Test
    fun `정상 입력이면 모든 값을 계산한다`() {
        val a = of()
        assertSame(a.totalKrw, "57396267.00")
        assertSame(a.marginBalanceKrw, "14000700.00")
        assertSame(a.availableBalanceKrw, "11200350.00")
        assertSame(a.bithumbKrwBalance, "1234567")
        assertSame(a.usdtLastPriceKrw, "1412")
        assertThat(a.marginBalanceUsdt).isEqualTo(BigDecimal("10000.50"))
        assertThat(a.availableBalanceUsdt).isEqualTo(BigDecimal("8000.25"))
    }

    @Test
    fun `반올림 없이 곱의 scale 을 그대로 둔다`() {
        val f = foreign(ForeignPerpBalance(BigDecimal("0.00000001"), BigDecimal.ZERO))
        val rate = BigDecimal("1400.123456")
        val a = of(f = f, rate = rate)
        val expected = BigDecimal("0.00000001").multiply(rate)
        assertThat(a.marginBalanceKrw).isEqualTo(expected)
        assertThat(a.marginBalanceKrw!!.scale()).isEqualTo(expected.scale())
    }

    @Test
    fun `krw 잔고가 0 이면 총 자산은 평가액과 증거금 원화의 합이다`() {
        val a = of(k = korea(krw = "0"))
        assertSame(a.totalKrw, "56161700.00")
    }

    @Test
    fun `krw 잔고가 null 이면 총 자산만 null 이고 바이낸스 값은 계산한다`() {
        val a = of(k = korea(krw = null))
        assertThat(a.totalKrw).isNull()
        assertThat(a.bithumbKrwBalance).isNull()
        assertSame(a.marginBalanceKrw, "14000700.00")
        assertSame(a.availableBalanceKrw, "11200350.00")
    }

    @Test
    fun `바이낸스 잔고가 null 이면 바이낸스 값과 총 자산이 null 이다`() {
        val a = of(f = foreign(bal = null))
        assertThat(a.totalKrw).isNull()
        assertThat(a.marginBalanceUsdt).isNull()
        assertThat(a.marginBalanceKrw).isNull()
        assertThat(a.availableBalanceUsdt).isNull()
        assertThat(a.availableBalanceKrw).isNull()
        assertSame(a.bithumbKrwBalance, "1234567")
    }

    @Test
    fun `수동 모드는 snapshot 에 잔고가 있어도 바이낸스 값과 총 자산이 null 이다`() {
        val a = of(f = foreign(source = ForeignPerpPositionSource.MANUAL_ENV))
        assertThat(a.totalKrw).isNull()
        assertThat(a.marginBalanceUsdt).isNull()
        assertThat(a.marginBalanceKrw).isNull()
        assertThat(a.availableBalanceUsdt).isNull()
        assertThat(a.availableBalanceKrw).isNull()
    }

    @Test
    fun `USDT 시세가 null 이어도 총 자산은 같다`() {
        val withPrice = of()
        val without = of(k = korea(usdt = null))
        assertThat(without.usdtLastPriceKrw).isNull()
        assertThat(without.totalKrw!!.compareTo(withPrice.totalKrw!!)).isZero()
    }

    @Test
    fun `toString 은 금액을 노출하지 않는다`() {
        assertThat(of().toString()).isEqualTo("AccountAssetBreakdown")
    }

    @Test
    fun `AccountPnlBreakdown 은 assets 를 채우고 toString 은 그대로다`() {
        val k = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.266"),
            averageBuyPrice = BigDecimal("152000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = BigDecimal("16173"),
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(PriceLevel(BigDecimal("158500000"), BigDecimal("10"))),
            lastPrice = BigDecimal("158500000"),
            bidObservedAt = observedAt,
            krwBalance = BigDecimal("1234567"),
            usdtLastPriceKrw = BigDecimal("1412"),
        )
        val f = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.266"),
            entryPrice = BigDecimal("108000"),
            entryCommissionUsdt = BigDecimal("14.36"),
            fundingUsdt = BigDecimal("4.2"),
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(PriceLevel(BigDecimal("111200"), BigDecimal("10"))),
            lastPrice = BigDecimal("111200"),
            askObservedAt = observedAt,
            balance = balance,
        )
        val calculatedAt = Instant.parse("2026-10-20T00:00:00Z")
        val pnl = AccountNetPnl.calculate(
            AccountHedgePosition(k, f),
            fx,
            Instant.parse("2026-10-19T23:59:00Z"),
            calculatedAt,
        )
        val breakdown = AccountPnlBreakdown.of(pnl)
        assertSame(breakdown.assets.totalKrw, "57396267")
        assertThat(breakdown.toString()).isEqualTo("AccountPnlBreakdown(calculatedAt=$calculatedAt)")
    }
}
