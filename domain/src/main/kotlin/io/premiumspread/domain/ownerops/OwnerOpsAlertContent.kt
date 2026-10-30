package io.premiumspread.domain.ownerops

import io.premiumspread.domain.alertmessage.AlertFormat
import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.EmbedDocument
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.MailPair
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.exchangerate.FxIngestionFreshness
import java.time.Duration
import java.time.ZoneId

/** 운영 알림 4종(환율 수집 지연·시세 끊김·Job 연속 실패·해소)의 메일·Discord 문구. 문구 정본 message-spec.md "운영 알림", 값 출처 design §6.4. */
object OwnerOpsAlertContent {
    const val ALERT_COLOR = 0xE67E22
    const val RECOVERED_COLOR = 0x95A5A6
    const val AUTHOR = "운영"

    // 경고 이모지(variation selector 포함)
    private const val WARNING = "⚠️"

    /** 무변경. */
    fun eventKey(notice: OpsNotice): String {
        val base = "owner-ops.${notice.signal.key}.${notice.incidentStartedAt.toEpochMilli()}"
        return when (notice) {
            is OpsNotice.Alert -> "$base.alert-${notice.sequence}"
            is OpsNotice.Recovered -> "$base.recovered"
        }
    }

    /** basis = notice.at. */
    fun message(notice: OpsNotice, settings: OpsAlertSettings, zone: ZoneId): OwnerAlertMessage = when (notice) {
        is OpsNotice.Alert -> alert(notice, settings, zone)
        is OpsNotice.Recovered -> recovered(notice, zone)
    }

    private fun alert(notice: OpsNotice.Alert, settings: OpsAlertSettings, zone: ZoneId): OwnerAlertMessage {
        val at = notice.at
        val since = notice.observation.since
        val interval = AlertFormat.threshold(settings.realertInterval)
        val suffix = if (notice.sequence >= 2) " · ${notice.sequence}번째 알림" else ""
        val headline = headline(notice.signal) + suffix
        val realertGuide = "해소될 때까지 ${interval}마다 다시 알리고, 해소되면 1번 알립니다."

        val sinceMail = since?.let { AlertFormat.stamp(it, zone) } ?: "없음"
        val sinceClock = since?.let { AlertFormat.clock(it, at, zone) } ?: "없음"
        val incidentMail = AlertFormat.stamp(notice.incidentStartedAt, zone)
        val incidentClock = AlertFormat.clock(notice.incidentStartedAt, at, zone)

        val intro: String
        val pairs: List<MailPair>
        val guide: String
        val cells: List<EmbedCell>
        val footer: String
        when (val signal = notice.signal) {
            is OpsSignal.FxIngestion -> {
                val gap = AlertFormat.threshold(FxIngestionFreshness.MAX_INGESTION_GAP)
                intro = "환율(${signal.base.code}/${signal.quote.code})을 $gap 넘게 가져오지 못했습니다."
                pairs = listOf(MailPair("마지막 성공", sinceMail), MailPair("장애 감지", incidentMail))
                guide = realertGuide
                cells = listOf(
                    EmbedCell("마지막 성공", sinceClock),
                    EmbedCell("장애 감지", incidentClock),
                    EmbedCell("기준", gap),
                )
                footer = "해소될 때까지 ${interval}마다 다시 알리고, 해소되면 1번 알림"
            }

            is OpsSignal.MarketStream -> {
                val stale = AlertFormat.threshold(settings.marketStaleAfter)
                intro = "${market(signal)} 시세가 $stale 넘게 들어오지 않았습니다."
                pairs = listOf(MailPair("마지막 시세", sinceMail), MailPair("장애 감지", incidentMail))
                guide = realertGuide
                cells = listOf(
                    EmbedCell("마지막 시세", sinceClock),
                    EmbedCell("장애 감지", incidentClock),
                    EmbedCell("기준", stale),
                )
                footer = "해소될 때까지 ${interval}마다 다시 알리고, 해소되면 1번 알림"
            }

            is OpsSignal.JobFailures -> {
                val first = requireNotNull(since) { "A job failure alert requires the first failure time." }
                val count = requireNotNull(notice.observation.failureCount) { "A job failure alert requires a failure count." }
                val persisted = AlertFormat.duration(Duration.between(notice.incidentStartedAt, at))
                val rule = "${settings.jobFailureCount}회 연속이고 ${AlertFormat.threshold(settings.jobFailureDuration)} 이상"
                intro = "${signal.jobId.tag} Job이 ${count}회 연속 실패했습니다."
                pairs = listOf(
                    MailPair("연속 실패", "${count}회"),
                    MailPair("첫 실패", AlertFormat.stamp(first, zone)),
                    MailPair("장애 감지", incidentMail),
                    MailPair("지속", persisted),
                )
                guide = "기준: $rule. 해소될 때까지 ${interval}마다 다시 알립니다."
                cells = listOf(
                    EmbedCell("연속 실패", "${count}회"),
                    EmbedCell("첫 실패", AlertFormat.clock(first, at, zone)),
                    EmbedCell("지속", persisted),
                )
                footer = "기준 $rule · ${interval}마다 다시 알림"
            }
        }
        return OwnerAlertMessage(
            eventKey = eventKey(notice),
            mail = MailDocument(
                subject = "[운영] $headline",
                blocks = listOf(MailBlock.Text(listOf(intro)), MailBlock.Pairs(pairs), MailBlock.Text(listOf(guide))),
                basis = at,
                zone = zone,
            ),
            discord = EmbedDocument(
                author = AUTHOR,
                title = "$WARNING $headline",
                color = ALERT_COLOR,
                rows = listOf(cells),
                footer = footer,
                timestamp = at,
            ),
        )
    }

