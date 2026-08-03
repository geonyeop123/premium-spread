package io.premiumspread.domain.tracking

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * 확정 스냅샷의 양수 불변식.
 *
 * 캐시 파서는 `toBigDecimalOrNull` 이라 `0` 과 음수도 문법상 통과한다. 그런 값을 확정으로 저장하면
 * `hasConfirmedClose` 는 참인데 `grossPnl` 의 `require` 가 실패해 **되돌릴 수 없는 500 상태**가 남는다
 * (codex 코드리뷰 high-2). 도메인이 Ticker 생성 단계에서 0 을 거부하므로 통합 경로로는 재현할 수 없고,
 * 손상된 Redis payload 가 유일한 유입 경로다. 그래서 여기서 불변식 자체를 고정한다.
 */
class TrackingCloseSnapshotTest {

    private val at = Instant.parse("2026-08-03T00:00:00Z")

    private fun of(
        korea: String = "129555000",
        foreign: String = "89500",
        fx: String = "1432.6",
    ) = TrackingCloseSnapshot.of(
        koreaPrice = BigDecimal(korea),
        foreignPrice = BigDecimal(foreign),
        fxRate = BigDecimal(fx),
        premiumRate = BigDecimal("1.00"),
        observedAt = at,
        fxObservedAt = at,
    )

    @Test
    fun `양수면 확정 스냅샷을 만든다`() {
        assertThat(of()).isNotNull
    }

    @Test
    fun `0 이나 음수는 확정하지 않고 null 을 준다`() {
        assertThat(of(korea = "0")).isNull()
        assertThat(of(korea = "-1")).isNull()
        assertThat(of(foreign = "0")).isNull()
        assertThat(of(foreign = "-1")).isNull()
        assertThat(of(fx = "0")).isNull()
        assertThat(of(fx = "-1")).isNull()
    }

    @Test
    fun `생성자를 직접 쓰면 불변식이 예외로 막는다`() {
        assertThatThrownBy {
            TrackingCloseSnapshot(
                koreaPrice = BigDecimal.ZERO,
                foreignPrice = BigDecimal("89500"),
                fxRate = BigDecimal("1432.6"),
                premiumRate = BigDecimal("1.00"),
                observedAt = at,
                fxObservedAt = at,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
