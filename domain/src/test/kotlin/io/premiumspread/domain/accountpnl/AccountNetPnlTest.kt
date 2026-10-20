package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingRecordSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * 기대값은 design §3 공식을 손으로 전개한 상수다. 프로덕션 함수를 호출해 만들지 않는다.
 *
 * 고정 입력: 빗썸 qty 0.266, 평단 113016278, bid 109500000, last 109575000, 진입수수료 12000 KRW, 매도수수료율 0.0004
 *           바이낸스 qty 0.266(숏), 진입가 83549.4, ask 81580, last 81577, 진입수수료 8.9 USDT, 펀딩 +3.2 USDT,
 *           taker 0.0005, 환율 1328.5
 */
class AccountNetPnlTest {

    private val observedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val fxObservedAt = Instant.parse("2026-10-19T23:30:00Z")
    private val calculatedAt = Instant.parse("2026-10-20T00:00:05Z")
    private val fxRate = BigDecimal("1328.5")

    private fun korea(
        bidLevels: List<PriceLevel> = listOf(level("109500000", "1.0")),
        entryFeeKrw: BigDecimal? = BigDecimal("12000"),
    ) = KoreaSpotAccountSnapshot(
        quantity = BigDecimal("0.266"),
        averageBuyPrice = BigDecimal("113016278"),
        averageBuyPriceModified = false,
        entryFeeKrw = entryFeeKrw,
        sellFeeRate = BigDecimal("0.0004"),
        bidLevels = bidLevels,
        lastPrice = BigDecimal("109575000"),
        bidObservedAt = observedAt,
    )

    private fun foreign(
        askLevels: List<PriceLevel> = listOf(level("81580", "5.0")),
        entryCommissionUsdt: BigDecimal? = BigDecimal("8.9"),
        fundingUsdt: BigDecimal? = BigDecimal("3.2"),
    ) = ForeignPerpAccountSnapshot(
        signedQuantity = BigDecimal("-0.266"),
        entryPrice = BigDecimal("83549.4"),
        entryCommissionUsdt = entryCommissionUsdt,
        fundingUsdt = fundingUsdt,
        takerFeeRate = BigDecimal("0.0005"),
        askLevels = askLevels,
        lastPrice = BigDecimal("81577"),
        askObservedAt = observedAt,
    )

    private fun level(price: String, quantity: String) = PriceLevel(BigDecimal(price), BigDecimal(quantity))

    private fun calculate(korea: KoreaSpotAccountSnapshot = korea(), foreign: ForeignPerpAccountSnapshot = foreign()) =
        AccountNetPnl.calculate(AccountHedgePosition(korea, foreign), fxRate, fxObservedAt, calculatedAt)

    @Test
    fun `최우선 잔량이 부족하면 다음 레벨까지 VWAP 로 청산가를 계산한다`() {
        val result = calculate(
            korea = korea(bidLevels = listOf(level("109500000", "0.1"), level("109490000", "0.2"))),
        )

        // (109500000×0.1 + 109490000×0.166) / 0.266 = 29125340 / 0.266, DECIMAL128(34자리) 로 나눈다
        assertThat(result.koreaExitPrice).isEqualByComparingTo(BigDecimal("109493759.3984962406015037593984962"))
        assertThat(result.foreignExitPrice).isEqualByComparingTo(BigDecimal("81580"))
    }

    @Test
    fun `바이낸스 매도호가도 앞 레벨부터 소화한다`() {
        val result = calculate(
            foreign = foreign(askLevels = listOf(level("81580", "0.2"), level("81590", "0.066"), level("81600", "9"))),
        )

        // (81580×0.2 + 81590×0.066) / 0.266 = 21700.94 / 0.266, DECIMAL128(34자리) 로 나눈다
        assertThat(result.foreignExitPrice).isEqualByComparingTo(BigDecimal("81582.48120300751879699248120300752"))
    }

    @Test
    fun `호가 깊이가 수량보다 부족하면 INSUFFICIENT_DEPTH`() {
        assertThatThrownBy { calculate(korea = korea(bidLevels = listOf(level("109500000", "0.1")))) }
            .isInstanceOfSatisfying(AccountReadException::class.java) {
                assertThat(it.category).isEqualTo(AccountReadFailureCategory.INSUFFICIENT_DEPTH)
            }
        assertThatThrownBy { calculate(foreign = foreign(askLevels = listOf(level("81580", "0.265")))) }
            .isInstanceOfSatisfying(AccountReadException::class.java) {
                assertThat(it.category).isEqualTo(AccountReadFailureCategory.INSUFFICIENT_DEPTH)
            }
        assertThatThrownBy { calculate(foreign = foreign(askLevels = emptyList())) }
            .isInstanceOfSatisfying(AccountReadException::class.java) {
                assertThat(it.category).isEqualTo(AccountReadFailureCategory.INSUFFICIENT_DEPTH)
            }
    }

