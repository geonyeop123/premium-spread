package io.premiumspread.interfaces.api.notification

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import io.premiumspread.application.notification.NotificationSubscriptionFacade
import io.premiumspread.application.notification.NotificationSubscriptionResult
import io.premiumspread.domain.notification.NotificationSubscriptionNotFoundException
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.infrastructure.security.CustomUserDetails
import io.premiumspread.infrastructure.security.CustomUserDetailsService
import io.premiumspread.infrastructure.security.JwtTokenProvider
import io.premiumspread.infrastructure.security.JwtValidationResult
import io.premiumspread.infrastructure.security.SecurityConfig
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

@WebMvcTest(NotificationSubscriptionController::class)
@Import(SecurityConfig::class, WebMvcConfig::class)
class NotificationSubscriptionControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var facade: NotificationSubscriptionFacade

    @MockkBean(relaxed = true)
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockkBean(relaxed = true)
    private lateinit var userDetailsService: CustomUserDetailsService

    private val testUserDetails = CustomUserDetails(
        memberId = 1L,
        email = "u@x.com",
        nickname = "user",
        encodedPassword = "pw",
    )

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        every { jwtTokenProvider.validateAndGetClaims(any()) } returns JwtValidationResult.Invalid
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `POST 정상 201`() {
        every { facade.create(any()) } returns NotificationSubscriptionResult.Detail(
            id = 100L,
            memberId = 1L,
            symbol = "BTC",
            direction = ThresholdDirection.ABOVE,
            threshold = BigDecimal("5.00"),
            status = SubscriptionStatus.ACTIVE,
        )

        mockMvc.post("/api/v1/notifications/subscriptions") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"BTC","direction":"ABOVE","threshold":5.00}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(100) }
            jsonPath("$.symbol") { value("BTC") }
            jsonPath("$.status") { value("ACTIVE") }
        }

        verify { facade.create(any()) }
    }

    @Test
    fun `POST 유효성 실패 400`() {
        mockMvc.post("/api/v1/notifications/subscriptions") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"","direction":"ABOVE","threshold":5.00}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `POST 미인증 401`() {
        mockMvc.post("/api/v1/notifications/subscriptions") {
            with(anonymous())
            contentType = MediaType.APPLICATION_JSON
            content = """{"symbol":"BTC","direction":"ABOVE","threshold":5.00}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET 목록 200`() {
        every { facade.findAllByMemberId(1L) } returns emptyList()

        mockMvc.get("/api/v1/notifications/subscriptions") {
            with(user(testUserDetails))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `GET 목록 미인증 401`() {
        mockMvc.get("/api/v1/notifications/subscriptions") {
            with(anonymous())
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET 단건 본인 200`() {
        every { facade.findByIdAndMemberId(10L, 1L) } returns NotificationSubscriptionResult.Detail(
            id = 10L,
            memberId = 1L,
            symbol = "BTC",
            direction = ThresholdDirection.ABOVE,
            threshold = BigDecimal("5.00"),
            status = SubscriptionStatus.ACTIVE,
        )

        mockMvc.get("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(10) }
            jsonPath("$.symbol") { value("BTC") }
        }
    }

    @Test
    fun `GET 단건 타인 또는 없음 404`() {
        every {
            facade.findByIdAndMemberId(10L, 1L)
        } throws NotificationSubscriptionNotFoundException("not found")

        mockMvc.get("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET 단건 미인증 401`() {
        mockMvc.get("/api/v1/notifications/subscriptions/10") {
            with(anonymous())
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `PATCH 본인 200`() {
        every { facade.update(any()) } returns NotificationSubscriptionResult.Detail(
            id = 10L,
            memberId = 1L,
            symbol = "BTC",
            direction = ThresholdDirection.BELOW,
            threshold = BigDecimal("-2.00"),
            status = SubscriptionStatus.INACTIVE,
        )

        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"INACTIVE","direction":"BELOW","threshold":-2.00}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("INACTIVE") }
            jsonPath("$.direction") { value("BELOW") }
        }
    }

    @Test
    fun `PATCH 타인 404`() {
        every { facade.update(any()) } throws NotificationSubscriptionNotFoundException("not found")

        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"INACTIVE"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `PATCH 미인증 401`() {
        mockMvc.patch("/api/v1/notifications/subscriptions/10") {
            with(anonymous())
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"INACTIVE"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DELETE 본인 204`() {
        every { facade.delete(10L, 1L) } returns Unit

        mockMvc.delete("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `DELETE 타인 404`() {
        every { facade.delete(10L, 1L) } throws NotificationSubscriptionNotFoundException("not found")

        mockMvc.delete("/api/v1/notifications/subscriptions/10") {
            with(user(testUserDetails))
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `DELETE 미인증 401`() {
        mockMvc.delete("/api/v1/notifications/subscriptions/10") {
            with(anonymous())
        }.andExpect { status { isUnauthorized() } }
    }
}
