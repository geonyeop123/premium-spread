package io.premiumspread.interfaces.api

import io.premiumspread.application.position.PositionNotFoundException
import io.premiumspread.application.position.PremiumNotFoundException
import io.premiumspread.application.premium.TickerNotFoundException
import io.premiumspread.domain.InvalidTickerException
import io.premiumspread.domain.member.DuplicateEmailException
import io.premiumspread.domain.position.InvalidPositionException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
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

    @Nested
    inner class DuplicateEmail {

        @Test
        fun `중복 이메일 예외는 CONFLICT 상태와 한국어 메시지를 반환한다`() {
            val response = handler.handleDuplicateEmail(DuplicateEmailException("test@example.com"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(response.body!!.code).isEqualTo("DUPLICATE_EMAIL")
            assertThat(response.body!!.message).isEqualTo("이미 사용 중인 이메일입니다.")
            assertThat(response.body!!.message).doesNotContain("test@example.com")
            assertThat(response.body!!.timestamp).isEqualTo(fixedNow)
        }
    }

    @Nested
    inner class DomainException {

        @Test
        fun `도메인 예외는 BAD_REQUEST 상태와 한국어 메시지를 반환한다`() {
            val response = handler.handleDomainException(InvalidTickerException("bad ticker"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body!!.code).isEqualTo("INVALID_TICKER")
            assertThat(response.body!!.message).isEqualTo("유효하지 않은 티커입니다.")
        }

        @Test
        fun `포지션 도메인 예외는 올바른 에러 코드를 반환한다`() {
            val response = handler.handleDomainException(InvalidPositionException("bad position"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body!!.code).isEqualTo("INVALID_POSITION")
            assertThat(response.body!!.message).isEqualTo("유효하지 않은 포지션입니다.")
        }
    }

    @Nested
    inner class NotFound {

        @Test
        fun `티커 미발견은 NOT_FOUND와 한국어 메시지를 반환한다`() {
            val response = handler.handleTickerNotFound(TickerNotFoundException("BTC"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(response.body!!.code).isEqualTo("TICKER_NOT_FOUND")
            assertThat(response.body!!.message).isEqualTo("티커를 찾을 수 없습니다.")
        }

        @Test
        fun `포지션 미발견은 NOT_FOUND와 한국어 메시지를 반환한다`() {
            val response = handler.handlePositionNotFound(PositionNotFoundException("123"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(response.body!!.code).isEqualTo("POSITION_NOT_FOUND")
            assertThat(response.body!!.message).isEqualTo("포지션을 찾을 수 없습니다.")
        }

        @Test
        fun `프리미엄 미발견은 NOT_FOUND와 한국어 메시지를 반환한다`() {
            val response = handler.handlePremiumNotFound(PremiumNotFoundException("BTC"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            assertThat(response.body!!.code).isEqualTo("PREMIUM_NOT_FOUND")
            assertThat(response.body!!.message).isEqualTo("프리미엄 정보를 찾을 수 없습니다.")
        }
    }

    @Nested
    inner class IllegalArgument {

        @Test
        fun `잘못된 인자 예외는 BAD_REQUEST와 한국어 메시지를 반환한다`() {
            val response = handler.handleIllegalArgument(IllegalArgumentException("bad value"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body!!.code).isEqualTo("INVALID_ARGUMENT")
            assertThat(response.body!!.message).isEqualTo("잘못된 요청 값입니다.")
        }
    }

    @Nested
    inner class CatchAll {

        @Test
        fun `예상치 못한 예외는 INTERNAL_SERVER_ERROR와 일반 메시지를 반환한다`() {
            val response = handler.handleUnexpected(RuntimeException("unexpected error"))

            assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            assertThat(response.body!!.code).isEqualTo("INTERNAL_ERROR")
            assertThat(response.body!!.message).isEqualTo("서버 내부 오류가 발생했습니다.")
            assertThat(response.body!!.message).doesNotContain("unexpected error")
        }

        @Test
        fun `catch-all 응답에 내부 에러 메시지가 노출되지 않는다`() {
            val response = handler.handleUnexpected(
                RuntimeException("SQL injection attempt: SELECT * FROM members"),
            )

            assertThat(response.body!!.message).doesNotContain("SQL")
            assertThat(response.body!!.message).doesNotContain("SELECT")
        }
    }
}
