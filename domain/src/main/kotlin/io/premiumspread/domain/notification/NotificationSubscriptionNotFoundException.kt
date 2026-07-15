package io.premiumspread.domain.notification

import io.premiumspread.domain.DomainException

class NotificationSubscriptionNotFoundException(message: String) : DomainException(message)
