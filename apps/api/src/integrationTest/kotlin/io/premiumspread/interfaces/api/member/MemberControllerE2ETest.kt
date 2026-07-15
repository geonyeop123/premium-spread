package io.premiumspread.interfaces.api.member

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.PasswordEncoder
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class MemberControllerE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val redisTemplate: StringRedisTemplate,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        redisTemplate.execute { it.serverCommands().flushAll() }
    }

    private fun createMember(
        email: String = "test@example.com",
        rawPassword: String = "password123",
    ): Member = memberRepository.save(
        Member.create(
            email = email,
            encodedPassword = passwordEncoder.encode(rawPassword),
        ),
    )

    private fun loginResult(email: String, password: String): MvcResult {
        val request = mapOf("email" to email, "password" to password)
        return mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isOk() } }.andReturn()
    }

    private fun login(email: String, password: String): String {
        val result = loginResult(email, password)
        val body = objectMapper.readTree(result.response.contentAsString)
        return body["accessToken"].asText()
    }

    private fun loginRefreshCookie(email: String, password: String): Cookie {
        val result = loginResult(email, password)

        return requireNotNull(result.response.getCookie(REFRESH_COOKIE_NAME)) {
            "로그인 응답에 refresh_token 쿠키가 없습니다."
        }
    }

    private fun refresh(cookie: Cookie): MvcResult = mockMvc.post("/api/v1/auth/refresh") {
        cookie(cookie)
    }.andReturn()

    // -- POST /api/v1/members/register --

    @Nested
    inner class Register {

        @Test
        fun `회원가입 성공 - 201 반환`() {
            val request = mapOf(
                "email" to "new@example.com",
                "password" to "password123",
            )

            mockMvc.post("/api/v1/members/register") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { isNumber() }
                jsonPath("$.email") { value("new@example.com") }
                jsonPath("$.nickname") { value("new") }
                jsonPath("$.status") { value("ACTIVE") }
            }
        }

        @Test
        fun `중복 이메일로 가입 시 409 반환`() {
            createMember(email = "dup@example.com")

            val request = mapOf(
                "email" to "dup@example.com",
                "password" to "password123",
            )

            mockMvc.post("/api/v1/members/register") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("DUPLICATE_EMAIL") }
            }
        }
    }

    // -- POST /api/v1/members/login --

    @Nested
    inner class Login {

        @Test
        fun `로그인 성공 - 200 + accessToken 및 회원 정보 반환`() {
            createMember(email = "login@example.com", rawPassword = "password123")

            val request = mapOf(
                "email" to "login@example.com",
                "password" to "password123",
            )

            mockMvc.post("/api/v1/members/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isOk() }
                jsonPath("$.accessToken") { isString() }
                jsonPath("$.email") { value("login@example.com") }
                jsonPath("$.nickname") { value("login") }
            }
        }

        @Test
        fun `존재하지 않는 이메일로 로그인 시 401 반환`() {
            val request = mapOf(
                "email" to "unknown@example.com",
                "password" to "password123",
            )

            mockMvc.post("/api/v1/members/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHENTICATION_FAILED") }
            }
        }

        @Test
        fun `잘못된 비밀번호로 로그인 시 401 반환`() {
            createMember(email = "test@example.com", rawPassword = "password123")

            val request = mapOf(
                "email" to "test@example.com",
                "password" to "wrongpassword",
            )

            mockMvc.post("/api/v1/members/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHENTICATION_FAILED") }
            }
        }
    }

    // -- POST /api/v1/auth/refresh --

    @Nested
    inner class Refresh {

        @Test
        fun `실제 로그인 응답의 refresh cookie만으로 access token을 재발급한다`() {
            createMember(email = "refresh@example.com", rawPassword = "password123")
            val refreshCookie = loginRefreshCookie("refresh@example.com", "password123")

            mockMvc.post("/api/v1/auth/refresh") {
                cookie(refreshCookie)
            }.andExpect {
                status { isOk() }
                jsonPath("$.accessToken") { isString() }
                jsonPath("$.refreshToken") { doesNotExist() }
                cookie { exists(REFRESH_COOKIE_NAME) }
                cookie { httpOnly(REFRESH_COOKIE_NAME, true) }
                cookie { secure(REFRESH_COOKIE_NAME, false) }
                header { string("Set-Cookie", org.hamcrest.Matchers.containsString("Path=/api/v1/auth")) }
                header { string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")) }
                header { string("Set-Cookie", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Domain="))) }
            }
        }

        @Test
        fun `access token을 refresh cookie로 제출하면 거부한다`() {
            createMember(email = "access-as-refresh@example.com")
            val accessToken = login("access-as-refresh@example.com", "password123")

            mockMvc.post("/api/v1/auth/refresh") {
                cookie(Cookie(REFRESH_COOKIE_NAME, accessToken))
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
            }
        }

        @Test
        fun `회전된 이전 refresh는 거부하지만 승자 token은 계속 회전할 수 있다`() {
            createMember(email = "rotate@example.com")
            val first = loginRefreshCookie("rotate@example.com", "password123")
            val firstRefresh = refresh(first)
            assertThat(firstRefresh.response.status).isEqualTo(200)
            val winner = requireNotNull(firstRefresh.response.getCookie(REFRESH_COOKIE_NAME))

            assertThat(refresh(first).response.status).isEqualTo(401)
            assertThat(refresh(winner).response.status).isEqualTo(200)
        }

        @RepeatedTest(3)
        fun `동시 refresh는 하나만 성공하고 승자 session은 유지된다`() {
            val member = createMember(email = "concurrent@example.com")
            val original = loginRefreshCookie("concurrent@example.com", "password123")
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val requests = List(2) {
                    executor.submit(
                        Callable {
                            check(start.await(10, TimeUnit.SECONDS))
                            refresh(Cookie(REFRESH_COOKIE_NAME, original.value))
                        },
                    )
                }
                start.countDown()
                val results = requests.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(results.map { it.response.status }.sorted()).containsExactly(200, 401)

                val winner = results.single { it.response.status == 200 }
                    .response.getCookie(REFRESH_COOKIE_NAME)
                assertThat(winner).isNotNull
                val winnerFollowUp = refresh(requireNotNull(winner))
                assertThat(winnerFollowUp.response.status)
                    .withFailMessage { refreshSessionDiagnostics(member.id, winnerFollowUp.response.status) }
                    .isEqualTo(200)
            } finally {
                executor.shutdownNow()
            }
        }

        private fun refreshSessionDiagnostics(memberId: Long, status: Int): String {
            val key = "auth:refresh:{$memberId}"
            val state = redisTemplate.opsForHash<String, String>().entries(key)
            return buildString {
                append("winner refresh failed: status=").append(status)
                append(", keyPresent=").append(state.isNotEmpty())
                append(", generation=").append(state["generation"] ?: "missing")
                append(", currentProofPresent=")
                    .append(state["currentHash"] != null && state["currentJti"] != null)
                append(", previousProofPresent=")
                    .append(state["previousHash"] != null && state["previousJti"] != null)
                append(", ttlMs=").append(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS))
            }
        }

        @Test
        fun `이전 로그인 family token은 현재 로그인 session을 revoke하지 않는다`() {
            createMember(email = "family@example.com")
            val previousFamily = loginRefreshCookie("family@example.com", "password123")
            val currentFamily = loginRefreshCookie("family@example.com", "password123")

            assertThat(refresh(previousFamily).response.status).isEqualTo(401)
            assertThat(refresh(currentFamily).response.status).isEqualTo(200)
        }

        @Test
        fun `Redis session에는 refresh token 원문을 저장하지 않는다`() {
            createMember(email = "hashed@example.com")
            val refreshCookie = loginRefreshCookie("hashed@example.com", "password123")

            val keys = redisTemplate.keys("auth:refresh:*")
            assertThat(keys).hasSize(1)
            val entries = redisTemplate.opsForHash<String, String>().entries(keys.single())
            assertThat(entries.values).doesNotContain(refreshCookie.value)
            assertThat(keys.single()).doesNotContain(refreshCookie.value)
        }
    }

    @Nested
    inner class CorsPreflight {

        @Test
        fun `허용 origin method header preflight는 실제 security chain을 통과한다`() {
            mockMvc.options("/api/v1/auth/refresh") {
                header("Origin", "http://localhost:3000")
                header("Access-Control-Request-Method", "POST")
                header("Access-Control-Request-Headers", "Content-Type")
            }.andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Origin", "http://localhost:3000") }
                header { string("Access-Control-Allow-Credentials", "true") }
            }
        }

        @Test
        fun `허용되지 않은 origin method header preflight는 거부한다`() {
            listOf(
                Triple("https://attacker.example.com", "POST", "Content-Type"),
                Triple("http://localhost:3000", "PUT", "Content-Type"),
                Triple("http://localhost:3000", "POST", "X-Forbidden"),
            ).forEach { (origin, method, headers) ->
                mockMvc.options("/api/v1/auth/refresh") {
                    header("Origin", origin)
                    header("Access-Control-Request-Method", method)
                    header("Access-Control-Request-Headers", headers)
                }.andExpect { status { isForbidden() } }
            }
        }
    }

    // -- Actuator security boundary --

    @Nested
    inner class ActuatorBoundary {

        @Test
        fun `application port에서는 actuator endpoint를 공개하지 않는다`() {
            mockMvc.get("/actuator/health/liveness")
                .andExpect { status { isNotFound() } }
            mockMvc.get("/actuator/health/readiness")
                .andExpect { status { isNotFound() } }
            mockMvc.get("/actuator/health")
                .andExpect { status { isUnauthorized() } }
        }
    }

    // -- POST /api/v1/auth/logout --

    @Nested
    inner class Logout {

        @Test
        fun `로그아웃은 refresh session을 revoke하고 204와 만료 cookie를 반환한다`() {
            createMember(email = "logout-refresh@example.com")
            val refreshCookie = loginRefreshCookie("logout-refresh@example.com", "password123")

            mockMvc.post("/api/v1/auth/logout") {
                cookie(refreshCookie)
            }.andExpect {
                status { isNoContent() }
                cookie { maxAge(REFRESH_COOKIE_NAME, 0) }
            }

            assertThat(refresh(refreshCookie).response.status).isEqualTo(401)
        }
    }

    // -- GET /api/v1/members/me --

    @Nested
    inner class Me {

        @Test
        fun `로그인 후 내 정보 조회 성공`() {
            createMember(email = "me@example.com", rawPassword = "password123")

            val accessToken = login("me@example.com", "password123")

            mockMvc.get("/api/v1/members/me") {
                header("Authorization", "Bearer $accessToken")
            }.andExpect {
                status { isOk() }
                jsonPath("$.email") { value("me@example.com") }
                jsonPath("$.nickname") { value("me") }
            }
        }

        @Test
        fun `로그인하지 않으면 401 반환`() {
            mockMvc.get("/api/v1/members/me").andExpect {
                status { isUnauthorized() }
            }
        }

        @Test
        fun `로그아웃 후에도 기존 access token은 만료까지 유효하다`() {
            createMember(email = "logout@example.com", rawPassword = "password123")

            val loginResult = loginResult("logout@example.com", "password123")
            val accessToken = objectMapper.readTree(loginResult.response.contentAsString)["accessToken"].asText()
            val refreshCookie = requireNotNull(loginResult.response.getCookie(REFRESH_COOKIE_NAME))

            // 로그아웃
            mockMvc.post("/api/v1/auth/logout") {
                cookie(refreshCookie)
            }.andExpect { status { isNoContent() } }

            // 로그아웃 후 /me 조회
            mockMvc.get("/api/v1/members/me") {
                header("Authorization", "Bearer $accessToken")
            }.andExpect {
                status { isOk() }
                jsonPath("$.email") { value("logout@example.com") }
            }
        }
    }

    // -- 전체 흐름 --

    @Test
    fun `회원가입 → 로그인 → 내 정보 조회 → 로그아웃 전체 흐름`() {
        // 1. 회원가입
        val registerRequest = mapOf(
            "email" to "flow@example.com",
            "password" to "password123",
        )
        mockMvc.post("/api/v1/members/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(registerRequest)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.email") { value("flow@example.com") }
        }

        // 2. 로그인 → accessToken 획득
        val loginResult = loginResult("flow@example.com", "password123")
        val accessToken = objectMapper.readTree(loginResult.response.contentAsString)["accessToken"].asText()
        val refreshCookie = requireNotNull(loginResult.response.getCookie(REFRESH_COOKIE_NAME))

        // 3. 내 정보 조회
        mockMvc.get("/api/v1/members/me") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("flow@example.com") }
            jsonPath("$.nickname") { value("flow") }
        }

        // 4. 로그아웃 (refresh_token 쿠키 삭제)
        mockMvc.post("/api/v1/auth/logout") {
            cookie(refreshCookie)
        }.andExpect {
            status { isNoContent() }
        }

        // 5. access token은 blacklist하지 않으므로 만료 전까지 유효
        mockMvc.get("/api/v1/members/me") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
        }
    }

    private companion object {
        const val REFRESH_COOKIE_NAME = "refresh_token"
    }
}
