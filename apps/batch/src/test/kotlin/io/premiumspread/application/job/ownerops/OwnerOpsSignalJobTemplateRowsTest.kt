package io.premiumspread.application.job.ownerops

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.JobExecutor
import io.premiumspread.application.common.JobResult
import io.premiumspread.application.notification.owneralert.MutableClock
import io.premiumspread.domain.batch.BatchMarket
import io.premiumspread.domain.job.JobId
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertChannels
import io.premiumspread.domain.owneralert.OwnerAlertKind
import io.premiumspread.domain.owneralert.OwnerAlertRouteOverride
import io.premiumspread.domain.owneralert.OwnerAlertRouting
import io.premiumspread.domain.ownerops.OpsAlertSettings
import io.premiumspread.domain.ownerops.OpsNotice
import io.premiumspread.domain.ownerops.OpsObservation
import io.premiumspread.domain.ownerops.OpsSignal
import io.premiumspread.domain.ownerops.OwnerOpsAlertContent
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Symbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Job 이 만드는 행이 템플릿 결과와 같다 (dod AC15(b)). 모든 채널을 켠 라우팅 + FX 메일 override 로
 * EMAIL·DISCORD 2행이 생기고, DISCORD 행은 layout 을 갖고, EMAIL 본문은 같은 입력의 템플릿 `mail.body()` 와 같다.
 * fake 구성은 같은 디렉터리 `OwnerOpsSignalJobTest` 의 복제다(R3 — 기존 파일 비의존).
 */
class OwnerOpsSignalJobTemplateRowsTest {
    private val start = Instant.parse("2026-10-28T00:00:00Z")
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = MutableClock(start)
    private val outbox = FakeOwnerAlertOutbox()
    private val lastSuccess = FakeLastSuccess(clock)
    private val executor = mockk<JobExecutor>()
    private val btc = Symbol("BTC")

    @Test
    fun `환율 수집 지연 첫 알림은 EMAIL DISCORD 2행이고 본문과 layout 이 템플릿 결과와 같다`() {
        every { executor.execute(any(), any()) } answers { secondArg<() -> JobResult>().invoke() }
        val lastFxSuccess = start.minus(Duration.ofMinutes(31))
        lastSuccess.overrides[JobId.FX_INGESTION] = lastFxSuccess
        val job = OwnerOpsSignalJob(
            lastSuccess,
            FakeFailureStreaks(),
            FakeTicks(clock),
            {
                BatchMarket(
                    MarketPair.default(btc),
                    Quote.coin(btc, Currency.KRW),
                    Quote.coin(btc, Currency.USD),
                    Currency.USD,
                    Currency.KRW,
                )
            },
            outbox,
            OwnerAlertRouting(
                OwnerAlertChannels(email = true, discord = true),
                mapOf(OwnerAlertKind.FX_INGESTION_DELAYED to OwnerAlertRouteOverride(email = true)),
            ),
            executor,
            clock,
        )

        assertThat(job.run()).isEqualTo(JobResult.Success)

        val notice = OpsNotice.Alert(
            OpsSignal.FxIngestion(Currency.USD, Currency.KRW),
            start,
            start,
            1,
            OpsObservation.Down(lastFxSuccess),
        )
        val message = OwnerOpsAlertContent.message(notice, OpsAlertSettings(), zone)
        val rows = outbox.rows
        assertThat(rows.map { it.channel }).containsExactly(OwnerAlertChannel.EMAIL, OwnerAlertChannel.DISCORD)
        assertThat(rows[0].body).isEqualTo(message.mail.body())
        assertThat(rows[1].discordLayout).isNotNull
        assertThat(rows[1].discordLayout).isEqualTo(message.discord.toDiscordMessage().layout)
    }
}
