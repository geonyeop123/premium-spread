package io.premiumspread.domain.ownerops

import io.premiumspread.domain.accountpnl.AccountPnlMail
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class OwnerOpsAlertContentTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val settings = OpsAlertSettings()
    private val at = Instant.parse("2026-10-28T00:00:00Z")
    private val warn = "⚠️"
    private val runbook = "조치 안내: docs/runbooks/owner-ops-alert.md"

    private val fxSignal = OpsSignal.FxIngestion(Currency.USD, Currency.KRW)
    private val bithumb = OpsSignal.MarketStream(Exchange.BITHUMB, Quote.coin(Symbol("BTC"), Currency.KRW))
    private val binance = OpsSignal.MarketStream(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD))
    private val job = OpsSignal.JobFailures(JobId.PREMIUM_REALTIME)

    private val fxAlert = OpsNotice.Alert(
        fxSignal,
        at,
        at,
        1,
        OpsObservation.Down(Instant.parse("2026-10-27T23:29:00Z")),
    )
    private val fxNoRecord = OpsNotice.Alert(fxSignal, at, at, 1, OpsObservation.Down(null))
    private val marketAlert = OpsNotice.Alert(
        bithumb,
        at,
        at,
        1,
        OpsObservation.Down(Instant.parse("2026-10-27T23:54:30Z")),
    )
    private val jobRealert = OpsNotice.Alert(
        job,
        Instant.parse("2026-10-27T18:00:00Z"),
        at,
        2,
        OpsObservation.Down(Instant.parse("2026-10-27T17:55:00Z"), 4),
    )
    private val recovered = OpsNotice.Recovered(binance, Instant.parse("2026-10-27T12:00:00Z"), at, 2)

    private val all = listOf(fxAlert, fxNoRecord, marketAlert, jobRealert, recovered)

    private fun mail(notice: OpsNotice) = OwnerOpsAlertContent.mail(notice, settings, zone)

    private fun discord(notice: OpsNotice) = OwnerOpsAlertContent.discord(notice, settings, zone)

    private fun lines(vararg line: String) = line.joinToString("\n")

    @Test
    fun `메일 - FX 첫 알림 마지막 성공 있음`() {
        assertThat(mail(fxAlert)).isEqualTo(
            AccountPnlMail(
                "owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1",
                "[운영] 환율 수집 지연 USD/KRW",
                lines(
                    "환율(USD/KRW) 수집이 30분 넘게 성공하지 않았습니다.",
                    "마지막 수집 성공: 2026-10-28 08:29:00 (2026-10-27T23:29:00Z)",
                    "장애 감지: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "알림 시각: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "해소될 때까지 6시간마다 다시 알리고, 해소되면 한 번 알립니다.",
                    runbook,
                ),
            ),
        )
    }

    @Test
    fun `메일 - FX 첫 알림 기록 없음`() {
        assertThat(mail(fxNoRecord)).isEqualTo(
            AccountPnlMail(
                "owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1",
                "[운영] 환율 수집 지연 USD/KRW",
                lines(
                    "환율(USD/KRW) 수집이 30분 넘게 성공하지 않았습니다.",
                    "마지막 수집 성공: 기록 없음(이 batch 프로세스 시작 후 성공 없음)",
                    "장애 감지: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "알림 시각: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "해소될 때까지 6시간마다 다시 알리고, 해소되면 한 번 알립니다.",
                    runbook,
                ),
            ),
        )
    }

    @Test
    fun `메일 - 시세 빗썸 첫 알림`() {
        assertThat(mail(marketAlert)).isEqualTo(
            AccountPnlMail(
                "owner-ops.market-stream.bithumb.btc-krw.1793145600000.alert-1",
                "[운영] 시세 수집 끊김 BITHUMB BTC/KRW",
                lines(
                    "BITHUMB BTC/KRW 시세가 5분 이상 들어오지 않았습니다.",
                    "마지막 시세(거래소 시각): 2026-10-28 08:54:30 (2026-10-27T23:54:30Z)",
                    "장애 감지: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "알림 시각: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "해소될 때까지 6시간마다 다시 알리고, 해소되면 한 번 알립니다.",
                    runbook,
                ),
            ),
        )
    }

    @Test
    fun `메일 - Job 재알림`() {
        assertThat(mail(jobRealert)).isEqualTo(
            AccountPnlMail(
                "owner-ops.job-failures.premium_realtime.1793124000000.alert-2",
                "[운영] Job 연속 실패 premium_realtime (계속, 2번째 알림)",
                lines(
                    "premium_realtime Job 이 4회 연속 실패했습니다 (첫 실패 후 6시간 5분).",
                    "첫 실패: 2026-10-28 02:55:00 (2026-10-27T17:55:00Z) · 기준: 3회 연속이고 5분 이상",
                    "장애 감지: 2026-10-28 03:00:00 (2026-10-27T18:00:00Z)",
                    "알림 시각: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "장애가 6시간 0분째 이어지고 있습니다. 6시간마다 다시 알립니다.",
                    runbook,
                ),
            ),
        )
    }

    @Test
    fun `메일 - 바이낸스 시세 복구`() {
        assertThat(mail(recovered)).isEqualTo(
            AccountPnlMail(
                "owner-ops.market-stream.binance.btc-usd.1793102400000.recovered",
                "[운영] 복구 — 시세 수집 끊김 BINANCE BTC/USD",
                lines(
                    "시세 수집 끊김 BINANCE BTC/USD 상태가 해소되었습니다.",
                    "장애 감지: 2026-10-27 21:00:00 (2026-10-27T12:00:00Z)",
                    "복구 확인: 2026-10-28 09:00:00 (2026-10-28T00:00:00Z)",
                    "장애 동안 보낸 알림: 2회",
                ),
            ),
        )
    }

    @Test
    fun `메일 행은 EMAIL 이고 Discord 경로가 없다`() {
        all.forEach {
            assertThat(mail(it).channel).isEqualTo(OwnerAlertChannel.EMAIL)
            assertThat(mail(it).discordRoute).isNull()
        }
    }

    @Test
    fun `Discord - FX 첫 알림 마지막 성공 있음`() {
        val message = discord(fxAlert)
        assertThat(message.title).isEqualTo("$warn 환율 수집 지연 USD/KRW")
        assertThat(message.description).isEqualTo(
            lines(
                "환율(USD/KRW) 수집이 30분 넘게 성공하지 않았습니다.",
                "마지막 수집 성공: 2026-10-28 08:29 KST",
                "장애 감지: 2026-10-28 09:00 KST",
                "알림 시각: 2026-10-28 09:00 KST",
                "해소될 때까지 6시간마다 다시 알리고, 해소되면 한 번 알립니다.",
                runbook,
            ),
        )
        assertThat(message.color).isEqualTo(15105570)
    }

    @Test
    fun `Discord - FX 첫 알림 기록 없음`() {
        val message = discord(fxNoRecord)
        assertThat(message.title).isEqualTo("$warn 환율 수집 지연 USD/KRW")
        assertThat(message.description).isEqualTo(
            lines(
                "환율(USD/KRW) 수집이 30분 넘게 성공하지 않았습니다.",
                "마지막 수집 성공: 기록 없음(이 batch 프로세스 시작 후 성공 없음)",
                "장애 감지: 2026-10-28 09:00 KST",
                "알림 시각: 2026-10-28 09:00 KST",
                "해소될 때까지 6시간마다 다시 알리고, 해소되면 한 번 알립니다.",
                runbook,
            ),
        )
        assertThat(message.color).isEqualTo(15105570)
    }

    @Test
    fun `Discord - 시세 빗썸 첫 알림`() {
        val message = discord(marketAlert)
        assertThat(message.title).isEqualTo("$warn 시세 수집 끊김 BITHUMB BTC/KRW")
        assertThat(message.description).isEqualTo(
            lines(
                "BITHUMB BTC/KRW 시세가 5분 이상 들어오지 않았습니다.",
                "마지막 시세(거래소 시각): 2026-10-28 08:54 KST",
                "장애 감지: 2026-10-28 09:00 KST",
                "알림 시각: 2026-10-28 09:00 KST",
                "해소될 때까지 6시간마다 다시 알리고, 해소되면 한 번 알립니다.",
                runbook,
            ),
        )
        assertThat(message.color).isEqualTo(15105570)
    }

    @Test
    fun `Discord - Job 재알림`() {
        val message = discord(jobRealert)
        assertThat(message.title).isEqualTo("$warn Job 연속 실패 premium_realtime (계속, 2번째 알림)")
        assertThat(message.description).isEqualTo(
            lines(
                "premium_realtime Job 이 4회 연속 실패했습니다 (첫 실패 후 6시간 5분).",
                "첫 실패: 2026-10-28 02:55 KST · 기준: 3회 연속이고 5분 이상",
                "장애 감지: 2026-10-28 03:00 KST",
                "알림 시각: 2026-10-28 09:00 KST",
                "장애가 6시간 0분째 이어지고 있습니다. 6시간마다 다시 알립니다.",
                runbook,
            ),
        )
        assertThat(message.color).isEqualTo(15105570)
    }

    @Test
    fun `Discord - 바이낸스 시세 복구`() {
        val message = discord(recovered)
        assertThat(message.title).isEqualTo("✅ 복구 — 시세 수집 끊김 BINANCE BTC/USD")
        assertThat(message.description).isEqualTo(
            lines(
                "시세 수집 끊김 BINANCE BTC/USD 상태가 해소되었습니다.",
                "장애 감지: 2026-10-27 21:00 KST",
                "복구 확인: 2026-10-28 09:00 KST",
                "장애 동안 보낸 알림: 2회",
            ),
        )
        assertThat(message.color).isEqualTo(9807270)
    }

    @Test
    fun `색 상수 정수값`() {
        assertThat(OwnerOpsAlertContent.ALERT_COLOR).isEqualTo(15105570)
        assertThat(OwnerOpsAlertContent.RECOVERED_COLOR).isEqualTo(9807270)
    }

    @Test
    fun `eventKey 형식`() {
        assertThat(all.map { OwnerOpsAlertContent.eventKey(it) }).containsExactly(
            "owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1",
            "owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1",
            "owner-ops.market-stream.bithumb.btc-krw.1793145600000.alert-1",
            "owner-ops.job-failures.premium_realtime.1793124000000.alert-2",
            "owner-ops.market-stream.binance.btc-usd.1793102400000.recovered",
        )
        all.forEach {
            assertThat(OwnerOpsAlertContent.eventKey(it)).matches("[A-Za-z0-9._-]{1,128}")
        }
    }

    @Test
    fun `임계값과 경과 표기`() {
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofMinutes(30))).isEqualTo("30분")
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofHours(6))).isEqualTo("6시간")
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofSeconds(90))).isEqualTo("90초")
        assertThat(OwnerOpsAlertContent.elapsed(Duration.ofHours(6).plusSeconds(30))).isEqualTo("6시간 0분")
        assertThat(OwnerOpsAlertContent.elapsed(Duration.ofMinutes(4).plusSeconds(59))).isEqualTo("4분")
        assertThat(OwnerOpsAlertContent.elapsed(Duration.ofMinutes(-3))).isEqualTo("0분")
    }

    @Test
    fun `금지 문자열이 없다`() {
        val texts = all.flatMap {
            val m = mail(it)
            val d = discord(it)
            listOf(m.eventKey, m.subject, m.body, d.title, d.description)
        }
        assertThat(texts).isNotEmpty
        texts.forEach { text ->
            listOf("example.test", "/api/webhooks/", "@", "http").forEach { assertThat(text).doesNotContain(it) }
        }
    }
}