    @Test
    fun `호가 기준 순손익을 공식대로 계산한다`() {
        val result = calculate()

        // gross = (109500000 − 113016278)×0.266 + (83549.4 − 81580)×0.266×1328.5
        //       = −935329.948 + 695948.5414 = −239381.4066
        assertThat(result.grossKrw).isEqualByComparingTo(BigDecimal("-239381.4066"))
        // entryFee = 12000 + 8.9×1328.5 = 12000 + 11823.65 = 23823.65
        assertThat(result.entryFeeKrw).isEqualByComparingTo(BigDecimal("23823.65"))
        // funding = 3.2×1328.5 = 4251.2
        assertThat(result.fundingKrw).isEqualByComparingTo(BigDecimal("4251.2"))
        // exitFeeEst = 0.0004×109500000×0.266 + 0.0005×81580×0.266×1328.5
        //            = 11650.8 + 14414.41099 = 26065.21099
        assertThat(result.exitFeeEstimateKrw).isEqualByComparingTo(BigDecimal("26065.21099"))
        // net = −239381.4066 − 23823.65 + 4251.2 − 26065.21099 = −285019.06759
        assertThat(result.netKrw).isEqualByComparingTo(BigDecimal("-285019.06759"))
        // netPercent = −285019.06759 ÷ (109500000×0.266 = 29127000) × 100 = −0.97853904... → −0.98
        assertThat(result.netPercent).isEqualTo(BigDecimal("-0.98"))
        assertThat(result.netPercent.scale()).isEqualTo(2)
        assertThat(result.koreaExitPrice).isEqualByComparingTo(BigDecimal("109500000"))
        assertThat(result.foreignExitPrice).isEqualByComparingTo(BigDecimal("81580"))
        assertThat(result.koreaEntryFeeUnrecovered).isFalse()
        assertThat(result.foreignEntryFeeUnrecovered).isFalse()
        assertThat(result.fundingUnrecovered).isFalse()
        assertThat(result.fxRate).isEqualByComparingTo(fxRate)
        assertThat(result.fxObservedAt).isEqualTo(fxObservedAt)
        assertThat(result.calculatedAt).isEqualTo(calculatedAt)
    }

    @Test
    fun `체결가 기준 값은 lastPrice 로 계산한다`() {
        val result = calculate()

        // grossAtLast = (109575000 − 113016278)×0.266 + (83549.4 − 81577)×0.266×1328.5
        //             = −915379.948 + 697008.6844 = −218371.2636
        assertThat(result.grossKrwAtLast).isEqualByComparingTo(BigDecimal("-218371.2636"))
        // exitFeeAtLast = 0.0004×109575000×0.266 + 0.0005×81577×0.266×1328.5
        //               = 11658.78 + 14413.8809185 = 26072.6609185
        assertThat(result.exitFeeEstimateKrwAtLast).isEqualByComparingTo(BigDecimal("26072.6609185"))
        // netAtLast = −218371.2636 − 23823.65 + 4251.2 − 26072.6609185 = −264016.3745185
        assertThat(result.netKrwAtLast).isEqualByComparingTo(BigDecimal("-264016.3745185"))
        // netPercentAtLast = −264016.3745185 ÷ (109575000×0.266 = 29146950) × 100 = −0.90581132... → −0.91
        assertThat(result.netPercentAtLast).isEqualTo(BigDecimal("-0.91"))
    }

    @Test
    fun `체결가 기준 gross 는 Tracking grossPnl 과 같다`() {
        val result = calculate()
        val tracking = Tracking.create(
            TrackingRecordSpec(
                memberId = 1L,
                pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE),
                koreaQuantity = BigDecimal("0.266"),
                koreaEntryPrice = BigDecimal("113016278"),
                foreignQuantity = BigDecimal("0.266"),
                foreignEntryPrice = BigDecimal("83549.4"),
                foreignLeverage = 1,
                entryFxRate = fxRate,
                entryObservedAt = observedAt,
            ),
        )

        val gross = tracking.grossPnl(
            koreaPrice = BigDecimal("109575000"),
            foreignPrice = BigDecimal("81577"),
            fxRate = fxRate,
            premiumRate = BigDecimal.ZERO,
            observedAt = observedAt,
            fxObservedAt = fxObservedAt,
            calculatedAt = calculatedAt,
        )

        assertThat(result.grossKrwAtLast.compareTo(gross.totalGrossPnlKrw)).isZero()
    }

    @Test
    fun `진입 수수료 이력 미확보면 0 과 플래그`() {
        val koreaMissing = calculate(korea = korea(entryFeeKrw = null))
        // entryFee = 0 + 8.9×1328.5 = 11823.65, net = −285019.06759 + 12000 = −273019.06759
        assertThat(koreaMissing.entryFeeKrw).isEqualByComparingTo(BigDecimal("11823.65"))
        assertThat(koreaMissing.netKrw).isEqualByComparingTo(BigDecimal("-273019.06759"))
        assertThat(koreaMissing.koreaEntryFeeUnrecovered).isTrue()
        assertThat(koreaMissing.foreignEntryFeeUnrecovered).isFalse()
        assertThat(koreaMissing.fundingUnrecovered).isFalse()

        val foreignMissing = calculate(foreign = foreign(entryCommissionUsdt = null))
        assertThat(foreignMissing.entryFeeKrw).isEqualByComparingTo(BigDecimal("12000"))
        assertThat(foreignMissing.foreignEntryFeeUnrecovered).isTrue()
        assertThat(foreignMissing.koreaEntryFeeUnrecovered).isFalse()

        val fundingMissing = calculate(foreign = foreign(fundingUsdt = null))
        // net = −285019.06759 − 4251.2 = −289270.26759
        assertThat(fundingMissing.fundingKrw).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fundingMissing.netKrw).isEqualByComparingTo(BigDecimal("-289270.26759"))
        assertThat(fundingMissing.fundingUnrecovered).isTrue()
        assertThat(fundingMissing.koreaEntryFeeUnrecovered).isFalse()
    }

    @Test
    fun `펀딩 지급은 손실로 반영한다`() {
        val result = calculate(foreign = foreign(fundingUsdt = BigDecimal("-3.2")))

        // fundingKrw = −3.2×1328.5 = −4251.2, net = −285019.06759 − 2×4251.2 = −293521.46759
        assertThat(result.fundingKrw).isEqualByComparingTo(BigDecimal("-4251.2"))
        assertThat(result.netKrw).isEqualByComparingTo(BigDecimal("-293521.46759"))
        assertThat(result.fundingUnrecovered).isFalse()
    }
}
