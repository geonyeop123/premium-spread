package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun passwordEncoder(): org.springframework.security.crypto.password.PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager =
        config.authenticationManager

    @Bean
    fun securityFilterChain(http: HttpSecurity, authenticationManager: AuthenticationManager): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .addFilterAt(jsonLoginFilter(authenticationManager), UsernamePasswordAuthenticationFilter::class.java)
            .logout {
                it.logoutUrl("/api/v1/members/logout")
                it.logoutSuccessHandler(CustomLogoutSuccessHandler(objectMapper))
            }
        return http.build()
    }

    private fun jsonLoginFilter(authenticationManager: AuthenticationManager): JsonLoginFilter {
        return JsonLoginFilter(objectMapper, authenticationManager).apply {
            setAuthenticationSuccessHandler(LoginSuccessHandler(objectMapper))
            setAuthenticationFailureHandler(LoginFailureHandler(objectMapper))
        }
    }
}
