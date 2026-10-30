package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * 기대값은 design §6.1 공식을 손으로 전개한 상수다 (dod AC4 입력).
 * 빗썸 0.266 BTC·평단 152,000,000·매수호가 158,500,000·진입 수수료 16,173원·sellFeeRate 0.0004,
 * 바이낸스 -0.266·진입가 108,000·매도호가 111,200·진입 수수료 14.36 USDT·펀딩 4.2 USDT·taker 0.0005, 환율 1400.
 */
class AccountPnlBreakdownTest {

    private val fx = BigDecimal("1400")
    private val fxObservedAt = Instant.parse("2026-10-19T23:59:00Z")
    private val calculatedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val observedAt = Instant.parse("2026-10-20T00:00:00Z")

    private fun level(price: String, qty: String = "10") = PriceLevel(BigDecimal(price), BigDecimal(qty))

    private fun pnl(
        bid: String = "158500000",
        entryFeeKrw: BigDecimal? = BigDecimal("16173"),
        entryCommission: BigDecimal? = BigDecimal("14.36"),
        funding: BigDecimal? = BigDecimal("4.2"),
        source: ForeignPerpPositionSource = ForeignPerpPositionSource.EXCHANGE_API,
    ): AccountNetPnl {
        val korea = KoreaSpotAccountSnapshot(
            quantity = BigDecimal("0.266"),
            averageBuyPrice = BigDecimal("152000000"),
            averageBuyPriceModified = false,
            entryFeeKrw = entryFeeKrw,
            sellFeeRate = BigDecimal("0.0004"),
            bidLevels = listOf(level(bid)),
            lastPrice = BigDecimal(bid),
            bidObservedAt = observedAt,
        )
        val foreign = ForeignPerpAccountSnapshot(
            signedQuantity = BigDecimal("-0.266"),
            entryPrice = BigDecimal("108000"),
            entryCommissionUsdt = entryCommission,
            fundingUsdt = funding,
            takerFeeRate = BigDecimal("0.0005"),
            askLevels = listOf(level("111200")),
            lastPrice = BigDecimal("111200"),
            askObservedAt = observedAt,
            positionSource = source,
        )
        return AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), fx, fxObservedAt, calculatedAt)
    }

    private fun assertNum(actual: BigDecimal?, expected: String) {
        assertThat(actual).isNotNull
        assertThat(actual!!).isEqualByComparingTo(BigDecimal(expected))
    }

    @Test
    fun `AC4 입력의 표시값 전부`() {
        val b = AccountPnlBreakdown.of(pnl())

        assertNum(b.currentPremiumPercent, "1.78")
        assertNum(b.entryPremiumPercent, "0.53")
        assertNum(b.premiumGapPercentPoint, "1.25")

        assertNum(b.korea.quantity, "0.266")
        assertNum(b.korea.averagePrice, "152000000")
        assertNum(b.korea.currentPrice, "158500000")
        assertNum(b.korea.pnlKrw, "1729000")
        assertNum(b.korea.valueKrw, "42161000")
        assertNum(b.korea.entryFeeKrw, "16173")
        assertNum(b.korea.exitFeeKrw, "16864.4")

        assertNum(b.foreign.quantity, "0.266")
        assertNum(b.foreign.entryPrice, "108000")
        assertNum(b.foreign.currentPrice, "111200")
        assertNum(b.foreign.pnlUsdt, "-851.2")
        assertNum(b.foreign.pnlKrw, "-1191680")
        assertNum(b.foreign.valueUsdt, "29579.2")
        assertNum(b.foreign.valueKrw, "41410880")
        assertNum(b.foreign.entryFeeUsdt, "14.36")
        assertNum(b.foreign.entryFeeKrw, "20104")
        assertNum(b.foreign.exitFeeUsdt, "14.7896")
        assertNum(b.foreign.exitFeeKrw, "20705.44")
        assertThat(b.foreign.source).isEqualTo(ForeignPerpPositionSource.EXCHANGE_API)

        assertNum(b.grossKrw, "537320")
        assertNum(b.feeTotalKrw, "73846.84")
        assertNum(b.fundingUsdt, "4.2")
        assertNum(b.fundingKrw, "5880")
        assertNum(b.netKrw, "469353.16")
        assertNum(b.netPercent, "1.11")
        assertNum(b.fxRate, "1400")
        assertThat(b.calculatedAt).isEqualTo(calculatedAt)
    }

    @Test
    fun `불변식 - 손익 합과 정리 수수료 합과 순손익이 AccountNetPnl 과 같다`() {
        val p = pnl()
        val b = AccountPnlBreakdown.of(p)

        assertThat(b.korea.pnlKrw.add(b.foreign.pnlKrw)).isEqualByComparingTo(p.grossKrw)
        assertThat(b.korea.exitFeeKrw.add(b.foreign.exitFeeKrw)).isEqualByComparingTo(p.exitFeeEstimateKrw)
        assertThat(b.grossKrw.subtract(b.feeTotalKrw).add(b.fundingKrw)).isEqualByComparingTo(p.netKrw)
    }

    @Test
    fun `진입 수수료와 펀딩이 null 이면 해당 값은 null 이고 수수료 합계는 정리 수수료만`() {
        val p = pnl(entryFeeKrw = null, entryCommission = null, funding = null)
        val b = AccountPnlBreakdown.of(p)

        assertThat(b.korea.entryFeeKrw).isNull()
        assertThat(b.foreign.entryFeeUsdt).isNull()
        assertThat(b.foreign.entryFeeKrw).isNull()
        assertThat(b.fundingUsdt).isNull()
        assertNum(b.fundingKrw, "0")
        assertNum(b.feeTotalKrw, "37569.84")
        assertThat(b.grossKrw.subtract(b.feeTotalKrw).add(b.fundingKrw)).isEqualByComparingTo(p.netKrw)
    }

    @Test
    fun `음수 김프는 부호 그대로`() {
        // (150,000,000 - 155,680,000) / 150,000,000 = -3.786...%
        val b = AccountPnlBreakdown.of(pnl(bid = "150000000"))

        assertNum(b.currentPremiumPercent, "-3.79")
        assertNum(b.entryPremiumPercent, "0.53")
        assertNum(b.premiumGapPercentPoint, "-4.32")
    }

    @Test
    fun `수동 입력 source 를 보존한다`() {
        val b = AccountPnlBreakdown.of(pnl(source = ForeignPerpPositionSource.MANUAL_ENV))

        assertThat(b.foreign.source).isEqualTo(ForeignPerpPositionSource.MANUAL_ENV)
    }

    @Test
    fun `toString 에 금액과 수량이 없다`() {
        val b = AccountPnlBreakdown.of(pnl())
        val all = listOf(b.toString(), b.korea.toString(), b.foreign.toString()).joinToString(" ")

        assertThat(all).doesNotContain("0.266", "152000000", "469")
        assertThat(b.toString()).contains("calculatedAt")
    }
}