    private fun recovered(notice: OpsNotice.Recovered, zone: ZoneId): OwnerAlertMessage {
        val at = notice.at
        val headline = headline(notice.signal)
        val persisted = AlertFormat.duration(Duration.between(notice.incidentStartedAt, at))
        val intro = when (val signal = notice.signal) {
            is OpsSignal.MarketStream -> "${market(signal)} 시세 끊김이 해소되었습니다."
            is OpsSignal.FxIngestion -> "환율(${signal.base.code}/${signal.quote.code}) 수집 지연이 해소되었습니다."
            is OpsSignal.JobFailures -> "${signal.jobId.tag} Job 연속 실패가 해소되었습니다."
        }
        return OwnerAlertMessage(
            eventKey = eventKey(notice),
            mail = MailDocument(
                subject = "[운영] 복구 — $headline",
                blocks = listOf(
                    MailBlock.Text(listOf(intro)),
                    MailBlock.Pairs(
                        listOf(
                            MailPair("장애 감지", AlertFormat.stamp(notice.incidentStartedAt, zone)),
                            MailPair("복구 확인", AlertFormat.stamp(at, zone)),
                            MailPair("지속", persisted),
                            MailPair("보낸 알림", "${notice.notifyCount}회"),
                        ),
                    ),
                ),
                basis = at,
                zone = zone,
            ),
            discord = EmbedDocument(
                author = AUTHOR,
                title = "✅ 복구 — $headline",
                color = RECOVERED_COLOR,
                rows = listOf(
                    listOf(
                        EmbedCell("장애 감지", AlertFormat.monthDayClock(notice.incidentStartedAt, zone)),
                        EmbedCell("복구 확인", AlertFormat.monthDayClock(at, zone)),
                        EmbedCell("지속", persisted),
                    ),
                    listOf(EmbedCell("보낸 알림", "${notice.notifyCount}회")),
                ),
                footer = null,
                timestamp = at,
            ),
        )
    }

    private fun headline(signal: OpsSignal): String = when (signal) {
        is OpsSignal.FxIngestion -> "환율 수집 지연 ${signal.base.code}/${signal.quote.code}"
        is OpsSignal.MarketStream -> "시세 끊김 ${market(signal)}"
        is OpsSignal.JobFailures -> "Job 연속 실패 ${signal.jobId.tag}"
    }

    private fun market(signal: OpsSignal.MarketStream): String =
        "${AlertFormat.exchange(signal.exchange)} ${signal.quote.baseCode}/${signal.quote.currency.code}"
}
