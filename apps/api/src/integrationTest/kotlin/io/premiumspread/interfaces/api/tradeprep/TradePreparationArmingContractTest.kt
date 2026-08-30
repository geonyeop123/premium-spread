package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tracking.Tracking
import io.premiumspread.domain.tracking.TrackingRecordSpec
import io.premiumspread.domain.tracking.TrackingRepository
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationOutcome
import io.premiumspread.domain.tradeprep.TradePreparationEvaluationService
import io.premiumspread.domain.tradeprep.TradePreparationFreshnessPolicy
import io.premiumspread.domain.tradeprep.TradePreparationStatus
import io.premiumspread.domain.tradeprep.VerifiedBalance
import io.premiumspread.domain.tradeprep.VerifiedBalanceReadPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration

/**
 * AC7 — owner 진입 목표 프리미엄에 도달하면 계획이 무장 상태로 전이하고 **주문을 제출하지 않으며**,
 * 직전 종료 포지션의 진입 프리미엄과 현재 gap 이 응답에 있다 (design.md D6·D7·D8).
 *
 * ## 전이를 어떻게 부르는가
 *
 * 전이는 Facade 가 아니라 Domain 의 [TradePreparationEvaluationService] 가 소유한다 (D21) —
 * `apps:batch` 의 평가 Job 이 쓰는 바로 그 경로다. `apps:api` 는 batch 를 참조할 수 없으므로 이
 * 계약은 같은 Domain capability 를 직접 실행하고, 결과는 **REST 조회 응답으로** 확인한다.
 * scheduler → Job → 전이 사슬 자체는 AC17
 * (`io.premiumspread.application.job.TradePreparationEvaluationJobIntegrationTest`)이 소유한다.
 *
 * ## 왜 판정용 잔고 fake 를 배선하는가
 *
 * `ARMED` 관문은 `boundBalanceBasis != UNVERIFIED` 이고(D19), production 에는 판정용 원천이
 * 없어(D22) declared 결속만 만들어진다. 그 상태의 도달점은 `WATCHING` 이며 그것이 의도다 —
 * 무장까지의 사슬은 verified 원천이 배선된 이 context 에서만 code-ready 로 검증된다.
 */
@Import(TradePreparationArmingContractTest.VerifiedBalanceConfig::class)
class TradePreparationArmingContractTest : TradePreparationContractTestBase() {

