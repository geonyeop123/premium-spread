package io.premiumspread.domain.position

import io.premiumspread.domain.ticker.DomainException

class InvalidPositionException(message: String) : DomainException(message)
