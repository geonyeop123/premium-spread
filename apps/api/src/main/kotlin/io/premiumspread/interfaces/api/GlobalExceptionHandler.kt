package io.premiumspread.interfaces.api

import io.premiumspread.application.position.PositionNotFoundException
import io.premiumspread.application.position.PremiumNotFoundException
import io.premiumspread.application.ticker.TickerNotFoundException
import io.premiumspread.domain.position.InvalidPositionException
import io.premiumspread.domain.ticker.DomainException
import io.premiumspread.domain.ticker.InvalidPremiumInputException
import io.premiumspread.domain.ticker.InvalidQuoteException
import io.premiumspread.domain.ticker.InvalidTickerException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

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
            .body(ErrorResponse(code = errorCode, message = ex.message ?: "Domain error"))
    }

    @ExceptionHandler(TickerNotFoundException::class)
    fun handleTickerNotFound(ex: TickerNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "TICKER_NOT_FOUND", message = ex.message ?: "Ticker not found"))
    }

    @ExceptionHandler(PositionNotFoundException::class)
    fun handlePositionNotFound(ex: PositionNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "POSITION_NOT_FOUND", message = ex.message ?: "Position not found"))
    }

    @ExceptionHandler(PremiumNotFoundException::class)
    fun handlePremiumNotFound(ex: PremiumNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(code = "PREMIUM_NOT_FOUND", message = ex.message ?: "Premium not found"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(code = "INVALID_ARGUMENT", message = ex.message ?: "Invalid argument"))
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
)
