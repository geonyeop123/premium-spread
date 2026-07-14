package io.premiumspread.infrastructure.api.security

import io.premiumspread.domain.auth.TokenIssuer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

class JwtAuthenticationFilter(
    private val tokenIssuer: TokenIssuer,
    private val clock: Clock,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        resolveToken(request)
            ?.let { tokenIssuer.verifyAccess(it, clock.instant()) }
            ?.let { verified ->
                val authentication = UsernamePasswordAuthenticationToken(
                    verified.subject.memberId.toString(),
                    null,
                    emptyList(),
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? = request.getHeader(AUTHORIZATION_HEADER)
        ?.takeIf { it.startsWith(BEARER_PREFIX) }
        ?.removePrefix(BEARER_PREFIX)
        ?.takeIf(String::isNotBlank)

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
