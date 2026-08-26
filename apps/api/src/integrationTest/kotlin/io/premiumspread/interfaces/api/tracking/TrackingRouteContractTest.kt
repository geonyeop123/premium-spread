package io.premiumspread.interfaces.api.tracking

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 경로의 **부재**는 실행 중인 앱에 물어야 한다 (dod.md AC1).
 *
 * 문자열 grep 은 `@RequestMapping("/api/v1/" + "positions")` 같은 상수 결합을 검출하지 못한다.
 * 인증된 요청으로 확인한다 — 인증 없이 호출하면 PublicEndpointPolicy 에 없는 경로라
 * 404 이전에 401 이 나와 판정이 무의미하다.
 */
class TrackingRouteContractTest : TrackingContractTestBase() {

    @Test
    fun `옛 positions 경로 8종이 모두 사라졌다`() {
        val id = saveTracking().id
        mockMvc.get("/api/v1/positions") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/positions/history") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/positions/summary") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/positions/$id") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/positions/$id/pnl") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/positions/auto") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/positions/manual") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/positions/$id/close") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `대체 endpoint 는 정상 동작한다`() {
        val id = saveTracking().id
        savePremium()
        mockMvc.get("/api/v1/trackings") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/v1/trackings/archived") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/v1/trackings/summary") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/v1/trackings/$id") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/v1/trackings/$id/gross-pnl") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
        mockMvc.post("/api/v1/trackings/$id/archive") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }
    }
}
