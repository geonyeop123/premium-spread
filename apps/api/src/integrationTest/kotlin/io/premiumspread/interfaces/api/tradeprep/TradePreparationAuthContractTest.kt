package io.premiumspread.interfaces.api.tradeprep

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * AC9 — 거래 준비 endpoint 전부가 인증을 요구한다. `PublicEndpointPolicy` 에 추가되지 않았다
 * (`.ai/rules/http.md` 인증 경계).
 *
 * **`PublicEndpointPolicy` 를 직접 import 하지 않는다.** 그 타입은 `infrastructure:api` 에 있고
 * `apps:api` 는 그것을 `runtimeOnly` 로만 소비하므로 test compile classpath 에 없다
 * (`apps/api/build.gradle.kts`). 목록을 문자열로 베껴 오는 것은 목록과 실제 배선이 갈라지면
 * 조용히 틀리므로, 실행 중인 filter chain 에 직접 물어 판정한다 — 그것이 `.ai/rules/http.md` 가
 * 말하는 "공개 여부는 method+path 조합"의 유일한 정본이다.
 *
 * **음성 판정만으로는 부족하다.** 경로가 아예 없어도 인증 없는 요청은 401 이 된다
 * (`PublicEndpointPolicy` 에 없는 경로라 authorization 이 handler mapping 보다 먼저 돈다).
 * 그래서 같은 method+path 를 인증하고 다시 불러 **401 도 404 도 아님**을 함께 확인한다 —
 * 이 짝이 없으면 controller 를 지워도 이 테스트가 통과한다.
 */
class TradePreparationAuthContractTest : TradePreparationContractTestBase() {

    @Test
    fun `인증 없이 호출하면 거래 준비 endpoint 5종이 전부 401 이다`() {
        val planId = createDraftPlan()

        endpoints(planId).forEach { (method, path) ->
            val response = call(method, path, authenticated = false)
            assertThat(response.status)
                .describedAs("$method $path 는 인증 없이 열려 있으면 안 된다")
                .isEqualTo(401)
        }
    }

    @Test
    fun `같은 method+path 를 인증하면 401 도 404 도 아니다`() {
        val planId = createDraftPlan()

        endpoints(planId).forEach { (method, path) ->
            val response = call(method, path, authenticated = true)
            assertThat(response.status)
                .describedAs("$method $path 가 존재하고 인증 principal 로 처리돼야 한다")
                .isNotIn(401, 404, 405)
        }
    }

    /**
     * premiums·tickers 의 GET 조회를 wildcard 로 공개하는 선례가 있어,
     * 거래 준비 단건 조회에도 같은 처리가 새어 들어오기 쉽다. 그 경로만 따로 못 박는다.
     */
    @Test
    fun `단건 조회 GET 도 공개가 아니다`() {
        val planId = createDraftPlan()

        mockMvc.get("/api/v1/trade-preparations/$planId").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/trade-preparations/$planId") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }
    }

    private fun endpoints(planId: Long): List<Pair<HttpMethod, String>> = listOf(
        HttpMethod.POST to "/api/v1/trade-preparations",
        HttpMethod.POST to "/api/v1/trade-preparations/$planId/target",
        HttpMethod.POST to "/api/v1/trade-preparations/$planId/refresh",
        HttpMethod.POST to "/api/v1/trade-preparations/$planId/invalidate",
        HttpMethod.GET to "/api/v1/trade-preparations/$planId",
    )

    private fun call(method: HttpMethod, path: String, authenticated: Boolean): MockHttpServletResponse {
        val request = MockMvcRequestBuilders.request(method, path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyFor(path))
        if (authenticated) {
            request.header("Authorization", "Bearer $token")
        }
        return mockMvc.perform(request).andReturn().response
    }

    /** 인증을 통과했을 때 400(본문 검증 실패)으로 갈리지 않도록 유효한 본문을 붙인다. */
    private fun bodyFor(path: String): String = when {
        path.endsWith("/target") -> objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "0.50"))
        path.endsWith("/trade-preparations") -> prepareBody()
        else -> "{}"
    }
}
