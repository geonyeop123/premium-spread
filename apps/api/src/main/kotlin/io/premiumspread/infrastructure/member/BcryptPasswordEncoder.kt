package io.premiumspread.infrastructure.member

import io.premiumspread.domain.member.PasswordEncoder
import org.springframework.stereotype.Component

@Component
class BcryptPasswordEncoder(
    private val springPasswordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
) : PasswordEncoder {

    override fun encode(rawPassword: String): String {
        return springPasswordEncoder.encode(rawPassword)
    }

    override fun matches(rawPassword: String, encodedPassword: String): Boolean {
        return springPasswordEncoder.matches(rawPassword, encodedPassword)
    }
}
