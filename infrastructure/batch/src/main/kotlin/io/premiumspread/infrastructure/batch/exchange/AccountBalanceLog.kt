package io.premiumspread.infrastructure.batch.exchange

import io.premiumspread.domain.accountpnl.AccountReadFailureCategory
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException

internal enum class BalanceItem { BITHUMB_KRW, BITHUMB_USDT_PRICE, BINANCE_USDT_ASSET }

internal enum class BalanceUnavailableReason { MISSING, NOT_NUMBER, NEGATIVE, NOT_POSITIVE, REQUEST_FAILED, TICKER_FALLBACK }

/**
 * 잔고·시세 칸을 채우지 못한 사유 로그. 금액·key·secret·query·URL·응답 본문·예외 메시지는 담지 않고
 * 항목·사유·범주·예외 클래스 이름만 남긴다.
 */
internal object AccountBalanceLog {
    private val log = LoggerFactory.getLogger("AccountBalanceLog")
    private const val MAX_CAUSE_DEPTH = 16
    private const val NONE = "-"

    fun unavailable(
        item: BalanceItem,
        reason: BalanceUnavailableReason,
        category: AccountReadFailureCategory? = null,
        errorClass: String? = null,
    ) {
        log.warn(
            "Account balance unavailable: item={}, reason={}, category={}, error={}",
            item,
            reason,
            category ?: NONE,
            errorClass ?: NONE,
        )
    }

    /** 원인 체인에 인터럽트·취소가 있거나 현재 스레드가 인터럽트 상태이면 true. */
    fun isCancellation(error: Throwable): Boolean =
        Thread.currentThread().isInterrupted ||
            chain(error).any { it is InterruptedException || it is CancellationException }

    /** 원인 체인 맨 끝 예외의 simpleName. */
    fun rootErrorClass(error: Throwable): String = chain(error).last().javaClass.simpleName

    private fun chain(error: Throwable): List<Throwable> {
        val result = mutableListOf(error)
        var current = error
        while (result.size < MAX_CAUSE_DEPTH) {
            val cause = current.cause
            if (cause == null || cause === current) break
            result += cause
            current = cause
        }
        return result
    }
}
