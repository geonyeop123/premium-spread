package io.premiumspread.interfaces.api

import io.premiumspread.application.position.PositionNotFoundException
import io.premiumspread.application.position.PremiumNotFoundException
import io.premiumspread.application.position.PremiumSnapshotNotAvailableException
import io.premiumspread.application.position.StalePremiumSnapshotException
import io.premiumspread.application.premium.TickerNotFoundException
import io.premiumspread.domain.DomainException
import io.premiumspread.domain.InvalidPremiumInputException
import io.premiumspread.domain.InvalidQuoteException
import io.premiumspread.domain.InvalidTickerException
import io.premiumspread.domain.member.DuplicateEmailException
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.position.InvalidPositionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicateEmail(ex: DuplicateEmailException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(code = "DUPLICATE_EMAIL", message = ERROR_MESSAGES["DUPLICATE_EMAIL"]!!))
    }

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(ex: DomainException): ResponseEntity<ErrorResponse> {
        val errorCode = when (ex) {
            is InvalidTickerException -> "INVALID_TICKER"
            is InvalidQuoteException -> "INVALID_QUOTE"
            is InvalidPremiumInputException -> "INVALID_PREMIUM_INPUT"
            is InvalidPositionException -> "INVALID_POSITION"
            else -> "DOMAIN_ERROR"
        }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = errorCode, message = ERROR_MESSAGES[errorCode] ?: "요청을 처리할 수 없습니다."))
    }

    @ExceptionHandler(TickerNotFoundException::class)
    fun handleTickerNotFound(ex: TickerNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "TICKER_NOT_FOUND", message = ERROR_MESSAGES["TICKER_NOT_FOUND"]!!))
    }

    @ExceptionHandler(PositionNotFoundException::class)
    fun handlePositionNotFound(ex: PositionNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "POSITION_NOT_FOUND", message = ERROR_MESSAGES["POSITION_NOT_FOUND"]!!))
    }

    @ExceptionHandler(PremiumNotFoundException::class)
    fun handlePremiumNotFound(ex: PremiumNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "PREMIUM_NOT_FOUND", message = ERROR_MESSAGES["PREMIUM_NOT_FOUND"]!!))
    }

    @ExceptionHandler(PremiumSnapshotNotAvailableException::class)
    fun handlePremiumSnapshotNotAvailable(ex: PremiumSnapshotNotAvailableException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "PREMIUM_SNAPSHOT_NOT_AVAILABLE",
                    message = ERROR_MESSAGES["PREMIUM_SNAPSHOT_NOT_AVAILABLE"]!!,
                ),
            )
    }

    @ExceptionHandler(StalePremiumSnapshotException::class)
    fun handleStalePremiumSnapshot(ex: StalePremiumSnapshotException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "STALE_PREMIUM_SNAPSHOT",
                    message = ERROR_MESSAGES["STALE_PREMIUM_SNAPSHOT"]!!,
                ),
            )
    }

    @ExceptionHandler(NotificationSubscriptionNotFoundException::class)
    fun handleNotificationSubscriptionNotFound(
        ex: NotificationSubscriptionNotFoundException,
    ): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ErrorResponse(
                    code = "NOTIFICATION_SUBSCRIPTION_NOT_FOUND",
                    message = ERROR_MESSAGES["NOTIFICATION_SUBSCRIPTION_NOT_FOUND"]!!,
                ),
            )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = "INVALID_ARGUMENT", message = ERROR_MESSAGES["INVALID_ARGUMENT"]!!))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = "INVALID_ARGUMENT", message = ERROR_MESSAGES["INVALID_ARGUMENT"]!!))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = "INVALID_ARGUMENT", message = ERROR_MESSAGES["INVALID_ARGUMENT"]!!))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ErrorResponse(code = "METHOD_NOT_ALLOWED", message = ERROR_MESSAGES["METHOD_NOT_ALLOWED"]!!))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        val code = if (status == HttpStatus.UNAUTHORIZED) "UNAUTHORIZED" else "INVALID_ARGUMENT"
        return ResponseEntity
            .status(status)
            .body(ErrorResponse(code = code, message = ERROR_MESSAGES[code] ?: ex.reason.orEmpty()))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("예상치 못한 오류 발생", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = "INTERNAL_ERROR", message = ERROR_MESSAGES["INTERNAL_ERROR"]!!))
    }

    companion object {
        val ERROR_MESSAGES = mapOf(
            "DUPLICATE_EMAIL" to "이미 사용 중인 이메일입니다.",
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
    val timestamp: Instant = Instant.now(),
)
