package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * AC4 — `balanceBasis` 가 `STALE` 일 때 **조회는 라벨과 함께 계획을 반환하고**, verified 원천이 있는
 * `registerTarget` 은 거절한다 (design.md D3·D20).
 *
 * 두 반응이 갈리는 이유는 노출(exposure) 여부다. `prepare` 는 표시용이라 신선도를 감추지도
 * 거절하지도 않고 `balanceBasis`·`balanceObservedAt` 라벨로 드러낸다(D2 가 캐시를 명시적으로
 * 허용하는 계약이다). `registerTarget` 은 활성 계획을 만들어 노출을 키우므로 판정용 원천이
 * 배선돼 있으면 `STALE` 을 fail-closed 로 거절한다.
 *
 * 그래서 이 context 는 표시용·판정용 원천을 **둘 다** 배선하고 둘 다 `STALE` 을 돌려준다 — 한
 * 배선에서 두 반응이 갈리는 것이 이 계약의 내용이다.
 */
@Import(TradePreparationStaleBalanceTest.StaleBalanceConfig::class)
class TradePreparationStaleBalanceTest : TradePreparationContractTestBase() {

    @Test
    fun `표시용 잔고가 STALE 이어도 prepare 는 계획을 만들고 라벨을 함께 돌려준다`() {
        mockMvc.post("/api/v1/trade-preparations") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andExpect {
            // 계획을 만든 prepare 는 201 이다 — STALE 이 경로를 바꾸지 않는다는 것이 이 계약이다.
            status { isCreated() }
            jsonPath("$.planId") { exists() }
            jsonPath("$.status") { value(TradePreparationStatus.DRAFT.name) }
            jsonPath("$.plannable") { value(true) }
            jsonPath("$.capViolations") { isEmpty() }
            jsonPath("$.balanceBasis") { value(BalanceBasis.STALE.name) }
            jsonPath("$.balanceSnapshotId") { value(STALE_SNAPSHOT_ID) }
            // 신선도 라벨의 짝인 관측 시각도 함께 나온다 (직렬화 형식은 이 계약의 대상이 아니다).
            jsonPath("$.balanceObservedAt") { value(containsString("2026-08-30")) }
        }

        assertThat(countPlans()).isEqualTo(1)
    }

    @Test
    fun `STALE 로 만든 계획은 조회에서도 그 결속을 그대로 보인다`() {
        val planId = createDraftPlan()

        mockMvc.get("/api/v1/trade-preparations/$planId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value(TradePreparationStatus.DRAFT.name) }
            jsonPath("$.boundBalanceBasis") { value(BalanceBasis.STALE.name) }
            jsonPath("$.boundBalanceSnapshotId") { value(STALE_SNAPSHOT_ID) }
        }
    }

    @Test
    fun `verified 원천이 STALE 이면 registerTarget 은 거절되고 DB 가 변하지 않는다`() {
        val planId = createDraftPlan()

        mockMvc.post("/api/v1/trade-preparations/$planId/target") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "1.50"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_BALANCE_FOR_EXPOSURE") }
        }

        val plan = tradePreparationRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(TradePreparationStatus.DRAFT)
        assertThat(plan.desiredEntryPremiumRate).isNull()
        assertThat(tradePreparationRepository.findActiveByOwnerId(memberId)).isNull()
    }

    @TestConfiguration
    class StaleBalanceConfig {

        /** 표시용은 캐시를 허용한다 — `STALE` 을 라벨로 드러낼 뿐 거절하지 않는다 (D2·D3). */
        @Bean
        fun staleBalanceSnapshotReadPort(): BalanceSnapshotReadPort = BalanceSnapshotReadPort { staleSnapshot() }

        /** 판정용. 같은 `STALE` 값이지만 이쪽은 노출을 키우는 경로라 거절로 이어진다. */
        @Bean
        fun staleVerifiedBalanceReadPort(): VerifiedBalanceReadPort =
            VerifiedBalanceReadPort { VerifiedBalance.from(staleSnapshot()) }

        private fun staleSnapshot(): BalanceSnapshot = BalanceSnapshot(
            id = TradePreparationStaleBalanceTest.STALE_SNAPSHOT_ID,
            koreaBalance = TradePreparationContractTestBase.PLANNABLE_KOREA_BALANCE,
            foreignBalance = TradePreparationContractTestBase.FOREIGN_BALANCE,
            balanceBasis = BalanceBasis.STALE,
            observedAt = TradePreparationContractTestBase.FIXTURE_OBSERVED_AT,
        )
    }

    companion object {
        const val STALE_SNAPSHOT_ID = "recorded-stale-1"
    }
}
