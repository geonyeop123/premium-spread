package io.premiumspread.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IngestionModeConfigTest {

    @Test
    fun `정상 mode 값(rest, websocket)은 검증 통과`() {
        listOf(
            "rest" to "rest",
            "rest" to "websocket",
            "websocket" to "rest",
            "websocket" to "websocket",
        ).forEach { (b, t) ->
            assertThatCode { IngestionModeConfig(b, t).validate() }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `invalid binance mode면 IllegalArgumentException 발생`() {
        assertThatThrownBy { IngestionModeConfig("foobar", "rest").validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("binance.mode")
    }

    @Test
    fun `invalid bithumb mode면 IllegalArgumentException 발생`() {
        assertThatThrownBy { IngestionModeConfig("rest", "WEBSOCKET").validate() } // 대소문자 strict
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("bithumb.mode")
    }

    @Test
    fun `빈 문자열도 invalid로 분류`() {
        assertThatThrownBy { IngestionModeConfig("", "rest").validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
