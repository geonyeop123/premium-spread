package io.premiumspread.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

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
            .authorizeHttpRequests {
                it.requestMatchers(
                    AntPathRequestMatcher("/api/v1/members/register", "POST"),
                    AntPathRequestMatcher("/api/v1/members/login", "POST"),
                    AntPathRequestMatcher("/api/v1/premiums/**"),
                    AntPathRequestMatcher("/api/v1/tickers/**"),
                    AntPathRequestMatcher("/actuator/**"),
                    AntPathRequestMatcher("/swagger-ui/**"),
                    AntPathRequestMatcher("/v3/api-docs/**"),
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = "UTF-8"
                    objectMapper.writeValue(
                        response.writer,
                        mapOf("code" to "UNAUTHORIZED", "message" to "로그인이 필요합니다."),
                    )
                }
            }
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
            setSecurityContextRepository(HttpSessionSecurityContextRepository())
        }
    }
}
