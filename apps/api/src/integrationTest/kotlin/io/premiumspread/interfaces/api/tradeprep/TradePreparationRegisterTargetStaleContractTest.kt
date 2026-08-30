package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant

/**
 * AC19 두 번째 경로 — verified 원천이 있고 `STALE` 이면 `registerTarget` 이 거절된다
 * (design.md D3·D20).
 *
 * production 에는 `VerifiedBalanceReadPort` 구현이 없다(D22, AC20). 그래서 이 경로는 판정용
 * 원천이 배선된 **다른 context** 로만 검증할 수 있고, declared 전용 경로
 * ([TradePreparationRegisterTargetContractTest])와 한 클래스에 담을 수 없다 — 같은 context 에
 * 빈이 있으면서 동시에 없을 수는 없기 때문이다. 두 클래스가 AC19 의 동결된 명령 하나에 함께
 * 잡힌다.
 *
 * `FRESH` 대조군을 함께 두는 이유: `STALE` 거절만 확인하면 "판정용 port 가 있으면 무조건
 * 거절한다"는 잘못된 구현도 통과한다. 거절의 원인이 **원천의 존재가 아니라 신선도**임을 같은
 * 배선에서 보인다.
 */
@Import(TradePreparationRegisterTargetStaleContractTest.VerifiedBalanceTestConfig::class)
class TradePreparationRegisterTargetStaleContractTest : TradePreparationContractTestBase() {

    @Autowired
    private lateinit var verifiedBalance: SwitchableVerifiedBalanceReadPort

    @BeforeEach
    fun resetPort() {
        verifiedBalance.basis = BalanceBasis.FRESH
        verifiedBalance.available = true
    }

    @Test
    fun `verified 원천이 STALE 이면 registerTarget 이 409 로 거절된다`() {
        val planId = createDraftPlan()
        verifiedBalance.basis = BalanceBasis.STALE

        registerTarget(planId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_BALANCE_FOR_EXPOSURE") }
        }

        assertPlanUntouched(planId)
    }

    @Test
    fun `verified 원천을 확보하지 못해도 같은 fail-closed 로 거절된다`() {
        val planId = createDraftPlan()
        verifiedBalance.available = false

        registerTarget(planId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_BALANCE_FOR_EXPOSURE") }
        }

        assertPlanUntouched(planId)
    }

    @Test
    fun `verified 원천이 FRESH 면 그 스냅샷 id 로 결속해 WATCHING 이 된다`() {
        val planId = createDraftPlan()

        registerTarget(planId).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("WATCHING") }
            jsonPath("$.boundBalanceBasis") { value("FRESH") }
            jsonPath("$.boundBalanceSnapshotId") { value(VERIFIED_SNAPSHOT_ID) }
        }

        val plan = tradePreparationRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.boundBalanceBasis).isEqualTo(BalanceBasis.FRESH)
        assertThat(plan.boundBalanceSnapshotId).isEqualTo(VERIFIED_SNAPSHOT_ID)
    }

    private fun assertPlanUntouched(planId: Long) {
        val plan = tradePreparationRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(TradePreparationStatus.DRAFT)
        assertThat(plan.desiredEntryPremiumRate).isNull()
        assertThat(plan.boundBalanceBasis).isEqualTo(BalanceBasis.UNVERIFIED)
        assertThat(tradePreparationRepository.findActiveByOwnerId(memberId)).isNull()
    }

    private fun registerTarget(planId: Long) = mockMvc.post("/api/v1/trade-preparations/$planId/target") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to "0.50"))
    }

    @TestConfiguration
    class VerifiedBalanceTestConfig {
        @Bean
        fun switchableVerifiedBalanceReadPort(): SwitchableVerifiedBalanceReadPort =
            SwitchableVerifiedBalanceReadPort()
    }

    companion object {
        const val VERIFIED_SNAPSHOT_ID = "recorded-verified-1"
    }
}

/**
 * 테스트에서만 존재하는 판정용 잔고 원천이다. `basis` 를 바꿔 같은 배선에서 `FRESH`·`STALE` 을
 * 오가고, `available=false` 로 확보 실패(`null`)를 재현한다.
 *
 * `VerifiedBalance` 는 생성자가 private 이라 `from` 을 거칠 수밖에 없다 — `UNVERIFIED` 로는
 * 애초에 만들어지지 않는 D9 의 신뢰 경계를 이 fake 도 우회하지 못한다.
 */
class SwitchableVerifiedBalanceReadPort : VerifiedBalanceReadPort {

    @Volatile
    var basis: BalanceBasis = BalanceBasis.FRESH

    @Volatile
    var available: Boolean = true

    override fun findForDecision(): VerifiedBalance? {
        if (!available) return null
        return VerifiedBalance.from(
            BalanceSnapshot(
                id = TradePreparationRegisterTargetStaleContractTest.VERIFIED_SNAPSHOT_ID,
                koreaBalance = BigDecimal("5000000"),
                foreignBalance = BigDecimal("1000"),
                balanceBasis = basis,
                observedAt = Instant.parse("2026-08-30T00:00:00Z"),
            ),
        )
    }
}
