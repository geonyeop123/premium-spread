package io.premiumspread.monitoring

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LogAlertService")
class LogAlertServiceTest {

    private val sut = LogAlertService()

    @Test
    fun `sendAlert 호출 시 예외 없이 로그를 기록한다`() {
        assertThatCode {
            sut.sendAlert("테스트 경고 메시지", AlertService.Severity.WARNING)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `sendCriticalAlert 호출 시 예외 없이 로그를 기록한다`() {
        assertThatCode {
            sut.sendCriticalAlert("테스트 심각 메시지")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `모든 Severity 수준에서 예외 없이 동작한다`() {
        AlertService.Severity.entries.forEach { severity ->
            assertThatCode {
                sut.sendAlert("severity=$severity 테스트", severity)
            }.doesNotThrowAnyException()
        }
    }
}
