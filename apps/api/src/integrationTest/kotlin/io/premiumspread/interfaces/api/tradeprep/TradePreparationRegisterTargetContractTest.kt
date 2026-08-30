package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post

/**
 * AC19 첫 번째 경로 — declared 원천만 있을 때 `registerTarget` 이 `UNVERIFIED` 결속으로
 * `WATCHING` 을 만들고 `VerifiedBalance` 는 생성되지 않는다 (design.md D20).
 *
 * 이 context 에는 `VerifiedBalanceReadPort` 빈이 없다. 그것이 production 배선이며(D22, AC20)
 * 이 경로가 검증하는 상태다. verified 원천이 있는 두 번째 경로는 빈 구성이 달라 같은 context 를
 * 쓸 수 없으므로 [TradePreparationRegisterTargetStaleContractTest] 가 소유한다 — 두 클래스가
 * AC19 의 동결된 명령(`--tests '*TradePreparationRegisterTarget*'`) 하나에 함께 잡힌다.
 */
class TradePreparationRegisterTargetContractTest : TradePreparationContractTestBase() {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `declared 원천만 있으면 UNVERIFIED 결속으로 WATCHING 이 된다`() {
        val planId = createDraftPlan()

        mockMvc.post("/api/v1/trade-preparations/$planId/target") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "0.50"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(planId) }
            jsonPath("$.status") { value("WATCHING") }
            jsonPath("$.boundBalanceBasis") { value("UNVERIFIED") }
            jsonPath("$.desiredEntryPremiumRate") { value(0.50) }
        }

        val plan = tradePreparationRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.boundBalanceBasis).isEqualTo(BalanceBasis.UNVERIFIED)
        assertThat(tradePreparationRepository.findActiveByOwnerId(memberId)!!.id).isEqualTo(planId)
    }

    /**
     * `VerifiedBalance` 는 생성자가 private 이고 `from` 이 `FRESH`·`STALE` 스냅샷만 변환한다.
     * declared 배선에서 그 타입에 도달할 경로가 없다는 것은 **판정용 port 빈 자체가 없다**는
     * 사실로 확인한다 — 빈이 없으면 `resolveBinding` 이 `getIfAvailable()` 에서 곧장 UNVERIFIED 로
     * 빠지므로 `findForDecision` 이 호출될 자리가 없다.
     */
    @Test
    fun `판정용 port 빈이 없어 VerifiedBalance 를 만들 경로가 없다`() {
        assertThat(applicationContext.getBeanNamesForType(VerifiedBalanceReadPort::class.java)).isEmpty()
    }

    @Test
    fun `이미 WATCHING 인 계획에 다시 목표를 등록하면 거절된다`() {
        val planId = createDraftPlan()
        registerTarget(planId).andExpect { status { isOk() } }

        registerTarget(planId).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("DOMAIN_ERROR") }
        }
    }

    private fun registerTarget(planId: Long) = mockMvc.post("/api/v1/trade-preparations/$planId/target") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "0.50"))
    }
}
