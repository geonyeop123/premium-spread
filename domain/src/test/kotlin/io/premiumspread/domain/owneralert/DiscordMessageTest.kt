package io.premiumspread.domain.owneralert

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DiscordMessageTest {

    @Test
    fun `제목 255자는 그대로 둔다`() {
        val title = "가".repeat(255)
        assertThat(DiscordMessage.of(title, "d", 0).title).isEqualTo(title)
    }

    @Test
    fun `제목 256자는 255자로 줄이고 말줄임표로 끝난다`() {
        val title = DiscordMessage.of("가".repeat(256), "d", 0).title
        assertThat(title).hasSize(255).endsWith(DiscordMessage.ELLIPSIS)
        assertThat(title).isEqualTo("가".repeat(254) + "…")
    }

    @Test
    fun `설명 4096자는 그대로 둔다`() {
        val description = "a".repeat(4096)
        assertThat(DiscordMessage.of("t", description, 0).description).isEqualTo(description)
    }

    @Test
    fun `설명 4097자는 4096자로 줄이고 말줄임표로 끝난다`() {
        val description = DiscordMessage.of("t", "a".repeat(4097), 0).description
        assertThat(description).hasSize(4096).endsWith(DiscordMessage.ELLIPSIS)
    }

    @Test
    fun `자르는 지점이 surrogate pair 앞 절반이면 쌍을 쪼개지 않는다`() {
        // 254번째 code unit(index 253)부터 이모지가 시작해 index 253 이 high surrogate 다.
        val text = "a".repeat(253) + "😀" + "b".repeat(10)
        val truncated = DiscordMessage.of(text, "d", 0).title
        assertThat(truncated.length).isLessThanOrEqualTo(255)
        assertThat(truncated).endsWith(DiscordMessage.ELLIPSIS)
        assertThat(truncated.dropLast(1).any { Character.isSurrogate(it) }).isFalse()
        assertThat(truncated).isEqualTo("a".repeat(253) + "…")
    }

    @Test
    fun `이모지가 온전히 들어가는 지점에서는 이모지를 보존한다`() {
        val text = "a".repeat(252) + "😀" + "b".repeat(10)
        assertThat(DiscordMessage.truncate(text, 255)).isEqualTo("a".repeat(252) + "😀" + "…")
    }

    @Test
    fun `상한 이하 문자열은 truncate 가 그대로 돌려준다`() {
        assertThat(DiscordMessage.truncate("abc", 3)).isEqualTo("abc")
    }

    @Test
    fun `기본 생성자는 상한과 색 범위를 검증한다`() {
        assertThatThrownBy { DiscordMessage(" ", "d", 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiscordMessage("a".repeat(256), "d", 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiscordMessage("t", "a".repeat(4097), 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiscordMessage("t", "d", -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DiscordMessage("t", "d", 0x1000000) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(DiscordMessage("t", "d", 0).color).isEqualTo(0)
        assertThat(DiscordMessage("t", "d", 0xFFFFFF).color).isEqualTo(0xFFFFFF)
    }

    @Test
    fun `제목과 설명 상한의 합은 embed 전체 상한보다 작다`() {
        assertThat(DiscordMessage.TITLE_MAX + DiscordMessage.DESCRIPTION_MAX).isLessThan(DiscordMessage.EMBED_TOTAL_MAX)
    }
}
