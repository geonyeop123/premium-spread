package io.premiumspread.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)
        if (token != null) {
            val result = jwtTokenProvider.validateAndGetClaims(token)
            if (result is JwtValidationResult.Valid && result.tokenType == JwtTokenProvider.TOKEN_TYPE_ACCESS) {
                val userDetails = CustomUserDetails(
                    memberId = result.memberId,
                    email = result.email,
                    nickname = "",
                    encodedPassword = "",
                )
                val authentication = UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.authorities,
                )
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader(AUTHORIZATION_HEADER) ?: return null
        if (bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length)
        }
        return null
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
