package io.premiumspread.domain.ownerops

import io.premiumspread.domain.accountpnl.AccountPnlFormat
import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import io.premiumspread.domain.owneralert.DiscordMessage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 운영 알림 본문 (design §10). 메일(EMAIL 행)과 Discord 메시지를 같은 문구로 만든다. */
object OwnerOpsAlertContent {
    const val RUNBOOK = "docs/runbooks/owner-ops-alert.md"
    const val ALERT_COLOR = 0xE67E22
    const val RECOVERED_COLOR = 0x95A5A6

    /** design §10.1. */
    fun eventKey(notice: OpsNotice): String {
        val base = "owner-ops.${notice.signal.key}.${notice.incidentStartedAt.toEpochMilli()}"
        return when (notice) {
            is OpsNotice.Alert -> "$base.alert-${notice.sequence}"
            is OpsNotice.Recovered -> "$base.recovered"
        }
    }

    /** EMAIL 행(channel 기본값). 제목·본문 = design §10.2. */
    fun mail(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): AccountPnlMail =
        AccountPnlMail(
            eventKey(notice),
            "[운영] " + titleCore(notice),
            body(notice, settings) { AccountPnlFormat.time(it, zone) },
        )

    /** DiscordMessage.of 로 절단. 제목·설명·색 = design §10.2. */
    fun discord(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): DiscordMessage {
        val title = when (notice) {
            is OpsNotice.Alert -> "$WARNING ${titleCore(notice)}"
            is OpsNotice.Recovered -> "✅ ${titleCore(notice)}"
        }
        val color = if (notice is OpsNotice.Alert) ALERT_COLOR else RECOVERED_COLOR
        return DiscordMessage.of(title, body(notice, settings) { AccountPnlFormat.shortTime(it, zone) }, color)
    }

    // "\u26A0\uFE0F" = 경고 이모지(variation selector 포함)
    private const val WARNING = "\u26A0\uFE0F"

    /** 메일 subject 에서 `[운영] ` 을, Discord title 에서 이모지를 뺀 제목. */
    private fun titleCore(notice: OpsNotice): String = when (notice) {
        is OpsNotice.Alert ->
            if (notice.sequence == 1) headline(notice.signal) else "${headline(notice.signal)} (계속, ${notice.sequence}번째 알림)"
        is OpsNotice.Recovered -> "복구 — ${headline(notice.signal)}"
    }

    private fun headline(signal: OpsSignal): String = when (signal) {
        is OpsSignal.FxIngestion -> "환율 수집 지연 ${fx(signal)}"
        is OpsSignal.MarketStream -> "시세 수집 끊김 ${market(signal)}"
        is OpsSignal.JobFailures -> "Job 연속 실패 ${signal.jobId.tag}"
    }

    private fun fx(signal: OpsSignal.FxIngestion) = "${signal.base.code}/${signal.quote.code}"

    private fun market(signal: OpsSignal.MarketStream) =
        "${signal.exchange.name} ${signal.quote.baseCode}/${signal.quote.currency.code}"

    private fun body(notice: OpsNotice, settings: OpsAlertSettings, time: (Instant) -> String): String =
        when (notice) {
            is OpsNotice.Alert -> alertBody(notice, settings, time)
            is OpsNotice.Recovered -> listOf(
                "${headline(notice.signal)} 상태가 해소되었습니다.",
                "장애 감지: ${time(notice.incidentStartedAt)}",
                "복구 확인: ${time(notice.at)}",
                "장애 동안 보낸 알림: ${notice.notifyCount}회",
            ).joinToString("\n")
        }

    private fun alertBody(notice: OpsNotice.Alert, settings: OpsAlertSettings, time: (Instant) -> String): String {
        val since = notice.observation.since
        val (situation, evidence) = when (val signal = notice.signal) {
            is OpsSignal.FxIngestion ->
                "환율(${fx(signal)}) 수집이 ${threshold(FxIngestionFreshness.MAX_INGESTION_GAP)} 넘게 성공하지 않았습니다." to
                    "마지막 수집 성공: " + (since?.let(time) ?: "기록 없음(이 batch 프로세스 시작 후 성공 없음)")
            is OpsSignal.MarketStream ->
                "${market(signal)} 시세가 ${threshold(settings.marketStaleAfter)} 이상 들어오지 않았습니다." to
                    "마지막 시세(거래소 시각): " + (since?.let(time) ?: "기록 없음(이 batch 프로세스 시작 후 수신 없음)")
            is OpsSignal.JobFailures -> {
                val first = requireNotNull(since) { "A job failure alert requires the first failure time." }
                val count = requireNotNull(notice.observation.failureCount) { "A job failure alert requires a failure count." }
                "${signal.jobId.tag} Job 이 ${count}회 연속 실패했습니다 " +
                    "(첫 실패 후 ${elapsed(Duration.between(first, notice.at))})." to
                    "첫 실패: ${time(first)} · " +
                    "기준: ${settings.jobFailureCount}회 연속이고 ${threshold(settings.jobFailureDuration)} 이상"
            }
        }
        val interval = threshold(settings.realertInterval)
        val guide = if (notice.sequence == 1) {
            "해소될 때까지 ${interval}마다 다시 알리고, 해소되면 한 번 알립니다."
        } else {
            "장애가 ${elapsed(Duration.between(notice.incidentStartedAt, notice.at))}째 이어지고 있습니다. ${interval}마다 다시 알립니다."
        }
        return listOf(
            situation,
            evidence,
            "장애 감지: ${time(notice.incidentStartedAt)}",
            "알림 시각: ${time(notice.at)}",
            guide,
            "조치 안내: $RUNBOOK",
        ).joinToString("\n")
    }

    /** 임계값 표기 {d}. */
    internal fun threshold(duration: Duration): String = when {
        duration.nano == 0 && duration.seconds % 3600 == 0L -> "${duration.toHours()}시간"
        duration.nano == 0 && duration.seconds % 60 == 0L -> "${duration.toMinutes()}분"
        else -> "${duration.seconds}초"
    }

    /** 경과 표기 {e}. 음수는 0 으로 본다. */
    internal fun elapsed(duration: Duration): String {
        val minutes = maxOf(duration.toMinutes(), 0L)
        return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
    }
}
