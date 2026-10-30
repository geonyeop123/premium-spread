package io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl

import io.premiumspread.domain.owneralert.DiscordField
import io.premiumspread.domain.owneralert.DiscordLayout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class DiscordLayoutJsonTest {

    @Test
    fun `write 는 design 5-2 의 키 순서와 v 1 을 쓴다`() {
        val layout = DiscordLayout(
            author = "실계정 · BTC",
            fields = listOf(DiscordField("현김프", "1.78%", true)),
            footer = "환율 1,400.00원/USD",
            timestamp = Instant.parse("2026-10-20T00:00:00Z"),
        )

        assertThat(DiscordLayoutJson.write(layout)).isEqualTo(
            """{"v":1,"author":"실계정 · BTC","fields":[{"name":"현김프","value":"1.78%","inline":true}],""" +
                """"footer":"환율 1,400.00원/USD","timestamp":"2026-10-20T00:00:00Z"}""",
        )
    }

    @Test
    fun `빈 fields 와 null 요소는 fields 배열과 JSON null 로 쓴다`() {
        val layout = DiscordLayout(author = null, fields = emptyList(), footer = null, timestamp = null)

        assertThat(DiscordLayoutJson.write(layout))
            .isEqualTo("""{"v":1,"author":null,"fields":[],"footer":null,"timestamp":null}""")
    }

    @Test
    fun `read(write(x)) 는 x 와 같다 - 모든 요소`() = assertRoundTrip(
        DiscordLayout("a", listOf(DiscordField("n1", "v1", true), DiscordField("n2", "v2", false)), "f", T),
    )

    @Test
    fun `read(write(x)) 는 x 와 같다 - null author`() =
        assertRoundTrip(DiscordLayout(null, listOf(DiscordField("n", "v", false)), "f", T))

    @Test
    fun `read(write(x)) 는 x 와 같다 - null footer 와 timestamp`() =
        assertRoundTrip(DiscordLayout("a", listOf(DiscordField("n", "v", true)), null, null))

    @Test
    fun `read(write(x)) 는 x 와 같다 - 빈 fields`() = assertRoundTrip(DiscordLayout(null, emptyList(), null, null))

    @Test
    fun `read(write(x)) 는 x 와 같다 - 한글 이모지 따옴표 역슬래시 개행`() = assertRoundTrip(
        DiscordLayout(
            author = "한글 😀",
            fields = listOf(DiscordField("\"따옴표\"", "역슬래시 \\ 와\n개행 😀", true)),
            footer = "푸터 \"\\\n",
            timestamp = T,
        ),
    )

    @Test
    fun `read(write(x)) 는 x 와 같다 - 나노초 timestamp`() = assertRoundTrip(
        DiscordLayout(null, emptyList(), null, Instant.parse("2026-10-20T00:00:00.123456789Z")),
    )

    @Test
    fun `깨진 JSON 은 reason json 이다`() {
        assertReason("{bad", DiscordLayoutUnreadableReason.JSON)
        assertReason("", DiscordLayoutUnreadableReason.JSON)
    }

    @Test
    fun `v 가 1 이 아니면 reason version 이다`() {
        assertReason("""{"v":2,"author":null,"fields":[],"footer":null,"timestamp":null}""", DiscordLayoutUnreadableReason.VERSION)
    }

    @Test
    fun `fields 누락 칸 이름 빈 문자열 타입 불일치는 reason shape 이다`() {
        assertReason("""{"v":1,"author":null,"footer":null,"timestamp":null}""", DiscordLayoutUnreadableReason.SHAPE)
        assertReason(
            """{"v":1,"author":null,"fields":[{"name":"","value":"x","inline":true}],"footer":null,"timestamp":null}""",
            DiscordLayoutUnreadableReason.SHAPE,
        )
        assertReason(
            """{"v":1,"author":null,"fields":[{"name":"n","value":"x","inline":"yes"}],"footer":null,"timestamp":null}""",
            DiscordLayoutUnreadableReason.SHAPE,
        )
        assertReason(
            """{"v":1,"author":null,"fields":[],"footer":null,"timestamp":"not-a-time"}""",
            DiscordLayoutUnreadableReason.SHAPE,
        )
        assertReason("[1,2]", DiscordLayoutUnreadableReason.SHAPE)
    }

    @Test
    fun `예외 메시지에 입력 내용이 없고 cause 도 없다`() {
        val secret = "SECRET-VALUE-123"
        val json = """{"v":1,"author":null,"fields":[{"name":"$secret","value":"","inline":true}],"footer":null,"timestamp":null}"""

        val thrown = catchThrowable { DiscordLayoutJson.read(json) }

        assertThat(thrown).isInstanceOf(DiscordLayoutUnreadableException::class.java)
        assertThat(thrown.message).isEqualTo("discord layout unreadable: shape")
        assertThat(thrown.cause).isNull()
        val bad = catchThrowable { DiscordLayoutJson.read("{$secret") }
        assertThat(bad.message).isEqualTo("discord layout unreadable: json").doesNotContain(secret)
        assertThat(bad.cause).isNull()
    }

    private fun catchThrowable(block: () -> Unit): Throwable =
        assertThatThrownBy(block).actual()

    private fun assertRoundTrip(layout: DiscordLayout) {
        assertThat(DiscordLayoutJson.read(DiscordLayoutJson.write(layout))).isEqualTo(layout)
    }

    private fun assertReason(json: String, reason: DiscordLayoutUnreadableReason) {
        assertThatThrownBy { DiscordLayoutJson.read(json) }
            .isInstanceOfSatisfying(DiscordLayoutUnreadableException::class.java) {
                assertThat(it.reason).isEqualTo(reason)
            }
    }

    private companion object {
        val T: Instant = Instant.parse("2026-10-20T00:00:00Z")
    }
}
