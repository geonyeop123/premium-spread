package io.premiumspread.domain.alertmessage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class EmbedDocumentTest {

    private val at = Instant.parse("2026-10-20T00:00:00Z")
    private val zwsp = Char(0x200B).toString()

    private fun cell(i: Int) = EmbedCell("칸$i", "값$i")

    private fun doc(rows: List<List<EmbedCell>>, footer: String? = "푸터") =
        EmbedDocument("실계정 · BTC", "제목", 0x2ECC71, rows, footer, at)

    private fun rowsOf(vararg sizes: Int): List<List<EmbedCell>> {
        var n = 0
        return sizes.map { size -> List(size) { cell(n++) } }
    }

    @Test
    fun `행 3-2-3-2-1 은 채움 칸 하나가 들어간 fields 12개이다`() {
        val message = doc(rowsOf(3, 2, 3, 2, 1)).toDiscordMessage()
        val fields = message.layout!!.fields

        assertThat(fields).hasSize(12)
        assertThat(fields.map { it.inline }).containsExactly(
            true, true, true,
            true, true, true,
            true, true, true,
            true, true,
            false,
        )
        assertThat(fields[5]).isEqualTo(EmbedDocument.SPACER)
        assertThat(fields.count { it == EmbedDocument.SPACER }).isEqualTo(1)
    }

    @Test
    fun `채움 칸은 U+200B 한 글자 이름과 값의 inline 칸이다`() {
        assertThat(EmbedDocument.SPACER.name).isEqualTo(Char(0x200B).toString()).hasSize(1)
        assertThat(EmbedDocument.SPACER.value).isEqualTo(zwsp)
        assertThat(EmbedDocument.SPACER.inline).isTrue()
    }

    @Test
    fun `1칸 행은 inline false 이다`() {
        val fields = doc(rowsOf(1)).toDiscordMessage().layout!!.fields
        assertThat(fields).hasSize(1)
        assertThat(fields[0].inline).isFalse()
    }

    @Test
    fun `마지막 2칸 행 뒤에는 채움 칸이 없다`() {
        assertThat(doc(rowsOf(3, 2)).toDiscordMessage().layout!!.fields).hasSize(5)
    }

    @Test
    fun `2칸 행 뒤에 1칸 행이 오면 채우지 않는다`() {
        assertThat(doc(rowsOf(2, 1)).toDiscordMessage().layout!!.fields).hasSize(3)
    }

    @Test
    fun `2칸 행 뒤에 2칸 행이 오면 채운다`() {
        val fields = doc(rowsOf(2, 2)).toDiscordMessage().layout!!.fields
        assertThat(fields).hasSize(5)
        assertThat(fields[2]).isEqualTo(EmbedDocument.SPACER)
    }

    @Test
    fun `값 1025자는 1024자와 말줄임표로 절단한다`() {
        val long = EmbedCell("칸", "가".repeat(1025))
        val value = doc(listOf(listOf(long))).toDiscordMessage().layout!!.fields[0].value
        assertThat(value).hasSize(1024).endsWith("…")
    }

    @Test
    fun `layout 은 author footer timestamp 와 title color 를 담는다`() {
        val message = doc(rowsOf(1)).toDiscordMessage()
        assertThat(message.title).isEqualTo("제목")
        assertThat(message.color).isEqualTo(0x2ECC71)
        assertThat(message.layout!!.author).isEqualTo("실계정 · BTC")
        assertThat(message.layout!!.footer).isEqualTo("푸터")
        assertThat(message.layout!!.timestamp).isEqualTo(at)
    }

    @Test
    fun `footer 가 null 이면 layout footer 도 null 이다`() {
        assertThat(doc(rowsOf(1), footer = null).toDiscordMessage().layout!!.footer).isNull()
    }

    @Test
    fun `대체 평문 description 은 칸마다 한 줄이고 footer 가 마지막 줄이다`() {
        val rows = listOf(
            listOf(EmbedCell("순손익", "**+1원**\n(+0.10%)"), EmbedCell("평단", "100원")),
            listOf(EmbedCell("a", "b"), EmbedCell("c", "d")),
        )
        val message = doc(rows).toDiscordMessage()
        assertThat(message.description).isEqualTo(
            listOf("순손익: +1원 / (+0.10%)", "평단: 100원", "a: b", "c: d", "푸터").joinToString("\n"),
        )
    }

    @Test
    fun `footer 가 없으면 description 에 footer 줄이 없다`() {
        assertThat(doc(listOf(listOf(EmbedCell("a", "b"))), footer = null).toDiscordMessage().description)
            .isEqualTo("a: b")
    }

    @Test
    fun `4칸 행과 빈 행과 빈 rows 는 거부한다`() {
        assertThatThrownBy { doc(rowsOf(4)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { doc(listOf(emptyList())) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { doc(emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
