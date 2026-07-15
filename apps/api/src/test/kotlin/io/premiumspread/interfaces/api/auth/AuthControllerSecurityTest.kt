package io.premiumspread.interfaces.api.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import io.premiumspread.application.auth.AuthCookieContract
import io.premiumspread.application.auth.AuthCriteria
import io.premiumspread.application.auth.AuthFacade
import io.premiumspread.application.auth.AuthResult
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.CookieValue

@WebMvcTest(
    controllers = [AuthController::class],
    properties = [
        "jwt.secret-key=test-secret-key-must-be-at-least-32-bytes-long!!",
        "jwt.issuer=premium-spread-test",
        "jwt.audience=premium-spread-api-test",
        "jwt.access-token-expiry-ms=1800000",
        "jwt.refresh-token-expiry-ms=604800000",
        "jwt.clock-skew-seconds=30",
        "auth.cookie.secure=false",
        "auth.cors.allowed-origins[0]=http://localhost:3000",
        "auth.cors.allowed-methods[0]=POST",
        "auth.cors.allowed-headers[0]=Content-Type",
        "auth.refresh.hmac-key=test-refresh-hmac-key-must-be-at-least-32-bytes!!",
    ],
)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerSecurityTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var authFacade: AuthFacade

    @Test
    fun `로그인은 access token과 설정 기반 refresh cookie를 반환한다`() {
        every { authFacade.login(AuthCriteria.Login("member@example.com", "password123")) } returns AuthResult.Login(
            accessToken = "access",
            id = 1L,
            email = "member@example.com",
            nickname = "member",
            refreshCookie = cookie("refresh"),
        )

        mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"member@example.com","password":"password123"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("access") }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refresh_token=refresh")) }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Path=/api/v1/auth")) }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")) }
        }
    }

    @Test
    fun `refresh는 access token과 회전된 cookie를 반환한다`() {
        every { authFacade.refresh(AuthCriteria.Refresh("old-refresh")) } returns AuthResult.Refresh(
            accessToken = "new-access",
            refreshCookie = cookie("new-refresh"),
        )

        mockMvc.post("/api/v1/auth/refresh") {
            cookie(Cookie("refresh_token", "old-refresh"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("new-access") }
            jsonPath("$.refreshToken") { doesNotExist() }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refresh_token=new-refresh")) }
        }
    }

    @Test
    fun `logout은 session revoke 후 cookie를 만료하고 204 empty를 반환한다`() {
        every { authFacade.logout(AuthCriteria.Logout("refresh")) } returns AuthResult.Logout(cookie("", 0))

        mockMvc.post("/api/v1/auth/logout") {
            cookie(Cookie("refresh_token", "refresh"))
        }.andExpect {
            status { isNoContent() }
            content { string("") }
            header { string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")) }
        }
        verify(exactly = 1) { authFacade.logout(AuthCriteria.Logout("refresh")) }
    }

    @Test
    fun `CookieValue annotation은 공용 cookie 이름 property 계약을 사용한다`() {
        val placeholders = AuthController::class.java.declaredMethods
            .flatMap { method -> method.parameterAnnotations.toList() }
            .flatMap { annotations -> annotations.toList() }
            .filterIsInstance<CookieValue>()
            .map(CookieValue::name)

        assertThat(placeholders).containsOnly(AuthCookieContract.NAME_PLACEHOLDER)
    }

    private fun cookie(value: String, maxAge: Long = 604_800): AuthResult.Cookie = AuthResult.Cookie(
        name = "refresh_token",
        value = value,
        path = "/api/v1/auth",
        domain = null,
        secure = false,
        httpOnly = true,
        sameSite = "Strict",
        maxAgeSeconds = maxAge,
    )
}