    @Autowired private lateinit var trackingRepository: TrackingRepository

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `진입 목표에 도달하면 계획이 ARMED 로 전이하고 조회 응답이 그것을 보인다`() {
        val planId = watchingPlan()

        val summary = evaluateWithCurrentPremium()

        assertThat(summary.outcome).isEqualTo(TradePreparationEvaluationOutcome.EVALUATED)
        assertThat(summary.armed).isEqualTo(1)
        mockMvc.get("/api/v1/trade-preparations/$planId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ARMED") }
            jsonPath("$.conditionFirstMetAt") { exists() }
            jsonPath("$.conditionFirstMetPremiumRate") { exists() }
        }
        assertThat(tradePreparationRepository.findById(planId)!!.status).isEqualTo(TradePreparationStatus.ARMED)
    }

    /**
     * `ARMED` 는 종점이다 — 무장 자체는 주문도 포지션도 만들지 않는다. 이 코드베이스에서 체결의
     * durable 흔적은 `Tracking`(`position` 테이블)뿐이므로 그것이 늘지 않았음을 센다.
     */
    @Test
    fun `무장은 주문이나 추적 기록을 만들지 않는다`() {
        val planId = watchingPlan()

        evaluateWithCurrentPremium()

        assertThat(tradePreparationRepository.findById(planId)!!.status).isEqualTo(TradePreparationStatus.ARMED)
        assertThat(countPositions()).isZero()
    }

    @Test
    fun `프리미엄이 진입 목표보다 높으면 WATCHING 을 유지한다`() {
        // 현재 프리미엄(약 1.04%)보다 낮은 목표는 아직 도달하지 않은 목표다.
        val planId = watchingPlan(desiredEntryPremiumRate = "0.50")

        val summary = evaluateWithCurrentPremium()

        assertThat(summary.armed).isZero()
        val plan = tradePreparationRepository.findById(planId)!!
        assertThat(plan.status).isEqualTo(TradePreparationStatus.WATCHING)
        assertThat(plan.conditionFirstMetAt).isNull()
    }

    @Test
    fun `prepare 응답에 직전 종료 추적의 진입 프리미엄과 현재 gap 이 있다`() {
        archivedTracking()

        val body = mockMvc.post("/api/v1/trade-preparations") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andExpect {
            // 계획을 만든 prepare 는 201 이다 (T6 계약).
            status { isCreated() }
            jsonPath("$.previousTracking.trackingId") { exists() }
            jsonPath("$.previousTracking.entryPremiumRate") { exists() }
            jsonPath("$.previousTracking.closedAt") { exists() }
        }.andReturn().response.contentAsString

        val json = objectMapper.readTree(body)
        val current = json.get("referencePremiumRate").decimalValue()
        val entry = json.get("previousTracking").get("entryPremiumRate").decimalValue()
        val gap = json.get("previousTracking").get("premiumRateGap").decimalValue()
        val reportedCurrent = json.get("previousTracking").get("currentPremiumRate").decimalValue()

        assertThat(reportedCurrent).isEqualByComparingTo(current)
        assertThat(gap).isEqualByComparingTo(current.subtract(entry))
        // 진입가가 다르므로 gap 은 0 이 아니다 — 0 이면 "항상 0" 구현도 통과한다.
        assertThat(gap).isNotEqualByComparingTo(BigDecimal.ZERO)
    }

    /**
     * verified 결속의 `WATCHING` 계획을 실제 API 로 만든다. 결속 basis 는 [VerifiedBalanceConfig] 가
     * 배선한 판정용 원천이 정한다 — 테스트가 엔티티를 손으로 조립하지 않는다.
     */
    private fun watchingPlan(desiredEntryPremiumRate: String = "1.50"): Long {
        val planId = createDraftPlan()
        mockMvc.post("/api/v1/trade-preparations/$planId/target") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("desiredEntryPremiumRate" to desiredEntryPremiumRate))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("WATCHING") }
            jsonPath("$.boundBalanceBasis") { value("FRESH") }
        }
        return planId
    }

    /**
     * Domain 평가를 현재 프리미엄으로 실행한다. `now` 는 관측 시각 자체라 신선도는 이 계약의
     * 변수가 아니다 — 신선도 경계는 AC17 이 소유한다.
     */
    private fun evaluateWithCurrentPremium() = TransactionTemplate(transactionManager).execute {
        val snapshot = premiumRepository.findLatestSnapshotByPair(PAIR)!!
        TradePreparationEvaluationService(
            tradePreparationRepository,
            TradePreparationFreshnessPolicy(Duration.ofSeconds(10)),
        ).evaluate(PAIR, snapshot, snapshot.observedAt)
    }!!

    private fun archivedTracking() {
        val tracking = Tracking.create(
            TrackingRecordSpec(
                memberId = memberId,
                pair = PAIR,
                koreaQuantity = BigDecimal("0.038"),
                koreaEntryPrice = BigDecimal("131000000"),
                foreignQuantity = BigDecimal("0.038"),
                foreignEntryPrice = FOREIGN_PRICE,
                foreignLeverage = 3,
                entryFxRate = FX_RATE,
                entryObservedAt = FIXTURE_OBSERVED_AT.minusSeconds(3600),
            ),
        )
        tracking.archive(null, FIXTURE_OBSERVED_AT.minusSeconds(600))
        trackingRepository.save(tracking)
    }

    private fun countPositions(): Long =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM position", Long::class.java)!!

    @TestConfiguration
    class VerifiedBalanceConfig {
        /**
         * 판정용 잔고의 test 전용 원천이다. production 에는 이 빈이 없다 (D22, AC20) — 여기서만
         * verified 결속이 만들어지고, 그래서 `ARMED` 사슬을 code-ready 로 볼 수 있다.
         */
        @Bean
        fun freshVerifiedBalanceReadPort(): VerifiedBalanceReadPort = VerifiedBalanceReadPort {
            VerifiedBalance.from(
                BalanceSnapshot(
                    id = TradePreparationArmingContractTest.VERIFIED_SNAPSHOT_ID,
                    koreaBalance = TradePreparationContractTestBase.PLANNABLE_KOREA_BALANCE,
                    foreignBalance = TradePreparationContractTestBase.FOREIGN_BALANCE,
                    balanceBasis = BalanceBasis.FRESH,
                    observedAt = TradePreparationContractTestBase.FIXTURE_OBSERVED_AT,
                ),
            )
        }
    }

    companion object {
        const val VERIFIED_SNAPSHOT_ID = "recorded-arming-1"
        val PAIR: MarketPair = MarketPair(
            Symbol(TradePreparationContractTestBase.SYMBOL),
            Exchange.BITHUMB,
            Exchange.BINANCE,
        )
    }
}
