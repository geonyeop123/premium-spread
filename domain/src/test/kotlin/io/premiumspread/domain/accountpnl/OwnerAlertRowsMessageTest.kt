package io.premiumspread.domain.accountpnl

import io.premiumspread.domain.alertmessage.EmbedCell
import io.premiumspread.domain.alertmessage.EmbedDocument
import io.premiumspread.domain.alertmessage.MailBlock
import io.premiumspread.domain.alertmessage.MailDocument
import io.premiumspread.domain.alertmessage.OwnerAlertMessage
import io.premiumspread.domain.owneralert.DiscordPush
import io.premiumspread.domain.owneralert.DiscordRoute
import io.premiumspread.domain.owneralert.DiscordTarget
import io.premiumspread.domain.owneralert.OwnerAlertChannel
import io.premiumspread.domain.owneralert.OwnerAlertRoute
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class OwnerAlertRowsMessageTest {

    private val at = Instant.parse("2026-10-20T00:00:00Z")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val pnlHere = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
    private val marketSilent = DiscordRoute(DiscordTarget.MARKET, DiscordPush.SILENT)

    private val mail = MailDocument("메일 제목", listOf(MailBlock.Text(listOf("본문"))), at, seoul)
    private val embed = EmbedDocument(
        "실계정 · BTC",
        "임베드 제목",
        0x2ECC71,
        listOf(listOf(EmbedCell("칸", "값"))),
        "푸터",
        at,
    )
    private val message = OwnerAlertMessage("account-pnl-profit-1", mail, embed)

    @Test
    fun `메일과 Discord 가 모두 켜지면 메일 행이 먼저인 2행이다`() {
        val rows = OwnerAlertRows.expand(message, OwnerAlertRoute(true, pnlHere))

        assertThat(rows).hasSize(2)
        assertThat(rows[0]).isEqualTo(AccountPnlMail("account-pnl-profit-1", "메일 제목", mail.body()))
        val discord = embed.toDiscordMessage()
        assertThat(rows[1]).isEqualTo(
            AccountPnlMail(
                eventKey = "account-pnl-profit-1",
                subject = "임베드 제목",
                body = discord.description,
                channel = OwnerAlertChannel.DISCORD,
                discordRoute = pnlHere,
                discordColor = 0x2ECC71,
                discordLayout = discord.layout,
            ),
        )
        assertThat(rows[1].discordLayout).isNotNull()
    }

    @Test
    fun `메일만 켜지면 EMAIL 1행이다`() {
        val rows = OwnerAlertRows.expand(message, OwnerAlertRoute(true, null))
        assertThat(rows).hasSize(1)
        assertThat(rows[0].channel).isEqualTo(OwnerAlertChannel.EMAIL)
        assertThat(rows[0].discordLayout).isNull()
    }

    @Test
    fun `Discord 만 켜지면 DISCORD 1행이다`() {
        val rows = OwnerAlertRows.expand(message, OwnerAlertRoute(false, marketSilent))
        assertThat(rows).hasSize(1)
        assertThat(rows[0].channel).isEqualTo(OwnerAlertChannel.DISCORD)
        assertThat(rows[0].discordRoute).isEqualTo(marketSilent)
        assertThat(rows[0].eventKey).isEqualTo("account-pnl-profit-1")
    }

    @Test
    fun `둘 다 꺼지면 0행이다`() {
        assertThat(OwnerAlertRows.expand(message, OwnerAlertRoute(false, null))).isEmpty()
    }

    @Test
    fun `route 에 Discord 가 없으면 Discord 문서를 렌더하지 않는다`() {
        val tooBig = embed.copy(rows = List(7) { listOf(EmbedCell("n", "v".repeat(1000)), EmbedCell("m", "v".repeat(1000))) })
        assertThatThrownBy { tooBig.toDiscordMessage() }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { OwnerAlertRows.expand(message.copy(discord = tooBig), OwnerAlertRoute(true, null)) }
            .doesNotThrowAnyException()
    }

    // 삭제될 OwnerAlertRowsTest:24,31 의 비문구 계약 이식 (AccountPnlMail 불변식)

    @Test
    fun `3-인자 생성은 EMAIL 이고 Discord 필드가 없다`() {
        val plain = AccountPnlMail("account-pnl-profit-1", "s", "b")
        assertThat(plain.channel).isEqualTo(OwnerAlertChannel.EMAIL)
        assertThat(plain.discordRoute).isNull()
        assertThat(plain.discordColor).isNull()
    }

    @Test
    fun `행 채널과 Discord 필드가 어긋나면 거부한다`() {
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.DISCORD, null, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.DISCORD, pnlHere, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.EMAIL, pnlHere, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.EMAIL, null, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
