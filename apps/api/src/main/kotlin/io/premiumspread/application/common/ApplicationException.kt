package io.premiumspread.application.common

enum class ApplicationError {
    AUTHENTICATION_FAILED,
    INVALID_REFRESH_TOKEN,
    DUPLICATE_EMAIL,
    MEMBER_NOT_FOUND,
    INVALID_TICKER,
    INVALID_QUOTE,
    INVALID_PREMIUM_INPUT,
    INVALID_TRACKING,
    DOMAIN_ERROR,
    TICKER_NOT_FOUND,
    TRACKING_NOT_FOUND,
    TRACKING_CLOSE_SNAPSHOT_UNAVAILABLE,
    PREMIUM_NOT_FOUND,
    PREMIUM_SNAPSHOT_NOT_AVAILABLE,
    STALE_PREMIUM_SNAPSHOT,
    NOTIFICATION_SUBSCRIPTION_NOT_FOUND,
}

class ApplicationException(val error: ApplicationError, cause: Throwable? = null) : RuntimeException(error.name, cause)
