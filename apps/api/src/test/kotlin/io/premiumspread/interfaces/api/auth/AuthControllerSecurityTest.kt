package io.premiumspread.interfaces.api.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.premiumspread.infrastructure.security.JwtTokenProvider
import io.premiumspread.infrastructure.security.JwtValidationResult
import io.premiumspread.infrastructure.security.LoginSuccessHandler
import io.premiumspread.infrastructure.security.SecurityConfig
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Date

@WebMvcTest(controllers = [AuthController::class, AuthSecurityProbeController::class])
@Import(SecurityConfig::class, AuthControllerSecurityTest.JwtTestConfiguration::class)
class AuthControllerSecurityTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    fun `refresh POST는 인증 없이 컨트롤러에 도달하고 쿠키가 없으면 401을 반환한다`() {
        mockMvc.post("/api/v1/auth/refresh")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("INVALID_TOKEN") }
                jsonPath("$.message") { value("리프레시 토큰이 없습니다.") }
            }
    }

    @Test
    fun `refresh 공개 matcher는 POST exact path에만 적용한다`() {
        mockMvc.get("/api/v1/auth/refresh")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }

        mockMvc.post("/api/v1/auth/refresh/extra")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `actuator 공개 matcher는 liveness와 readiness GET exact path로 제한한다`() {
        mockMvc.get("/actuator/health/liveness")
            .andExpect { status { isOk() } }
        mockMvc.get("/actuator/health/readiness")
            .andExpect { status { isOk() } }

        mockMvc.get("/actuator/health")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
        mockMvc.post("/actuator/health/liveness")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `Authorization 헤더의 refresh token은 받지 않고 쿠키만 허용한다`() {
        val refreshToken = jwtTokenProvider.generateRefreshToken(1L, "member@example.com")

        mockMvc.post("/api/v1/auth/refresh") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $refreshToken")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_TOKEN") }
            jsonPath("$.message") { value("리프레시 토큰이 없습니다.") }
        }
    }

    @Test
    fun `잘못 서명된 refresh cookie는 401을 반환한다`() {
        val wrongProvider = provider(secret = "wrong-secret-key-must-be-at-least-32-bytes-long!!")
        val token = wrongProvider.generateRefreshToken(1L, "member@example.com")

        assertInvalidRefresh(token)
    }

    @Test
    fun `만료된 refresh cookie는 401을 반환한다`() {
        val token = expiredRefreshToken()

        assertInvalidRefresh(token)
    }

    @Test
    fun `access token을 refresh cookie로 보내면 401을 반환한다`() {
        val token = jwtTokenProvider.generateAccessToken(1L, "member@example.com")

        assertInvalidRefresh(token)
    }

    @Test
    fun `유효한 refresh cookie는 새 access token과 HttpOnly refresh cookie를 반환한다`() {
        val token = jwtTokenProvider.generateRefreshToken(7L, "member@example.com")

        val result = mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie(LoginSuccessHandler.REFRESH_TOKEN_COOKIE, token))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isString() }
            jsonPath("$.refreshToken") { doesNotExist() }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refresh_token=")) }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")) }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("SameSite=Strict")) }
        }.andReturn()

        val accessToken = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.response.contentAsString)["accessToken"]
            .asText()
        val claims = jwtTokenProvider.validateAndGetClaims(accessToken)
        assertThat(claims).isInstanceOf(JwtValidationResult.Valid::class.java)
        assertThat((claims as JwtValidationResult.Valid).tokenType)
            .isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS)
    }

    private fun assertInvalidRefresh(token: String) {
        mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie(LoginSuccessHandler.REFRESH_TOKEN_COOKIE, token))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_TOKEN") }
            jsonPath("$.message") { value("유효하지 않은 리프레시 토큰입니다.") }
        }
    }

    @TestConfiguration
    class JwtTestConfiguration {
        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = provider()
    }

    companion object {
        private const val SECRET = "test-secret-key-must-be-at-least-32-bytes-long!!"

        private fun provider(
            secret: String = SECRET,
            accessExpiryMs: Long = 1_800_000L,
            refreshExpiryMs: Long = 604_800_000L,
        ): JwtTokenProvider = JwtTokenProvider(
            secretKeyString = secret,
            accessTokenExpiryMs = accessExpiryMs,
            refreshTokenExpiryMs = refreshExpiryMs,
            issuer = "premium-spread",
            audience = "premium-spread-api",
            clockSkewSeconds = 0L,
        )

        private fun expiredRefreshToken(): String {
            val now = Date()
            return Jwts.builder()
                .issuer("premium-spread")
                .subject("member@example.com")
                .audience().add("premium-spread-api").and()
                .claim(JwtTokenProvider.CLAIM_MEMBER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_TOKEN_TYPE, JwtTokenProvider.TOKEN_TYPE_REFRESH)
                .issuedAt(now)
                .expiration(Date(now.time - 1_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.toByteArray()))
                .compact()
        }
    }
}

@RestController
class AuthSecurityProbeController {
    @GetMapping("/actuator/health/liveness", "/actuator/health/readiness")
    fun probe(): Map<String, String> = mapOf("status" to "UP")
}
