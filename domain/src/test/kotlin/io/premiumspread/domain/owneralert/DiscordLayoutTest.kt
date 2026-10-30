package io.premiumspread.domain.owneralert

import io.premiumspread.domain.accountpnl.AccountPnlMail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class DiscordLayoutTest {

    private val zwsp = Char(0x200B).toString()
    private val at = Instant.parse("2026-10-20T00:00:00Z")

    private fun layout(
        author: String? = "실계정 · BTC",
        fields: List<DiscordField> = listOf(DiscordField("칸", "값", true)),
        footer: String? = "푸터",
    ) = DiscordLayout(author, fields, footer, at)

    // (a) DiscordField

    @Test
    fun `칸 이름이 빈 문자열이거나 공백만이면 거부한다`() {
        assertThatThrownBy { DiscordField("", "v", true) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiscordField("  ", "v", true) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `칸 이름 257자는 거부하고 256자는 통과한다`() {
        assertThatThrownBy { DiscordField("a".repeat(257), "v", true) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { DiscordField("a".repeat(256), "v", true) }.doesNotThrowAnyException()
    }

    @Test
    fun `칸 값이 빈 문자열이거나 공백만이면 거부한다`() {
        assertThatThrownBy { DiscordField("n", "", true) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiscordField("n", " \n ", true) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `칸 값 1025자는 거부하고 1024자는 통과한다`() {
        assertThatThrownBy { DiscordField("n", "a".repeat(1025), true) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { DiscordField("n", "a".repeat(1024), true) }.doesNotThrowAnyException()
    }

    @Test
    fun `zero-width space 한 글자는 이름과 값으로 통과한다`() {
        assertThatCode { DiscordField(zwsp, zwsp, true) }.doesNotThrowAnyException()
    }

    // (b) DiscordLayout

    @Test
    fun `칸 26개는 거부하고 25개는 통과한다`() {
        val field = DiscordField("n", "v", true)
        assertThatThrownBy { layout(fields = List(26) { field }) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { layout(fields = List(25) { field }) }.doesNotThrowAnyException()
    }

    @Test
    fun `footer 2049자는 거부하고 2048자는 통과한다`() {
        assertThatThrownBy { layout(footer = "a".repeat(2049)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { layout(footer = "a".repeat(2048)) }.doesNotThrowAnyException()
    }

    @Test
    fun `author 257자는 거부하고 256자는 통과한다`() {
        assertThatThrownBy { layout(author = "a".repeat(257)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { layout(author = "a".repeat(256)) }.doesNotThrowAnyException()
    }

    @Test
    fun `빈 author 와 빈 footer 는 거부하고 null 은 통과한다`() {
        assertThatThrownBy { layout(author = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { layout(footer = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatCode { layout(author = null, footer = null) }.doesNotThrowAnyException()
    }

    // (c) DiscordMessage 합계

    /** title 255 + author 256 + footer 2048 = 2559 에 칸 이름·값 길이를 더해 [total] 을 맞춘다. */
    private fun layoutWithTotal(total: Int): Pair<String, DiscordLayout> {
        val title = "t".repeat(255)
        var rest = total - 255 - 256 - 2048
        val fields = mutableListOf<DiscordField>()
        while (rest > 0) {
            val chunk = minOf(rest, 1280)
            val nameLen = minOf(256, chunk - 1)
            fields += DiscordField("n".repeat(nameLen), "v".repeat(chunk - nameLen), true)
            rest -= chunk
        }
        return title to DiscordLayout("a".repeat(256), fields, "f".repeat(2048), at)
    }

    @Test
    fun `layout 합계 6000 은 통과하고 6001 은 거부한다`() {
        val (title, ok) = layoutWithTotal(6000)
        assertThat(ok.fields.size).isLessThanOrEqualTo(25)
        assertThatCode { DiscordMessage(title, "d", 0, ok) }.doesNotThrowAnyException()
        val (title2, over) = layoutWithTotal(6001)
        assertThatThrownBy { DiscordMessage(title2, "d", 0, over) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `description 길이는 합계에 넣지 않는다`() {
        val (title, ok) = layoutWithTotal(6000)
        assertThatCode { DiscordMessage(title, "d".repeat(4096), 0, ok) }.doesNotThrowAnyException()
    }

    @Test
    fun `layout 이 없으면 기존 검증 그대로이고 합계 검증을 하지 않는다`() {
        assertThatCode { DiscordMessage("t".repeat(255), "d".repeat(4096), 0) }.doesNotThrowAnyException()
        assertThat(DiscordMessage("t", "d", 0).layout).isNull()
    }

    @Test
    fun `of 는 layout 을 보존하고 title 을 절단한다`() {
        val l = layout()
        val message = DiscordMessage.of("가".repeat(256), "d", 1, l)
        assertThat(message.title).hasSize(255)
        assertThat(message.layout).isEqualTo(l)
    }

    // (d) AccountPnlMail

    @Test
    fun `EMAIL 행에 discordLayout 이 있으면 거부한다`() {
        assertThatThrownBy { AccountPnlMail("k", "s", "b", OwnerAlertChannel.EMAIL, discordLayout = layout()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("discordLayout must be set only for DISCORD rows.")
    }

    @Test
    fun `DISCORD 행은 discordLayout 이 null 이어도 non-null 이어도 허용한다`() {
        val route = DiscordRoute(DiscordTarget.PNL, DiscordPush.HERE)
        assertThat(AccountPnlMail("k", "s", "b", OwnerAlertChannel.DISCORD, route, 1).discordLayout).isNull()
        assertThat(AccountPnlMail("k", "s", "b", OwnerAlertChannel.DISCORD, route, 1, layout()).discordLayout).isEqualTo(layout())
    }
}
