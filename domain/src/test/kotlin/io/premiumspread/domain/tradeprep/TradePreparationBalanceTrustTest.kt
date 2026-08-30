package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * AC13 — `DeclaredBalanceAdapter`의 `UNVERIFIED` 스냅샷으로는 [VerifiedBalance]를 만들 수 없다
 * (design.md D9). 이 불가능성은 [VerifiedBalance.from]이 강제하고, 생성자는 이 클래스 밖에서
 * 호출할 수 없다(컴파일러가 강제).
 */
class TradePreparationBalanceTrustTest {

    private val observedAt = Instant.parse("2026-08-30T00:00:00Z")

    @Test
    fun `UNVERIFIED 스냅샷으로는 VerifiedBalance를 만들 수 없다`() {
        val declared = snapshot(BalanceBasis.UNVERIFIED)

        assertThat(VerifiedBalance.from(declared)).isNull()
    }

    @Test
    fun `UNAVAILABLE 스냅샷으로도 VerifiedBalance를 만들 수 없다`() {
        val unavailable = snapshot(BalanceBasis.UNAVAILABLE)

        assertThat(VerifiedBalance.from(unavailable)).isNull()
    }

    @Test
    fun `FRESH 스냅샷은 VerifiedBalance로 변환되고 필드를 보존한다`() {
        val fresh = snapshot(BalanceBasis.FRESH)

        val verified = VerifiedBalance.from(fresh)

        assertThat(verified).isNotNull
        assertThat(verified!!.snapshotId).isEqualTo(fresh.id)
        assertThat(verified.koreaBalance).isEqualByComparingTo(fresh.koreaBalance)
        assertThat(verified.foreignBalance).isEqualByComparingTo(fresh.foreignBalance)
        assertThat(verified.balanceBasis).isEqualTo(BalanceBasis.FRESH)
        assertThat(verified.observedAt).isEqualTo(fresh.observedAt)
    }

    @Test
    fun `STALE 스냅샷도 VerifiedBalance로 변환된다 — 신선도 거절은 소비자(registerTarget) 책임이다`() {
        val stale = snapshot(BalanceBasis.STALE)

        val verified = VerifiedBalance.from(stale)

        assertThat(verified).isNotNull
        assertThat(verified!!.balanceBasis).isEqualTo(BalanceBasis.STALE)
    }

    @Test
    fun `RecordedBalanceAdapter 결속(FRESH·STALE)으로는 같은 조건에서 ARMED에 필요한 판정용 잔고에 도달한다`() {
        val recordedFresh = snapshot(BalanceBasis.FRESH, id = "recorded-1")
        val recordedStale = snapshot(BalanceBasis.STALE, id = "recorded-2")

        assertThat(VerifiedBalance.from(recordedFresh)).isNotNull
        assertThat(VerifiedBalance.from(recordedStale)).isNotNull
    }

    private fun snapshot(basis: BalanceBasis, id: String = "snapshot-1"): BalanceSnapshot =
        BalanceSnapshot(
            id = id,
            koreaBalance = BigDecimal("24000000"),
            foreignBalance = BigDecimal("3500"),
            balanceBasis = basis,
            observedAt = observedAt,
        )
}
