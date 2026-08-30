package io.premiumspread.interfaces.api.tradeprep

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * AC1 — 거래 준비 요청이 잔고·물량·레버리지·캡 판정과 `balanceBasis`·`observedAt` 을 담은 응답을
 * 반환한다. **응답 키 집합을 대조한다** (design.md §1.1·D2).
 *
 * 키 집합을 통째로 비교하는 이유: 개별 `jsonPath` 단언만 쓰면 필드가 사라져도 그 단언을 함께
 * 지우면 테스트가 계속 통과한다. 집합 비교는 누락과 추가를 둘 다 실패로 만든다. 값 단언을 따로
 * 두는 이유는 그 반대다 — 비슷한 `BigDecimal` 필드 27개를 매핑하다 서로 뒤바뀌어도 키 집합은
 * 그대로이기 때문이다.
 *
 * AC3 의 "위반한 캡을 응답에 명시한다"도 여기서 실제 HTTP 응답으로 확인한다. Domain 단위
 * 테스트(`TradePreparationCapTest`)는 판정 자체를 검증하지만, 그 판정이 본문까지 실려 나가는지는
 * REST 계층에서만 확인된다.
 */
class TradePreparationContractTest : TradePreparationContractTestBase() {

    @Test
    fun `prepare 응답 키 집합이 잔고·사이징·캡 판정·provenance 를 전부 담는다`() {
        val json = prepare(prepareBody(), expectedStatus = 201)

        assertThat(json.fieldNames().asSequence().toSet()).isEqualTo(PREPARATION_KEYS)
    }

    @Test
    fun `캡 위반 응답도 같은 키 집합을 유지한다`() {
        val json = prepare(prepareBody(koreaBalance = CAP_VIOLATING_KOREA_BALANCE), expectedStatus = 422)

        assertThat(json.fieldNames().asSequence().toSet()).isEqualTo(PREPARATION_KEYS)
    }

    @Test
    fun `캡 안쪽이면 계획을 만들고 잔고 라벨과 사이징 산출값을 그대로 싣는다`() {
        val json = prepare(prepareBody(), expectedStatus = 201)

        assertThat(json.get("planId").asLong()).isPositive()
        assertThat(json.get("status").asText()).isEqualTo("DRAFT")
        assertThat(json.get("plannable").asBoolean()).isTrue()
        assertThat(json.get("code").isNull).isTrue()
        assertThat(json.get("capViolations")).isEmpty()

        // D2·D3: 신고값은 UNVERIFIED 라벨을 달고 관측 시각과 함께 나간다.
        assertThat(json.get("balanceBasis").asText()).isEqualTo("UNVERIFIED")
        assertThat(json.get("balanceObservedAt").asText()).isNotBlank()
        assertThat(json.get("balanceSnapshotId").asText()).startsWith("declared-")
        assertThat(json.decimal("koreaBalance")).isEqualByComparingTo("5000000")
        assertThat(json.decimal("foreignBalance")).isEqualByComparingTo("1000")

        // ECO-5 §2: 양 leg lot/step 내림 뒤 작은 쪽(0.038)을 채택하고 leverage 를 재계산한다.
        assertThat(json.decimal("koreaRoundedQuantity")).isEqualByComparingTo("0.0385")
        assertThat(json.decimal("foreignRoundedQuantity")).isEqualByComparingTo("0.038")
        assertThat(json.decimal("quantity")).isEqualByComparingTo("0.038")
        assertThat(json.decimal("leverage")).isEqualByComparingTo("3.401")
        assertThat(json.decimal("rawQuantity")).isGreaterThan(json.decimal("quantity"))

        // provenance (D12)
        assertThat(json.decimal("referenceForeignPrice")).isEqualByComparingTo("89500")
        assertThat(json.decimal("referenceFxRate")).isEqualByComparingTo("1432.6")
        assertThat(json.decimal("referencePremiumRate")).isEqualByComparingTo("1.04")
        assertThat(json.get("referenceFxSource").asText()).isEqualTo("FX_PROVIDER")
        assertThat(json.get("referenceObservedAt").asText()).isNotBlank()
        assertThat(json.get("referenceFxObservedAt").asText()).isNotBlank()

        // D8: 직전 종료 추적이 없으면 null 이다.
        assertThat(json.get("previousTracking").isNull).isTrue()
    }

    @Test
    fun `캡을 위반하면 422 이고 계획을 만들지 않으며 위반한 캡을 본문에 싣는다`() {
        val json = prepare(prepareBody(koreaBalance = CAP_VIOLATING_KOREA_BALANCE), expectedStatus = 422)

        assertThat(json.get("code").asText()).isEqualTo("CAP_VIOLATED")
        assertThat(json.get("planId").isNull).isTrue()
        assertThat(json.get("status").isNull).isTrue()
        assertThat(json.get("plannable").asBoolean()).isFalse()

        // AC3 의 핵심: 상태 코드만이 아니라 **어느 캡이** 걸렸는지가 본문에 있어야 한다.
        assertThat(json.get("capViolations").map { it.asText() }).containsExactly("EFFICIENCY_CAP")
        assertThat(json.decimal("koreaShare")).isLessThan(BigDecimal("0.60"))
        assertThat(json.decimal("liquidationDistance")).isPositive()
    }

