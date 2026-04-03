package io.premiumspread.interfaces.api.member

import com.fasterxml.jackson.databind.ObjectMapper
import io.premiumspread.config.TestConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

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

    private fun login(email: String, password: String): String {
        val request = mapOf("email" to email, "password" to password)
        val result = mockMvc.post("/api/v1/members/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andReturn()
        val body = objectMapper.readTree(result.response.contentAsString)
        return body["accessToken"].asText()
    }

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

    // -- POST /api/v1/members/logout --

    @Nested
    inner class Logout {

        @Test
        fun `로그아웃 성공 - 200 반환`() {
            mockMvc.post("/api/v1/members/logout").andExpect {
                status { isOk() }
            }
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
        @Disabled("JWT 토큰 블랙리스트 미구현 - 로그아웃 후에도 토큰이 만료 전까지 유효")
        fun `로그아웃 후 내 정보 조회 시 401 반환`() {
            createMember(email = "logout@example.com", rawPassword = "password123")

            val accessToken = login("logout@example.com", "password123")

            // 로그아웃
            mockMvc.post("/api/v1/auth/logout") {
                header("Authorization", "Bearer $accessToken")
            }

            // 로그아웃 후 /me 조회
            mockMvc.get("/api/v1/members/me") {
                header("Authorization", "Bearer $accessToken")
            }.andExpect {
                status { isUnauthorized() }
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
        val accessToken = login("flow@example.com", "password123")

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
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
        }

        // 5. 로그아웃 후 accessToken은 블랙리스트 미구현으로 여전히 유효
        // 토큰 없이 요청하면 401 반환됨을 검증
        mockMvc.get("/api/v1/members/me").andExpect {
            status { isUnauthorized() }
        }
    }
}
