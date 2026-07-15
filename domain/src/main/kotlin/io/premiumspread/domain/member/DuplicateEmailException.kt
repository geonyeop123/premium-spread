package io.premiumspread.domain.member

import io.premiumspread.domain.DomainException

class DuplicateEmailException(message: String) : DomainException(message)
