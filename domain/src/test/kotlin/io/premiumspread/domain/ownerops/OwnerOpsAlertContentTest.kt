package io.premiumspread.domain.ownerops

import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.job.JobId
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
    private val realertFooter = "해소될 때까지 6시간마다 다시 알리고, 해소되면 1번 알림"
    private val realertMail = "해소될 때까지 6시간마다 다시 알리고, 해소되면 1번 알립니다."

    private val fxSignal = OpsSignal.FxIngestion(Currency.USD, Currency.KRW)
    private val bithumb = OpsSignal.MarketStream(Exchange.BITHUMB, Quote.coin(Symbol("BTC"), Currency.KRW))
    private val binance = OpsSignal.MarketStream(Exchange.BINANCE, Quote.coin(Symbol("BTC"), Currency.USD))
    private val job = OpsSignal.JobFailures(JobId.PREMIUM_REALTIME)

    private val fxAlert = OpsNotice.Alert(fxSignal, at, at, 1, OpsObservation.Down(Instant.parse("2026-10-27T23:29:00Z")))
    private val fxNoRecord = OpsNotice.Alert(fxSignal, at, at, 1, OpsObservation.Down(null))
    private val fxSecond = OpsNotice.Alert(fxSignal, at, at, 2, OpsObservation.Down(Instant.parse("2026-10-27T23:29:00Z")))
    private val marketAlert = OpsNotice.Alert(bithumb, at, at, 1, OpsObservation.Down(Instant.parse("2026-10-27T23:54:00Z")))
    private val marketNoRecord = OpsNotice.Alert(bithumb, at, at, 1, OpsObservation.Down(null))
    private val jobFirst = OpsNotice.Alert(
        job,
        Instant.parse("2026-10-27T18:00:00Z"),
        Instant.parse("2026-10-27T18:00:00Z"),
        1,
        OpsObservation.Down(Instant.parse("2026-10-27T17:55:00Z"), 3),
    )
    private val jobRealert = OpsNotice.Alert(
        job,
        Instant.parse("2026-10-27T18:00:00Z"),
        at,
        2,
        OpsObservation.Down(Instant.parse("2026-10-27T17:55:00Z"), 4),
    )
    private val recovered = OpsNotice.Recovered(binance, Instant.parse("2026-10-27T12:00:00Z"), at, 2)
    private val fxRecovered = OpsNotice.Recovered(fxSignal, Instant.parse("2026-10-27T23:30:00Z"), at, 1)
    private val jobRecovered = OpsNotice.Recovered(job, Instant.parse("2026-10-27T18:00:00Z"), at, 3)

    private val all = listOf(fxAlert, fxNoRecord, marketAlert, jobRealert, recovered)

    private fun msg(notice: OpsNotice): OwnerAlertMessage = OwnerOpsAlertContent.message(notice, settings, zone)

    private fun cell(name: String, value: String) = EmbedCell(name, value)

    @Test
    fun `환율 수집 지연 첫 알림 golden`() {
        val m = msg(fxAlert)

        assertThat(m.eventKey).isEqualTo("owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1")
        assertThat(m.mail.subject).isEqualTo("[운영] 환율 수집 지연 USD/KRW")
        assertThat(m.mail.body()).isEqualTo(
            """
            환율(USD/KRW)을 30분 넘게 가져오지 못했습니다.

            마지막 성공  2026-10-28 08:29 KST
            장애 감지    2026-10-28 09:00 KST

            $realertMail

            기준 시각 2026-10-28 09:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("운영")
        assertThat(e.title).isEqualTo("$warn 환율 수집 지연 USD/KRW")
        assertThat(e.color).isEqualTo(0xE67E22)
        assertThat(e.footer).isEqualTo(realertFooter)
        assertThat(e.timestamp).isEqualTo(at)
        assertThat(e.rows).containsExactly(listOf(cell("마지막 성공", "08:29"), cell("장애 감지", "09:00"), cell("기준", "30분")))
    }

    @Test
    fun `시세 끊김 빗썸 golden`() {
        val m = msg(marketAlert)

        assertThat(m.eventKey).isEqualTo("owner-ops.market-stream.bithumb.btc-krw.1793145600000.alert-1")
        assertThat(m.mail.subject).isEqualTo("[운영] 시세 끊김 빗썸 BTC/KRW")
        assertThat(m.mail.body()).isEqualTo(
            """
            빗썸 BTC/KRW 시세가 5분 넘게 들어오지 않았습니다.

            마지막 시세  2026-10-28 08:54 KST
            장애 감지    2026-10-28 09:00 KST

            $realertMail

            기준 시각 2026-10-28 09:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("운영")
        assertThat(e.title).isEqualTo("$warn 시세 끊김 빗썸 BTC/KRW")
        assertThat(e.color).isEqualTo(0xE67E22)
        assertThat(e.footer).isEqualTo(realertFooter)
        assertThat(e.rows).containsExactly(listOf(cell("마지막 시세", "08:54"), cell("장애 감지", "09:00"), cell("기준", "5분")))
    }

    @Test
    fun `Job 연속 실패 재알림 golden`() {
        val m = msg(jobRealert)

        assertThat(m.eventKey).isEqualTo("owner-ops.job-failures.premium_realtime.1793124000000.alert-2")
        assertThat(m.mail.subject).isEqualTo("[운영] Job 연속 실패 premium_realtime · 2번째 알림")
        assertThat(m.mail.body()).isEqualTo(
            """
            premium_realtime Job이 4회 연속 실패했습니다.

            연속 실패  4회
            첫 실패    2026-10-28 02:55 KST
            장애 감지  2026-10-28 03:00 KST
            지속       6시간

            기준: 3회 연속이고 5분 이상. 해소될 때까지 6시간마다 다시 알립니다.

            기준 시각 2026-10-28 09:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("운영")
        assertThat(e.title).isEqualTo("$warn Job 연속 실패 premium_realtime · 2번째 알림")
        assertThat(e.color).isEqualTo(0xE67E22)
        assertThat(e.footer).isEqualTo("기준 3회 연속이고 5분 이상 · 6시간마다 다시 알림")
        assertThat(e.rows).containsExactly(listOf(cell("연속 실패", "4회"), cell("첫 실패", "02:55"), cell("지속", "6시간")))
    }

    @Test
    fun `운영 장애 해소 golden`() {
        val m = msg(recovered)

        assertThat(m.eventKey).isEqualTo("owner-ops.market-stream.binance.btc-usd.1793102400000.recovered")
        assertThat(m.mail.subject).isEqualTo("[운영] 복구 — 시세 끊김 바이낸스 BTC/USD")
        assertThat(m.mail.body()).isEqualTo(
            """
            바이낸스 BTC/USD 시세 끊김이 해소되었습니다.

            장애 감지  2026-10-27 21:00 KST
            복구 확인  2026-10-28 09:00 KST
            지속       12시간
            보낸 알림  2회

            기준 시각 2026-10-28 09:00 KST
            """.trimIndent(),
        )
        val e = m.discord
        assertThat(e.author).isEqualTo("운영")
        assertThat(e.title).isEqualTo("✅ 복구 — 시세 끊김 바이낸스 BTC/USD")
        assertThat(e.color).isEqualTo(0x95A5A6)
        assertThat(e.footer).isNull()
        assertThat(e.timestamp).isEqualTo(at)
        assertThat(e.rows).containsExactly(
            listOf(cell("장애 감지", "10-27 21:00"), cell("복구 확인", "10-28 09:00"), cell("지속", "12시간")),
            listOf(cell("보낸 알림", "2회")),
        )
    }

    // ---- 변형 ----

    @Test
    fun `since 가 없으면 마지막 성공과 마지막 시세는 없음이다`() {
        assertThat(msg(fxNoRecord).discord.rows.single()[0]).isEqualTo(cell("마지막 성공", "없음"))
        assertThat(msg(fxNoRecord).mail.body()).contains("마지막 성공  없음")
        assertThat(msg(marketNoRecord).discord.rows.single()[0]).isEqualTo(cell("마지막 시세", "없음"))
        assertThat(msg(marketNoRecord).mail.body()).contains("마지막 시세  없음")
    }

    @Test
    fun `Job 첫 알림은 순번 접미사가 없고 환율 2번째 알림은 있다`() {
        assertThat(msg(jobFirst).discord.title).isEqualTo("$warn Job 연속 실패 premium_realtime")
        assertThat(msg(jobFirst).mail.subject).isEqualTo("[운영] Job 연속 실패 premium_realtime")
        assertThat(msg(jobFirst).discord.rows.single()[2]).isEqualTo(cell("지속", "0분"))
        assertThat(msg(fxSecond).discord.title).isEqualTo("$warn 환율 수집 지연 USD/KRW · 2번째 알림")
        assertThat(msg(fxSecond).mail.subject).isEqualTo("[운영] 환율 수집 지연 USD/KRW · 2번째 알림")
    }

    @Test
    fun `환율과 Job 해소는 메일 첫 줄이 종류별이다`() {
        assertThat(msg(fxRecovered).mail.body()).startsWith("환율(USD/KRW) 수집 지연이 해소되었습니다.")
        assertThat(msg(fxRecovered).mail.subject).isEqualTo("[운영] 복구 — 환율 수집 지연 USD/KRW")
        assertThat(msg(jobRecovered).mail.body()).startsWith("premium_realtime Job 연속 실패가 해소되었습니다.")
        assertThat(msg(jobRecovered).discord.rows[0][2]).isEqualTo(cell("지속", "6시간"))
    }

    @Test
    fun `색 상수와 eventKey 형식`() {
        assertThat(OwnerOpsAlertContent.ALERT_COLOR).isEqualTo(15105570)
        assertThat(OwnerOpsAlertContent.RECOVERED_COLOR).isEqualTo(9807270)
        assertThat(all.map { OwnerOpsAlertContent.eventKey(it) }).containsExactly(
            "owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1",
            "owner-ops.fx-ingestion.usd-krw.1793145600000.alert-1",
            "owner-ops.market-stream.bithumb.btc-krw.1793145600000.alert-1",
            "owner-ops.job-failures.premium_realtime.1793124000000.alert-2",
            "owner-ops.market-stream.binance.btc-usd.1793102400000.recovered",
        )
        all.forEach {
            assertThat(OwnerOpsAlertContent.eventKey(it)).matches("[A-Za-z0-9._-]{1,128}")
            assertThat(msg(it).eventKey).isEqualTo(OwnerOpsAlertContent.eventKey(it))
        }
    }

    @Test
    fun `threshold 표기`() {
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofMinutes(30))).isEqualTo("30분")
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofHours(6))).isEqualTo("6시간")
        assertThat(OwnerOpsAlertContent.threshold(Duration.ofSeconds(90))).isEqualTo("90초")
    }

    @Test
    fun `모든 결과에 주소 webhook 원문이 없다`() {
        val texts = all.flatMap {
            val m = msg(it)
            listOf(m.mail.subject, m.mail.body(), m.discord.title, m.discord.footer.orEmpty()) +
                m.discord.rows.flatten().flatMap { c -> listOf(c.name, c.value) }
        }

        assertThat(texts).allSatisfy { assertThat(it).doesNotContain("example.test", "/api/webhooks/", "docs/runbooks") }
    }
}
