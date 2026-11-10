package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HedgeQuoteTest {

    private fun level(price: String, quantity: String) = PriceLevel(BigDecimal(price), BigDecimal(quantity))

    @Test
    fun `김프는 소수 둘째 자리 HALF_UP 이다`() {
        // (1000 - 0.5 * 1900) / 1000 * 100 = 5.00
        assertThat(HedgeQuote.premiumPercent(BigDecimal("1000"), BigDecimal("0.5"), BigDecimal("1900")))
            .isEqualByComparingTo(BigDecimal("5.00"))
        // (3 - 1 * 2.9) / 3 * 100 = 3.3333.. -> 3.33
        assertThat(HedgeQuote.premiumPercent(BigDecimal("3"), BigDecimal("1"), BigDecimal("2.9")))
            .isEqualTo(BigDecimal("3.33"))
        // 경계 HALF_UP: (200 - 199.99) / 200 * 100 = 0.005 -> 0.01
        assertThat(HedgeQuote.premiumPercent(BigDecimal("200"), BigDecimal("1"), BigDecimal("199.99")))
            .isEqualTo(BigDecimal("0.01"))
    }

    @Test
    fun `AccountPnlBreakdown 이 내는 김프와 같은 입력에서 같은 값이다`() {
        val position = AccountHedgePosition(
            KoreaSpotAccountSnapshot(
                BigDecimal("0.266"), BigDecimal("113016278"), false, BigDecimal("12000"), BigDecimal("0.0004"),
                listOf(level("109500000", "1.0")), BigDecimal("109575000"), java.time.Instant.EPOCH,
            ),
            ForeignPerpAccountSnapshot(
                BigDecimal("-0.266"), BigDecimal("83549.4"), BigDecimal("8.9"), BigDecimal("3.2"), BigDecimal("0.0005"),
                listOf(level("81580", "5.0")), BigDecimal("81577"), java.time.Instant.EPOCH,
            ),
        )
        val pnl = AccountNetPnl.calculate(position, BigDecimal("1328.5"), java.time.Instant.EPOCH, java.time.Instant.EPOCH)
        val breakdown = AccountPnlBreakdown.of(pnl)

        assertThat(HedgeQuote.premiumPercent(pnl.koreaExitPrice, pnl.foreignExitPrice, pnl.fxRate))
            .isEqualTo(breakdown.currentPremiumPercent)
        assertThat(HedgeQuote.premiumPercent(BigDecimal("113016278"), BigDecimal("83549.4"), pnl.fxRate))
            .isEqualTo(breakdown.entryPremiumPercent)
        assertThat(HedgeQuote.vwap(position.korea.bidLevels, position.koreaQuantity, "bithumb bid"))
            .isEqualTo(pnl.koreaExitPrice)
        assertThat(HedgeQuote.vwap(position.foreign.askLevels, position.foreignQuantity, "binance ask"))
            .isEqualTo(pnl.foreignExitPrice)
    }

    @Test
    fun `여러 레벨을 앞에서부터 소화한 VWAP 이다`() {
        val result = HedgeQuote.vwap(listOf(level("100", "0.1"), level("90", "0.2")), BigDecimal("0.3"), "x")

        // (100×0.1 + 90×0.2) / 0.3 = 28 / 0.3
        assertThat(result).isEqualByComparingTo(BigDecimal("93.33333333333333333333333333333333"))
    }

    @Test
    fun `깊이가 부족하면 INSUFFICIENT_DEPTH 이다`() {
        assertThatThrownBy { HedgeQuote.vwap(listOf(level("100", "0.1")), BigDecimal("0.3"), "bithumb bid") }
            .isInstanceOfSatisfying(AccountReadException::class.java) {
                assertThat(it.category).isEqualTo(AccountReadFailureCategory.INSUFFICIENT_DEPTH)
            }
    }
}
