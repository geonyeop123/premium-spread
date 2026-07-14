package io.premiumspread.domain

open class DomainException(message: String) : RuntimeException(message)

class InvalidTickerException(message: String) : DomainException(message)

class InvalidQuoteException(message: String) : DomainException(message)

class InvalidPremiumInputException(message: String) : DomainException(message)
