package io.premiumspread.interfaces.api

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GlobalExceptionHandlerTest {
    private lateinit var handler: GlobalExceptionHandler
    private val fixedNow = Instant.parse("2026-07-14T03:00:00Z")

    @BeforeEach
    fun setUp() {
        handler = GlobalExceptionHandler(Clock.fixed(fixedNow, ZoneOffset.UTC))
    }

    @Test
    fun `중복 이메일 Application 오류는 409와 안정된 메시지를 반환한다`() {
        val response = handler.handleApplicationException(
            ApplicationException(ApplicationError.DUPLICATE_EMAIL, IllegalStateException("test@example.com")),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("DUPLICATE_EMAIL")
        assertThat(response.body!!.message).isEqualTo("이미 사용 중인 이메일입니다.")
        assertThat(response.body!!.message).doesNotContain("test@example.com")
        assertThat(response.body!!.timestamp).isEqualTo(fixedNow)
    }

    @Test
    fun `도메인 유효성 Application 오류는 422를 반환한다`() {
        val response = handler.handleApplicationException(ApplicationException(ApplicationError.INVALID_POSITION))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!.code).isEqualTo("INVALID_POSITION")
    }

    @Test
    fun `미발견 Application 오류는 404를 반환한다`() {
        val response = handler.handleApplicationException(ApplicationException(ApplicationError.POSITION_NOT_FOUND))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.message).isEqualTo("포지션을 찾을 수 없습니다.")
    }

    @Test
    fun `인증 Application 오류는 401을 반환한다`() {
        val response = handler.handleApplicationException(ApplicationException(ApplicationError.INVALID_REFRESH_TOKEN))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `JSON transport 오류는 400을 반환한다`() {
        val response = handler.handleHttpMessageNotReadable(
            HttpMessageNotReadableException("bad json", MockHttpInputMessage(byteArrayOf())),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.code).isEqualTo("INVALID_ARGUMENT")
    }

    @Test
    fun `예상치 못한 예외는 내부 정보를 숨긴 500을 반환한다`() {
        val response = handler.handleUnexpected(RuntimeException("SELECT secret"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body!!.code).isEqualTo("INTERNAL_ERROR")
        assertThat(response.body!!.message).doesNotContain("SELECT")
    }
}
