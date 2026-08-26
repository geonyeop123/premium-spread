package io.premiumspread.interfaces.api.tracking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 응답 **body** 를 검증한다 (dod.md AC3).
 *
 * DTO 파일 텍스트 검사는 필드가 실제로 응답에 실리는지를 증명하지 못한다.
 */
class TrackingGrossPnlContractTest : TrackingContractTestBase() {

    private val required = setOf(
        "trackingId", "priceBasis", "pnlBasis", "entryPremiumRate", "referencePremiumRate",
        "premiumRateDelta", "koreaLegGrossPnlKrw", "foreignLegGrossPnlKrw", "totalGrossPnlKrw",
        "koreaLegNotionalKrw", "grossPnlPercentOfKoreaNotional", "isGrossProfit",
        "calculatedAt", "observedAt", "fxObservedAt",
    )

    private val forbidden = setOf(
        "positionId", "premiumDiff", "currentPremiumRate", "koreaPnl", "foreignPnlKrw",
        "totalPnlKrw", "koreaCurrentValue", "totalPnlPercent", "isProfit",
    )

    private fun keysOf(json: String): Set<String> =
        objectMapper.readTree(json).fieldNames().asSequence().toSet()

    @Test
    fun `ACTIVE 응답이 계약 필드를 정확히 갖는다`() {
        val id = saveTracking().id
        savePremium()
        val body = mockMvc.get("/api/v1/trackings/$id/gross-pnl") {
            header("Authorization", "Bearer $token")
        }.andReturn().response.contentAsString

        val keys = keysOf(body)
        assertThat(keys).containsAll(required)
        assertThat(keys).doesNotContainAnyElementsOf(forbidden)
        assertThat(objectMapper.readTree(body).get("priceBasis").asText()).isEqualTo("CURRENT_MARKET")
        assertThat(objectMapper.readTree(body).get("pnlBasis").asText())
            .isEqualTo("GROSS_EXCLUDING_FEES_FUNDING_SLIPPAGE_FX_SPREAD")
    }

    @Test
    fun `ARCHIVED 응답도 같은 계약을 지키고 priceBasis 만 다르다`() {
        val id = saveTracking().id
        savePremium()
        mockMvc.post("/api/v1/trackings/$id/archive") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }

        val body = mockMvc.get("/api/v1/trackings/$id/gross-pnl") {
            header("Authorization", "Bearer $token")
        }.andReturn().response.contentAsString

        assertThat(keysOf(body)).containsAll(required)
        assertThat(keysOf(body)).doesNotContainAnyElementsOf(forbidden)
        assertThat(objectMapper.readTree(body).get("priceBasis").asText()).isEqualTo("ARCHIVED_SNAPSHOT")
    }
}
