package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.domain.tradeprep.TradePreparationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * AC12 — owner 는 인증 principal 에서 도출되며 요청 body 의 owner 필드를 받지 않는다. 타 회원이
 * 남의 계획을 조회·목표등록·무효화하면 **존재를 노출하지 않는 404** 를 받고 DB 가 변하지 않는다
 * (design.md D10).
 *
 * 403 을 쓰지 않는 이유는 403 이 "그 id 의 계획이 존재한다"를 알려주기 때문이다. 그래서 남의
 * 계획과 아예 없는 id 의 응답이 **구별되지 않아야** 한다 — status 만이 아니라 error code 까지
 * 같은지 대조한다.
 */
class TradePreparationOwnerScopeContractTest : TradePreparationContractTestBase() {

    private var otherMemberId: Long = 0L
    private lateinit var otherToken: String

    @BeforeEach
    fun installOtherMember() {
        otherMemberId = newMember(OTHER_EMAIL)
        otherToken = login(OTHER_EMAIL)
    }

    @Test
    fun `남의 계획에 대한 조회·목표등록·무효화·refresh 는 전부 404 다`() {
        val planId = createDraftPlan()

        ownerScopedEndpoints(planId).forEach { (method, path) ->
            val response = call(method, path, otherToken)
            assertThat(response.status).describedAs("$method $path").isEqualTo(404)
            assertThat(errorCode(response)).describedAs("$method $path").isEqualTo("TRADE_PREPARATION_NOT_FOUND")
        }
    }

    @Test
    fun `남의 계획과 없는 계획의 응답이 구별되지 않는다`() {
        val planId = createDraftPlan()
        val absentId = planId + 100_000L

        ownerScopedEndpoints(planId).zip(ownerScopedEndpoints(absentId)).forEach { (owned, absent) ->
            val ownedResponse = call(owned.first, owned.second, otherToken)
            val absentResponse = call(absent.first, absent.second, otherToken)

            assertThat(ownedResponse.status).isEqualTo(absentResponse.status)
            assertThat(errorCode(ownedResponse)).isEqualTo(errorCode(absentResponse))
        }
    }

    @Test
    fun `남의 계획에 대한 요청은 DB 를 바꾸지 않는다`() {
        val planId = createDraftPlan()
        val before = tradePreparationRepository.findById(planId)!!
        val beforeVersion = before.version

        ownerScopedEndpoints(planId).forEach { (method, path) -> call(method, path, otherToken) }

        val after = tradePreparationRepository.findById(planId)!!
        assertThat(after.status).isEqualTo(TradePreparationStatus.DRAFT)
        assertThat(after.version).isEqualTo(beforeVersion)
        assertThat(after.desiredEntryPremiumRate).isNull()
        assertThat(after.invalidatedAt).isNull()
        assertThat(after.ownerId).isEqualTo(memberId)
    }

    @Test
    fun `요청 body 의 owner 필드는 무시되고 인증 principal 이 owner 다`() {
        val response = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/v1/trade-preparations")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    prepareBody(
                        extra = mapOf("ownerId" to otherMemberId, "memberId" to otherMemberId),
                    ),
                ),
        ).andReturn().response

        assertThat(response.status).isEqualTo(201)
        val planId = objectMapper.readTree(response.contentAsString).get("planId").asLong()

        // body 가 지목한 회원이 아니라 인증 principal 이 owner 다.
        assertThat(tradePreparationRepository.findById(planId)!!.ownerId).isEqualTo(memberId)
        // 그러므로 body 가 지목한 회원은 그 계획을 볼 수 없다.
        assertThat(call(HttpMethod.GET, "/api/v1/trade-preparations/$planId", otherToken).status).isEqualTo(404)
        assertThat(call(HttpMethod.GET, "/api/v1/trade-preparations/$planId", token).status).isEqualTo(200)
    }

    private fun ownerScopedEndpoints(planId: Long): List<Pair<HttpMethod, String>> = listOf(
        HttpMethod.GET to "/api/v1/trade-preparations/$planId",
        HttpMethod.POST to "/api/v1/trade-preparations/$planId/target",
        HttpMethod.POST to "/api/v1/trade-preparations/$planId/refresh",
        HttpMethod.POST to "/api/v1/trade-preparations/$planId/invalidate",
    )

    private fun call(method: HttpMethod, path: String, accessToken: String): MockHttpServletResponse =
        mockMvc.perform(
            MockMvcRequestBuilders.request(method, path)
                .header("Authorization", "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    if (path.endsWith("/target")) {
                        objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "0.50"))
                    } else {
                        "{}"
                    },
                ),
        ).andReturn().response

    private fun errorCode(response: MockHttpServletResponse): String =
        objectMapper.readTree(response.contentAsString).get("code").asText()
}
