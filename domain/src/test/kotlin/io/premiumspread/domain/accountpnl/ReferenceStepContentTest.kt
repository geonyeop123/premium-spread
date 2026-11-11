package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.EmbedCell
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class ReferenceStepContentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val at = Instant.parse("2026-10-20T03:00:00Z")

    private fun observation(kind: PositionObservationKind) = PositionObservation(
        kind = kind,
        observedAt = at,
        netPercent = if (kind == PositionObservationKind.HOLDING) BigDecimal("0.90") else null,
        netKrw = if (kind == PositionObservationKind.HOLDING) BigDecimal("123457") else null,
        currentPremiumPercent = BigDecimal("2.31"),
        entryPremiumPercent = BigDecimal("1.74"),
        premiumGapPercentPoint = BigDecimal("0.57"),
        fxRate = BigDecimal("1400"),
        koreaExitPrice = BigDecimal("100000000"),
        foreignExitPrice = BigDecimal("70000"),
    )

    private fun alert(
        direction: SwingDirection,
        level: Int,
        phase: ReferencePhase,
    ) = AccountAlert.ReferenceStep(
        direction,
        level,
        BigDecimal("0.50"),
        phase,
        observation(if (phase == ReferencePhase.HOLDING) PositionObservationKind.HOLDING else PositionObservationKind.FLAT),
    )

    private fun msg(alert: AccountAlert.ReferenceStep) = AccountAlertContent.message(alert, zone)

    @Test
    fun `보유 상승 제목과 eventKey`() {
        val a = alert(SwingDirection.UP, 1, ReferencePhase.HOLDING)
        val m = msg(a)

        assertThat(m.eventKey).isEqualTo("account-pnl-reference-step-up-1-${at.toEpochMilli()}")
        assertThat(AccountAlertContent.eventKey(a)).isEqualTo(m.eventKey)
        assertThat(m.eventKey).matches("[A-Za-z0-9._-]{1,128}")
        assertThat(m.mail.subject).isEqualTo("[실계정] 기준 대비 김프 +0.50%p")
        assertThat(m.discord.title).isEqualTo("📈 기준 대비 김프 +0.50%p")
        assertThat(m.discord.color).isEqualTo(AccountAlertContent.COLOR_SWING_UP)
        assertThat(m.discord.author).isEqualTo("실계정 · BTC")
        assertThat(m.discord.timestamp).isEqualTo(at)
    }

    @Test
    fun `보유 하락 제목은 음수 경계와 하락 이모지 색이다`() {
        val m = msg(alert(SwingDirection.DOWN, -1, ReferencePhase.HOLDING))

        assertThat(m.eventKey).isEqualTo("account-pnl-reference-step-down--1-${at.toEpochMilli()}")
        assertThat(m.mail.subject).isEqualTo("[실계정] 기준 대비 김프 -0.50%p")
        assertThat(m.discord.title).isEqualTo("📉 기준 대비 김프 -0.50%p")
        assertThat(m.discord.color).isEqualTo(AccountAlertContent.COLOR_SWING_DOWN)
    }

    @Test
    fun `무포지션 제목은 직전 포지션 기준이다`() {
        val m = msg(alert(SwingDirection.UP, 2, ReferencePhase.FLAT))

        assertThat(m.mail.subject).isEqualTo("[실계정] 직전 포지션 대비 김프 +1.00%p")
        assertThat(m.discord.title).isEqualTo("📈 직전 포지션 대비 김프 +1.00%p")
    }

    @Test
    fun `무포지션이고 level 0 이면 직전 진입 수준 도달`() {
        val m = msg(alert(SwingDirection.DOWN, 0, ReferencePhase.FLAT))

        assertThat(m.mail.subject).isEqualTo("[실계정] 직전 진입 수준 도달")
        assertThat(m.discord.title).isEqualTo("📉 직전 진입 수준 도달")
    }

    @Test
    fun `보유 중 level 0 은 일반 제목을 쓴다`() {
        val m = msg(alert(SwingDirection.DOWN, 0, ReferencePhase.HOLDING))

        assertThat(m.mail.subject).isEqualTo("[실계정] 기준 대비 김프 +0.00%p")
    }

    @Test
    fun `Discord 칸과 footer`() {
        val m = msg(alert(SwingDirection.UP, 1, ReferencePhase.HOLDING))

        assertThat(m.discord.rows).containsExactly(
            listOf(EmbedCell("경계", "+0.50%p"), EmbedCell("현재 차이", "+0.57%p"), EmbedCell("현재 김프", "2.31%")),
            listOf(EmbedCell("기준 매수김프", "1.74%"), EmbedCell("환율", "1,400.00")),
            listOf(EmbedCell("국면", "보유 중")),
        )
        assertThat(m.discord.footer).isEqualTo("기준 ±0.50%p 단계 · 같은 경계는 한 단계 움직인 뒤 다시 알림")
    }

    @Test
    fun `무포지션 국면 칸은 직전 포지션 기준임을 밝힌다`() {
        val m = msg(alert(SwingDirection.UP, 1, ReferencePhase.FLAT))

        assertThat(m.discord.rows.last()).containsExactly(EmbedCell("국면", "포지션 없음 — 직전 포지션 기준"))
        assertThat(m.mail.body()).contains("국면").contains("포지션 없음 — 직전 포지션 기준")
    }

    @Test
    fun `메일 본문은 기준 시각을 담고 실계정 수량을 담지 않는다`() {
        val body = msg(alert(SwingDirection.UP, 1, ReferencePhase.HOLDING)).mail.body()

        assertThat(body).contains("+0.57%p").contains("기준 시각 2026-10-20 12:00 KST")
        assertThat(body).doesNotContain("BTC")
    }
}
