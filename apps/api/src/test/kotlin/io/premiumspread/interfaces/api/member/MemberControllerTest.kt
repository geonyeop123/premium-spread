package io.premiumspread.interfaces.api.member

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.premiumspread.application.member.MemberCriteria
import io.premiumspread.application.member.MemberFacade
import io.premiumspread.application.member.MemberResult
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.security.Principal

@WebMvcTest(MemberController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebMvcConfig::class)
class MemberControllerTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean lateinit var facade: MemberFacade

    @Test
    fun `register는 Criteria로 변환하고 201을 반환한다`() {
        every { facade.register(MemberCriteria.Register("new@example.com", "password123")) } returns
            MemberResult.Detail(1L, "new@example.com", "new", "ACTIVE")
        mockMvc.post("/api/v1/members/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MemberRequest.Register("new@example.com", "password123"))
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `register DTO validation 실패는 400이다`() {
        mockMvc.post("/api/v1/members/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"bad","password":"short"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `me는 표준 principal의 memberId를 Criteria로 전달한다`() {
        every { facade.findMe(MemberCriteria.FindMe(1L)) } returns MemberResult.Detail(1L, "me@example.com", "me", "ACTIVE")
        mockMvc.get("/api/v1/members/me") { principal = Principal { "1" } }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("me@example.com") }
        }
    }
}
