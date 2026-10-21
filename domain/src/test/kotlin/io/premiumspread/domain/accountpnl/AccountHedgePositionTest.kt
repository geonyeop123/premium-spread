package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AccountHedgePositionTest {

    private val observedAt = Instant.parse("2026-10-20T00:00:00Z")

    private fun korea(quantity: String, avg: String = "150000000") = KoreaSpotAccountSnapshot(
        quantity = BigDecimal(quantity),
        averageBuyPrice = BigDecimal(avg),
        averageBuyPriceModified = false,
        entryFeeKrw = null,
        sellFeeRate = BigDecimal("0.0004"),
        bidLevels = emptyList(),
        lastPrice = BigDecimal("150000000"),
        bidObservedAt = observedAt,
    )

    private fun foreign(signedQuantity: String, entry: String = "100000") = ForeignPerpAccountSnapshot(
        signedQuantity = BigDecimal(signedQuantity),
        entryPrice = BigDecimal(entry),
        entryCommissionUsdt = null,
        fundingUsdt = null,
        takerFeeRate = BigDecimal("0.0005"),
        askLevels = emptyList(),
        lastPrice = BigDecimal("100000"),
        askObservedAt = observedAt,
    )

    @Test
    fun `빗썸 수량 0 이면 포지션 없음`() {
        assertThat(AccountHedgePosition.resolve(korea("0"), foreign("-0.265"))).isNull()
    }

    @Test
    fun `빗썸 수량이 dust 미만이면 포지션 없음`() {
        assertThat(AccountHedgePosition.resolve(korea("0.00009999"), foreign("-0.265"))).isNull()
    }

    @Test
    fun `빗썸 수량이 dust 와 같으면 포지션`() {
        assertThat(AccountHedgePosition.resolve(korea("0.0001"), foreign("-0.265"))).isNotNull()
    }

    @Test
    fun `바이낸스 수량 0 이면 포지션 없음`() {
        assertThat(AccountHedgePosition.resolve(korea("0.266"), foreign("0"))).isNull()
    }

    @Test
    fun `바이낸스 롱이면 포지션 없음`() {
        assertThat(AccountHedgePosition.resolve(korea("0.266"), foreign("0.266"))).isNull()
    }

    @Test
    fun `빗썸 0_266 과 바이낸스 -0_265 이면 각 leg 수량을 보존한다`() {
        val position = AccountHedgePosition.resolve(korea("0.266"), foreign("-0.265"))!!

        assertThat(position.koreaQuantity).isEqualByComparingTo(BigDecimal("0.266"))
        assertThat(position.foreignQuantity).isEqualByComparingTo(BigDecimal("0.265"))
    }

    @Test
    fun `fingerprint 는 양 leg 수량과 평단 진입가로 정해지고 빗썸 평단만 바뀌어도 달라진다`() {
        val base = AccountHedgePosition.resolve(korea("0.266", "150000000"), foreign("-0.265", "100000"))!!
        val same = AccountHedgePosition.resolve(korea("0.2660", "150000000.0"), foreign("-0.2650", "100000.00"))!!
        val koreaAvgChanged = AccountHedgePosition.resolve(korea("0.266", "150000001"), foreign("-0.265", "100000"))!!
        val foreignQtyChanged = AccountHedgePosition.resolve(korea("0.266", "150000000"), foreign("-0.264", "100000"))!!
        val foreignEntryChanged = AccountHedgePosition.resolve(korea("0.266", "150000000"), foreign("-0.265", "100001"))!!

        assertThat(base.fingerprint).isEqualTo("0.266|150000000|-0.265|100000")
        assertThat(same.fingerprint).isEqualTo(base.fingerprint)
        assertThat(koreaAvgChanged.fingerprint).isNotEqualTo(base.fingerprint)
        assertThat(foreignQtyChanged.fingerprint).isNotEqualTo(base.fingerprint)
        assertThat(foreignEntryChanged.fingerprint).isNotEqualTo(base.fingerprint)
    }
}
