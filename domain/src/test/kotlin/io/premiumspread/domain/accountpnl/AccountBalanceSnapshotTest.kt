package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AccountBalanceSnapshotTest {
    private val observedAt = Instant.parse("2026-10-20T00:00:00Z")
    private val one = BigDecimal.ONE

    private fun korea(
        krwBalance: BigDecimal? = null,
        usdtLastPriceKrw: BigDecimal? = null,
        amount: BigDecimal = one,
    ) = KoreaSpotAccountSnapshot(
        quantity = amount,
        averageBuyPrice = amount,
        averageBuyPriceModified = false,
        entryFeeKrw = amount,
        sellFeeRate = amount,
        bidLevels = listOf(PriceLevel(amount, amount)),
        lastPrice = amount,
        bidObservedAt = observedAt,
        krwBalance = krwBalance,
        usdtLastPriceKrw = usdtLastPriceKrw,
    )

    private fun foreign(
        balance: ForeignPerpBalance? = null,
        amount: BigDecimal = one,
    ) = ForeignPerpAccountSnapshot(
        signedQuantity = amount,
        entryPrice = amount,
        entryCommissionUsdt = amount,
        fundingUsdt = amount,
        takerFeeRate = amount,
        askLevels = listOf(PriceLevel(amount, amount)),
        lastPrice = amount,
        askObservedAt = observedAt,
        balance = balance,
    )

    private fun catchMessage(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message

    @Test
    fun `새 필드를 생략하면 null 이다`() {
        val k = korea()
        val f = foreign()
        assertThat(k.krwBalance).isNull()
        assertThat(k.usdtLastPriceKrw).isNull()
        assertThat(f.balance).isNull()
    }

    @Test
    fun `krwBalance 경계`() {
        assertThatCode { korea(krwBalance = BigDecimal.ZERO) }.doesNotThrowAnyException()
        assertThatThrownBy { korea(krwBalance = BigDecimal("-0.00000001")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `usdtLastPriceKrw 경계`() {
        assertThatThrownBy { korea(usdtLastPriceKrw = BigDecimal.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { korea(usdtLastPriceKrw = BigDecimal("0.0001")) }.doesNotThrowAnyException()
    }

    @Test
    fun `ForeignPerpBalance 경계`() {
        assertThatCode { ForeignPerpBalance(BigDecimal.ZERO, BigDecimal.ZERO) }.doesNotThrowAnyException()
        assertThatThrownBy { ForeignPerpBalance(BigDecimal("-0.01"), one) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ForeignPerpBalance(one, BigDecimal("-0.01")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `예외 메시지에 입력 값이 없다`() {
        val negative = BigDecimal("-0.01")
        val messages = listOf(
            catchMessage { korea(krwBalance = negative) },
            catchMessage { ForeignPerpBalance(negative, one) },
            catchMessage { ForeignPerpBalance(one, negative) },
            catchMessage { korea(usdtLastPriceKrw = negative) },
        )
        messages.forEach { assertThat(it).isNotNull().doesNotContain("-0.01") }
    }

    @Test
    fun `toString 은 금액을 노출하지 않는다`() {
        val sentinel = BigDecimal("987654.321")
        val balance = ForeignPerpBalance(sentinel, sentinel)
        val k = korea(krwBalance = sentinel, usdtLastPriceKrw = sentinel, amount = sentinel)
        val f = foreign(balance = balance, amount = sentinel)

        assertThat(balance.toString()).isEqualTo("ForeignPerpBalance")
        assertThat(k.toString()).isEqualTo("KoreaSpotAccountSnapshot(bidObservedAt=2026-10-20T00:00:00Z)")
        assertThat(f.toString())
            .isEqualTo("ForeignPerpAccountSnapshot(positionSource=EXCHANGE_API, askObservedAt=2026-10-20T00:00:00Z)")
        listOf(balance.toString(), k.toString(), f.toString(), AccountHedgePosition(k, f).toString())
            .forEach { assertThat(it).doesNotContain("987654.321") }
    }
}
