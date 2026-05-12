package io.premiumspread.domain.notification

import io.premiumspread.domain.DomainException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.NOT_FOUND)
class NotificationSubscriptionNotFoundException(message: String) : DomainException(message)
