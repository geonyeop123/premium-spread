package io.premiumspread.interfaces.api

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Clock
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler(
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApplicationException::class)
    fun handleApplicationException(ex: ApplicationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(statusOf(ex.error))
            .body(error(ex.error.name))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(error("INVALID_ARGUMENT"))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(error("INVALID_ARGUMENT"))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(error("METHOD_NOT_ALLOWED"))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        val code = if (status == HttpStatus.UNAUTHORIZED) "UNAUTHORIZED" else "INVALID_ARGUMENT"
        return ResponseEntity
            .status(status)
            .body(error(code, ERROR_MESSAGES[code] ?: ex.reason.orEmpty()))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("예상치 못한 오류 발생", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(error("INTERNAL_ERROR"))
    }

    private fun error(code: String, message: String = ERROR_MESSAGES.getValue(code)): ErrorResponse =
        ErrorResponse(code = code, message = message, timestamp = clock.instant())

    private fun statusOf(error: ApplicationError): HttpStatus = when (error) {
        ApplicationError.AUTHENTICATION_FAILED,
        ApplicationError.INVALID_REFRESH_TOKEN -> HttpStatus.UNAUTHORIZED

        ApplicationError.MEMBER_NOT_FOUND,
        ApplicationError.TICKER_NOT_FOUND,
        ApplicationError.POSITION_NOT_FOUND,
        ApplicationError.PREMIUM_NOT_FOUND,
        ApplicationError.NOTIFICATION_SUBSCRIPTION_NOT_FOUND -> HttpStatus.NOT_FOUND

        ApplicationError.DUPLICATE_EMAIL,
        ApplicationError.PREMIUM_SNAPSHOT_NOT_AVAILABLE,
        ApplicationError.STALE_PREMIUM_SNAPSHOT -> HttpStatus.CONFLICT

        ApplicationError.INVALID_TICKER,
        ApplicationError.INVALID_QUOTE,
        ApplicationError.INVALID_PREMIUM_INPUT,
        ApplicationError.INVALID_POSITION,
        ApplicationError.DOMAIN_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY
    }

    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleRequestBinding(ex: Exception): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(error("INVALID_ARGUMENT"))
    }

    companion object {
        val ERROR_MESSAGES = mapOf(
            "AUTHENTICATION_FAILED" to "인증에 실패했습니다.",
            "INVALID_REFRESH_TOKEN" to "유효하지 않은 리프레시 토큰입니다.",
            "DUPLICATE_EMAIL" to "이미 사용 중인 이메일입니다.",
            "MEMBER_NOT_FOUND" to "회원을 찾을 수 없습니다.",
            "INVALID_TICKER" to "유효하지 않은 티커입니다.",
            "INVALID_QUOTE" to "유효하지 않은 시세입니다.",
            "INVALID_PREMIUM_INPUT" to "유효하지 않은 프리미엄 입력값입니다.",
            "INVALID_POSITION" to "유효하지 않은 포지션입니다.",
            "DOMAIN_ERROR" to "요청을 처리할 수 없습니다.",
            "TICKER_NOT_FOUND" to "티커를 찾을 수 없습니다.",
            "POSITION_NOT_FOUND" to "포지션을 찾을 수 없습니다.",
            "PREMIUM_NOT_FOUND" to "프리미엄 정보를 찾을 수 없습니다.",
            "PREMIUM_SNAPSHOT_NOT_AVAILABLE" to "해당 종목의 최신 프리미엄 스냅샷이 없습니다.",
            "STALE_PREMIUM_SNAPSHOT" to "프리미엄 스냅샷이 오래되어 사용할 수 없습니다. 잠시 후 다시 시도해주세요.",
            "NOTIFICATION_SUBSCRIPTION_NOT_FOUND" to "알림 구독을 찾을 수 없습니다.",
            "UNAUTHORIZED" to "로그인이 필요합니다.",
            "INVALID_ARGUMENT" to "잘못된 요청 값입니다.",
            "METHOD_NOT_ALLOWED" to "해당 경로에서 지원하지 않는 메서드입니다.",
            "INTERNAL_ERROR" to "서버 내부 오류가 발생했습니다.",
        )
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Instant,
)
