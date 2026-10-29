package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AccountPnlLatestTest {
    private val at = Instant.parse("2026-10-29T00:00:00Z")

    private fun pnl(netKrw: String, netPercent: String): AccountNetPnl {
        val zero = BigDecimal.ZERO
        val korea = KoreaSpotAccountSnapshot(
            BigDecimal("1"), BigDecimal("100"), false, zero, zero, emptyList(), BigDecimal("100"), at,
        )
        val foreign = ForeignPerpAccountSnapshot(
            BigDecimal("-1"), BigDecimal("100"), zero, zero, zero, emptyList(), BigDecimal("100"), at,
        )
        return AccountNetPnl(
            zero, zero, zero, zero, BigDecimal(netKrw), BigDecimal(netPercent),
            zero, zero, zero, zero, zero, zero, false, false, false,
            AccountHedgePosition(korea, foreign), BigDecimal("1400"), at, at,
        )
    }

    @Test
    fun `position true 인데 금액이 없으면 거부한다`() {
        assertThatThrownBy { AccountPnlLatest(true, BigDecimal("1.00"), null, at) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlLatest(true, null, BigDecimal("1"), at) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `position false 인데 금액이 있으면 거부한다`() {
        assertThatThrownBy { AccountPnlLatest(false, BigDecimal("1.00"), null, at) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlLatest(false, null, BigDecimal("1"), at) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `noPosition 은 금액이 없다`() {
        assertThat(AccountPnlLatest.noPosition(at)).isEqualTo(AccountPnlLatest(false, null, null, at))
    }

    @Test
    fun `computed 는 netKrw 를 scale 0 HALF_UP 으로 반올림하고 나머지는 그대로 둔다`() {
        val latest = AccountPnlLatest.computed(pnl("123456.5", "12.34"))

        assertThat(latest.position).isTrue()
        assertThat(latest.netKrw).isEqualTo(BigDecimal("123457"))
        assertThat(latest.netKrw!!.scale()).isZero()
        assertThat(latest.netPercent).isEqualTo(BigDecimal("12.34"))
        assertThat(latest.calculatedAt).isEqualTo(at)
    }

    @Test
    fun `computed 음수 경계는 절댓값 기준으로 올린다`() {
        assertThat(AccountPnlLatest.computed(pnl("-123456.5", "-1.00")).netKrw).isEqualTo(BigDecimal("-123457"))
    }

    @Test
    fun `toString 은 금액을 노출하지 않는다`() {
        val text = AccountPnlLatest.computed(pnl("123456.5", "12.34")).toString()

        assertThat(text).doesNotContain("123457", "12.34", "123456")
        assertThat(text).contains("calculatedAt")
    }
}
