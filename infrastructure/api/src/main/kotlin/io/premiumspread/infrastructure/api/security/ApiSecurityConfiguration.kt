package io.premiumspread.infrastructure.api.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.domain.auth.TokenIssuer
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain::class)
    fun securityFilterChain(
        http: HttpSecurity,
        tokenIssuer: TokenIssuer,
        clock: Clock,
        corsProperties: CorsProperties,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        val publicMatchers = PublicEndpointPolicy.endpoints.map(PublicEndpoint::requestMatcher).toTypedArray()
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(corsProperties)) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.requestMatchers(*publicMatchers).permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = Charsets.UTF_8.name()
                    objectMapper.writeValue(
                        response.writer,
                        mapOf("code" to "UNAUTHORIZED", "message" to "로그인이 필요합니다."),
                    )
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(CookieEndpointOriginFilter(corsProperties), UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(JwtAuthenticationFilter(tokenIssuer, clock), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins.toMutableList()
            allowedMethods = properties.allowedMethods.toMutableList()
            allowedHeaders = properties.allowedHeaders.toMutableList()
            allowCredentials = properties.allowCredentials
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", configuration) }
    }
}
