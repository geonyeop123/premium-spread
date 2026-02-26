package io.premiumspread.infrastructure.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

data class CustomUserDetails(
    val memberId: Long,
    val email: String,
    val nickname: String,
    private val encodedPassword: String,
) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()
    override fun getPassword(): String = encodedPassword
    override fun getUsername(): String = email
}
