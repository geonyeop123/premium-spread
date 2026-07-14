package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.member.PasswordEncoder

class BcryptPasswordEncoderAdapter(
    private val delegate: org.springframework.security.crypto.password.PasswordEncoder,
) : PasswordEncoder {
    override fun encode(rawPassword: String): String = delegate.encode(rawPassword)

    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        delegate.matches(rawPassword, encodedPassword)
}
