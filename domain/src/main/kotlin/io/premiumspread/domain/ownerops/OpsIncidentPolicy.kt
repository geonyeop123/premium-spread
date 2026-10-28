package io.premiumspread.domain.ownerops

import io.premiumspread.domain.accountpnl.OwnerAlertIncident
import io.premiumspread.domain.accountpnl.OwnerAlertSignalState
import io.premiumspread.domain.owneralert.OwnerAlertKind
import java.time.Duration
import java.time.Instant

/** 이번 회차에 보낼 알림. [kind] 가 라우팅 종류다. */
sealed interface OpsNotice {
    val signal: OpsSignal
    val incidentStartedAt: Instant
    val at: Instant
    val kind: OwnerAlertKind

    /** 장애 알림. [sequence] 1 = 첫 알림, 2 이상 = 재알림. kind = signal.alertKind */
    data class Alert(
        override val signal: OpsSignal,
        override val incidentStartedAt: Instant,
        override val at: Instant,
        val sequence: Int,
        val observation: OpsObservation.Down,
    ) : OpsNotice {
        init {
            require(sequence >= 1) { "sequence must be at least 1." }
        }

        override val kind: OwnerAlertKind get() = signal.alertKind
    }

    /** 복구 알림. [notifyCount] = 장애 동안 보낸 알림 수. kind = OPS_RECOVERED */
    data class Recovered(
        override val signal: OpsSignal,
        override val incidentStartedAt: Instant,
        override val at: Instant,
        val notifyCount: Int,
    ) : OpsNotice {
        init {
            require(notifyCount >= 1) { "notifyCount must be at least 1." }
        }

        override val kind: OwnerAlertKind get() = OwnerAlertKind.OPS_RECOVERED
    }
}

/** [next] null = 상태 변화 없음(쓰지 않는다). */
data class OpsIncidentDecision(val next: OwnerAlertSignalState?, val notice: OpsNotice?)

object OpsIncidentPolicy {
    /** [current] 는 저장된 상태 또는 OwnerAlertSignalState.initial. signalKey 가 signal.key 와 다르면 IllegalArgumentException. */
    fun decide(
        signal: OpsSignal,
        current: OwnerAlertSignalState,
        observation: OpsObservation,
        now: Instant,
        settings: OpsAlertSettings,
    ): OpsIncidentDecision {
        require(current.signalKey == signal.key) { "signal key does not match the state." }
        val incident = current.incident
        return when {
            incident == null -> openOrKeep(signal, current, observation, now)
            observation is OpsObservation.Down -> realertOrKeep(signal, current, incident, observation, now, settings)
            observation is OpsObservation.Up -> OpsIncidentDecision(
                next = stateOf(signal, current, null),
                notice = OpsNotice.Recovered(signal, incident.startedAt, now, incident.notifyCount),
            )
            else -> KEEP
        }
    }

    private val KEEP = OpsIncidentDecision(null, null)

    private fun openOrKeep(
        signal: OpsSignal,
        current: OwnerAlertSignalState,
        observation: OpsObservation,
        now: Instant,
    ): OpsIncidentDecision {
        if (observation !is OpsObservation.Down) return KEEP
        return OpsIncidentDecision(
            next = stateOf(signal, current, OwnerAlertIncident(now, now, 1)),
            notice = OpsNotice.Alert(signal, now, now, 1, observation),
        )
    }

    private fun realertOrKeep(
        signal: OpsSignal,
        current: OwnerAlertSignalState,
        incident: OwnerAlertIncident,
        observation: OpsObservation.Down,
        now: Instant,
        settings: OpsAlertSettings,
    ): OpsIncidentDecision {
        if (Duration.between(incident.lastNotifiedAt, now) < settings.realertInterval) return KEEP
        val count = incident.notifyCount + 1
        return OpsIncidentDecision(
            next = stateOf(signal, current, OwnerAlertIncident(incident.startedAt, now, count)),
            notice = OpsNotice.Alert(signal, incident.startedAt, now, count, observation),
        )
    }

    private fun stateOf(signal: OpsSignal, current: OwnerAlertSignalState, incident: OwnerAlertIncident?) =
        OwnerAlertSignalState(signal.key, signal.alertKind, incident, current.version)
}
