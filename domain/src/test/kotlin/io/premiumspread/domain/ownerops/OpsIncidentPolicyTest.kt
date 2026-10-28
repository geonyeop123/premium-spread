package io.premiumspread.domain.ownerops

import io.premiumspread.domain.accountpnl.OwnerAlertIncident
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.ticker.Currency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class OpsIncidentPolicyTest {
    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val settings = OpsAlertSettings()
    private val signal = OpsSignal.FxIngestion(Currency.USD, Currency.KRW)
    private val down = OpsObservation.Down(now.minusSeconds(1800))

    private fun normal(version: Long = 4) = OwnerAlertSignalState(signal.key, signal.alertKind, null, version)

    private fun active(lastNotifiedAt: Instant, count: Int = 2, version: Long = 7) = OwnerAlertSignalState(
        signal.key,
        signal.alertKind,
        OwnerAlertIncident(now.minus(Duration.ofHours(10)), lastNotifiedAt, count),
        version,
    )

    private fun decide(current: OwnerAlertSignalState, observation: OpsObservation, at: Instant = now) =
        OpsIncidentPolicy.decide(signal, current, observation, at, settings)

    @Test
    fun `T1 정상에서 장애 - 첫 알림`() {
        val decision = decide(normal(), down)

        assertThat(decision.next).isEqualTo(
            OwnerAlertSignalState(signal.key, signal.alertKind, OwnerAlertIncident(now, now, 1), 4),
        )
        assertThat(decision.notice).isEqualTo(OpsNotice.Alert(signal, now, now, 1, down))
    }

    @Test
    fun `T2 정상에서 Up Unknown 은 아무것도 쓰지 않는다`() {
        assertThat(decide(normal(), OpsObservation.Up)).isEqualTo(OpsIncidentDecision(null, null))
        assertThat(decide(normal(), OpsObservation.Unknown)).isEqualTo(OpsIncidentDecision(null, null))
    }

    @Test
    fun `T3 마지막 알림 6시간 뒤 재알림`() {
        val current = active(now.minus(Duration.ofHours(6)))
        val started = current.incident!!.startedAt

        val decision = decide(current, down)

        assertThat(decision.next).isEqualTo(current.copy(incident = OwnerAlertIncident(started, now, 3)))
        assertThat(decision.notice).isEqualTo(OpsNotice.Alert(signal, started, now, 3, down))
    }

    @Test
    fun `T4 6시간 - 1ms 는 재알림 없음`() {
        val current = active(now.minus(Duration.ofHours(6)).plusMillis(1))

        assertThat(decide(current, down)).isEqualTo(OpsIncidentDecision(null, null))
    }

    @Test
    fun `T5 장애에서 Up 은 복구 1회`() {
        val current = active(now.minus(Duration.ofHours(1)), count = 2)

        val decision = decide(current, OpsObservation.Up)

        assertThat(decision.next).isEqualTo(current.copy(incident = null))
        assertThat(decision.notice).isEqualTo(OpsNotice.Recovered(signal, current.incident!!.startedAt, now, 2))
    }

    @Test
    fun `T6 장애에서 Unknown 은 유지`() {
        val current = active(now.minus(Duration.ofHours(7)))

        assertThat(decide(current, OpsObservation.Unknown)).isEqualTo(OpsIncidentDecision(null, null))
    }

    @Test
    fun `복구 다음 Down 은 새 장애`() {
        val recovered = decide(active(now.minus(Duration.ofHours(1))), OpsObservation.Up).next!!
        val later = now.plusSeconds(30)

        val decision = decide(recovered, down, later)

        assertThat(decision.next!!.incident).isEqualTo(OwnerAlertIncident(later, later, 1))
        assertThat(decision.notice).isEqualTo(OpsNotice.Alert(signal, later, later, 1, down))
    }

    @Test
    fun `signalKey 불일치 거부`() {
        val other = OwnerAlertSignalState.initial(
            OpsSignal.JobFailures(JobId.FX_INGESTION).key,
            OwnerAlertKind.JOB_CONSECUTIVE_FAILURES,
        )

        assertThatThrownBy { decide(other, down) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("signal key does not match the state.")
    }

    @Test
    fun `notice kind`() {
        val alert = decide(normal(), down).notice!!
        val recovered = decide(active(now), OpsObservation.Up).notice!!

        assertThat(alert.kind).isEqualTo(OwnerAlertKind.FX_INGESTION_DELAYED)
        assertThat(recovered.kind).isEqualTo(OwnerAlertKind.OPS_RECOVERED)
    }
}