    @Test
    fun `캡을 위반하면 계획 행 자체가 생기지 않는다`() {
        prepare(prepareBody(koreaBalance = CAP_VIOLATING_KOREA_BALANCE), expectedStatus = 422)

        // DRAFT 는 active_key 가 NULL 이라 findActiveByOwnerId 로 잡히지 않는다. id 를 훑어
        // "행이 하나도 없음"을 직접 확인한다.
        assertThat((1L..20L).mapNotNull { tradePreparationRepository.findById(it) }).isEmpty()
    }

    @Test
    fun `단건 조회는 저장된 계획의 결속과 provenance 를 돌려준다`() {
        val planId = createDraftPlan()

        val json = get(planId, expectedStatus = 200)

        assertThat(json.fieldNames().asSequence().toSet()).isEqualTo(DETAIL_KEYS)
        assertThat(json.get("id").asLong()).isEqualTo(planId)
        assertThat(json.get("status").asText()).isEqualTo("DRAFT")
        assertThat(json.get("symbol").asText()).isEqualTo(SYMBOL)
        assertThat(json.get("boundBalanceBasis").asText()).isEqualTo("UNVERIFIED")
        assertThat(json.get("boundBalanceSnapshotId").asText()).startsWith("declared-")
        assertThat(json.decimal("quantity")).isEqualByComparingTo("0.038")
        assertThat(json.get("desiredEntryPremiumRate").isNull).isTrue()
        assertThat(json.get("invalidationReason").isNull).isTrue()
    }

    @Test
    fun `refresh 와 invalidate 는 계획을 INVALIDATED 로 만들고 사유를 남긴다`() {
        val refreshed = createDraftPlan()
        val refreshJson = postPath("/api/v1/trade-preparations/$refreshed/refresh", expectedStatus = 200)
        assertThat(refreshJson.get("status").asText()).isEqualTo("INVALIDATED")
        assertThat(refreshJson.get("invalidationReason").asText()).isEqualTo("OWNER_REFRESH")
        assertThat(refreshJson.get("invalidatedAt").asText()).isNotBlank()

        val invalidated = createDraftPlan()
        val invalidateJson = postPath("/api/v1/trade-preparations/$invalidated/invalidate", expectedStatus = 200)
        assertThat(invalidateJson.get("status").asText()).isEqualTo("INVALIDATED")
        assertThat(invalidateJson.get("invalidationReason").asText()).isEqualTo("OWNER_REFRESH")
    }

    @Test
    fun `없는 계획은 404 이고 안정된 error code 를 쓴다`() {
        val json = get(999_999L, expectedStatus = 404)

        assertThat(json.get("code").asText()).isEqualTo("TRADE_PREPARATION_NOT_FOUND")
    }

    private fun prepare(body: String, expectedStatus: Int): JsonNode {
        val response = mockMvc.post("/api/v1/trade-preparations") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn().response
        assertThat(response.status).isEqualTo(expectedStatus)
        return objectMapper.readTree(response.contentAsString)
    }

    private fun postPath(path: String, expectedStatus: Int): JsonNode {
        val response = mockMvc.post(path) { header("Authorization", "Bearer $token") }.andReturn().response
        assertThat(response.status).isEqualTo(expectedStatus)
        return objectMapper.readTree(response.contentAsString)
    }

    private fun get(planId: Long, expectedStatus: Int): JsonNode {
        val response = mockMvc.get("/api/v1/trade-preparations/$planId") {
            header("Authorization", "Bearer $token")
        }.andReturn().response
        assertThat(response.status).isEqualTo(expectedStatus)
        return objectMapper.readTree(response.contentAsString)
    }

    private fun JsonNode.decimal(field: String): BigDecimal = get(field).decimalValue()

    companion object {
        /**
         * `prepare` 응답의 전체 키 집합 (AC1). `null` 필드도 직렬화되므로 캡 위반 여부와 무관하게
         * 같은 집합이다 — 응답 형태가 결과에 따라 갈라지지 않는 것이 계약의 일부다.
         */
        val PREPARATION_KEYS = setOf(
            "planId",
            "status",
            "code",
            "symbol",
            "koreaExchange",
            "foreignExchange",
            "balanceSnapshotId",
            "koreaBalance",
            "foreignBalance",
            "balanceBasis",
            "balanceObservedAt",
            "balanceRatio",
            "rawLeverage",
            "rawQuantity",
            "koreaRoundedQuantity",
            "foreignRoundedQuantity",
            "quantity",
            "leverage",
            "koreaShare",
            "liquidationDistance",
            "capViolations",
            "plannable",
            "referenceForeignPrice",
            "referenceFxRate",
            "referencePremiumRate",
            "referenceObservedAt",
            "referenceFxSource",
            "referenceFxObservedAt",
            "previousTracking",
        )

        /** 저장된 계획 단건의 키 집합. */
        val DETAIL_KEYS = setOf(
            "id",
            "symbol",
            "koreaExchange",
            "foreignExchange",
            "status",
            "boundBalanceSnapshotId",
            "boundBalanceBasis",
            "quantity",
            "leverage",
            "referenceForeignPrice",
            "referenceFxRate",
            "referencePremiumRate",
            "referenceObservedAt",
            "referenceFxSource",
            "referenceFxObservedAt",
            "desiredEntryPremiumRate",
            "conditionFirstMetAt",
            "conditionFirstMetPremiumRate",
            "invalidationReason",
            "invalidatedAt",
            "version",
        )
    }
}
