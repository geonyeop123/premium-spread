package io.premiumspread.domain.accountpnl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MonitoringOutagePolicyTest {

    private val t0 = Instant.parse("2026-10-20T00:00:00Z")
    private val fail = AccountReadFailureCategory.NETWORK

    private fun afterSuccess() = OutageState(lastSuccessAt = t0, monitoringSince = t0, outageNotified = false)

    @Test
    fun `실패 5분 정각에는 알리지 않는다`() {
        val d = MonitoringOutagePolicy.decide(afterSuccess(), fail, t0.plus(Duration.ofMinutes(5)))

        assertThat(d.notice).isNull()
        assertThat(d.next.outageNotified).isFalse()
    }

    @Test
    fun `5분 1초에 원인 범주와 함께 1회 알린다`() {
        val d = MonitoringOutagePolicy.decide(
            afterSuccess(), AccountReadFailureCategory.STALE_FX, t0.plusSeconds(301),
        )

        assertThat(d.notice).isEqualTo(OutageNotice.Outage(AccountReadFailureCategory.STALE_FX, t0))
        assertThat(d.next.outageNotified).isTrue()
        assertThat(d.next.lastSuccessAt).isEqualTo(t0)
    }

    @Test
    fun `알림 판정 회차의 범주를 싣는다`() {
        val first = MonitoringOutagePolicy.decide(afterSuccess(), AccountReadFailureCategory.BITHUMB_AUTH, t0.plusSeconds(60))
        val second = MonitoringOutagePolicy.decide(first.next, AccountReadFailureCategory.NETWORK, t0.plusSeconds(400))

        assertThat(first.notice).isNull()
        assertThat(second.notice).isEqualTo(OutageNotice.Outage(AccountReadFailureCategory.NETWORK, t0))
    }

    @Test
    fun `계속 실패해도 추가로 알리지 않는다`() {
        val notified = MonitoringOutagePolicy.decide(afterSuccess(), fail, t0.plusSeconds(301)).next

        val d = MonitoringOutagePolicy.decide(notified, fail, t0.plusSeconds(3600))

        assertThat(d.notice).isNull()
        assertThat(d.next).isEqualTo(notified)
    }

    @Test
    fun `복구 첫 회차에 1회 복구 알림`() {
        val notified = MonitoringOutagePolicy.decide(afterSuccess(), fail, t0.plusSeconds(301)).next
        val at = t0.plusSeconds(600)

        val d = MonitoringOutagePolicy.decide(notified, null, at)
        val again = MonitoringOutagePolicy.decide(d.next, null, at.plusSeconds(60))

        assertThat(d.notice).isEqualTo(OutageNotice.Recovered)
        assertThat(d.next).isEqualTo(OutageState(at, t0, false))
        assertThat(again.notice).isNull()
    }

    @Test
    fun `알린 적 없는 성공은 복구 알림을 내지 않고 마지막 성공 시각만 갱신한다`() {
        val d = MonitoringOutagePolicy.decide(afterSuccess(), null, t0.plusSeconds(60))

        assertThat(d.notice).isNull()
        assertThat(d.next.lastSuccessAt).isEqualTo(t0.plusSeconds(60))
    }

    @Test
    fun `첫 실행부터 실패면 첫 실행 시각 기준으로 잰다`() {
        val first = MonitoringOutagePolicy.decide(OutageState.INITIAL, fail, t0)
        val atFive = MonitoringOutagePolicy.decide(first.next, fail, t0.plus(Duration.ofMinutes(5)))
        val over = MonitoringOutagePolicy.decide(atFive.next, fail, t0.plusSeconds(301))

        assertThat(first.notice).isNull()
        assertThat(first.next.monitoringSince).isEqualTo(t0)
        assertThat(atFive.notice).isNull()
        assertThat(over.notice).isEqualTo(OutageNotice.Outage(fail, null))
    }

    @Test
    fun `첫 실행 성공이면 monitoringSince 를 채우고 알리지 않는다`() {
        val d = MonitoringOutagePolicy.decide(OutageState.INITIAL, null, t0)

        assertThat(d.notice).isNull()
        assertThat(d.next).isEqualTo(OutageState(t0, t0, false))
    }

    @Test
    fun `복구 뒤 다시 5분 초과 중단이면 또 알린다`() {
        val notified = MonitoringOutagePolicy.decide(afterSuccess(), fail, t0.plusSeconds(301)).next
        val recoveredAt = t0.plusSeconds(600)
        val recovered = MonitoringOutagePolicy.decide(notified, null, recoveredAt).next

        val within = MonitoringOutagePolicy.decide(recovered, fail, recoveredAt.plusSeconds(300))
        val over = MonitoringOutagePolicy.decide(recovered, fail, recoveredAt.plusSeconds(301))

        assertThat(within.notice).isNull()
        assertThat(over.notice).isEqualTo(OutageNotice.Outage(fail, recoveredAt))
    }
}
