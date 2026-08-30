package io.premiumspread.interfaces.api.tradeprep

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.application.tradeprep.TradePreparationCriteria
import io.premiumspread.application.tradeprep.TradePreparationFacade
import io.premiumspread.application.tradeprep.TradePreparationResult
import io.premiumspread.interfaces.api.config.WebMvcConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.security.Principal
import java.time.Instant

/**
 * Controller 단위 계약이다. Criteria 변환·HTTP status 매핑·transport validation 만 본다 —
 * 유스케이스 동작은 `apps/api/src/integrationTest` 의 계약 테스트가 실제 HTTP 로 검증한다.
 */
@WebMvcTest(TradePreparationController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebMvcConfig::class)
class TradePreparationControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean lateinit var facade: TradePreparationFacade

    private val principal = Principal { "7" }

    @Test
    fun `캡 안쪽이면 201 이고 계획 id 를 싣는다`() {
        every { facade.prepare(any()) } returns preparation()

        mockMvc.post("/api/v1/trade-preparations") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.planId") { value(1) }
            jsonPath("$.plannable") { value(true) }
        }.andReturn().response.contentAsString.let {
            // 키는 있고 값이 null 이다. 성공·실패 응답의 키 집합이 갈라지지 않는다.
            assertThat(objectMapper.readTree(it).get("code").isNull).isTrue()
        }
    }

    /**
     * 캡 위반은 예외가 아니라 결과로 온다. status 만 422 로 바꾸고 본문에서 캡 정보를 떨어뜨리면
     * design.md §3 의 "위반한 캡을 응답에 명시한다"를 충족하지 못한다.
     */
    @Test
    fun `캡 위반 결과는 422 이고 위반한 캡과 안정된 code 를 본문에 싣는다`() {
        every { facade.prepare(any()) } returns preparation(
            planId = null,
            status = null,
            plannable = false,
            capViolations = listOf("EFFICIENCY_CAP", "LEVERAGE_CAP"),
        )

        mockMvc.post("/api/v1/trade-preparations") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("CAP_VIOLATED") }
            jsonPath("$.capViolations[0]") { value("EFFICIENCY_CAP") }
            jsonPath("$.capViolations[1]") { value("LEVERAGE_CAP") }
        }.andReturn().response.contentAsString.let {
            assertThat(objectMapper.readTree(it).get("planId").isNull).isTrue()
        }
    }

    /**
     * 반올림으로 물량이 0 이 되는 경우다. 계획이 없으므로 201 이 아니고, 위반한 캡이 없으므로
     * `CAP_VIOLATED` 도 아니다. 그렇다고 `code` 를 비우면 클라이언트가 이 422 를 파싱 실패와
     * 구별하지 못하므로 `NOT_PLANNABLE` 을 싣는다.
     */
    @Test
    fun `캡 위반이 없어도 계획을 만들지 못하면 422 NOT_PLANNABLE 이다`() {
        every { facade.prepare(any()) } returns preparation(planId = null, status = null, plannable = false)

        mockMvc.post("/api/v1/trade-preparations") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("NOT_PLANNABLE") }
            jsonPath("$.capViolations.length()") { value(0) }
        }
    }

    @Test
    fun `owner 는 요청 body 가 아니라 인증 principal 에서 온다`() {
        val criteria = slot<TradePreparationCriteria.Prepare>()
        every { facade.prepare(capture(criteria)) } returns preparation()

        mockMvc.post("/api/v1/trade-preparations") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            // body 가 다른 회원을 지목해도 무시돼야 한다.
            content = prepareBody(extra = """, "ownerId": 999, "memberId": 999""")
        }.andExpect { status { isCreated() } }

        assertThat(criteria.captured.memberId).isEqualTo(7L)
    }

    @Test
    fun `목표 등록도 principal 의 memberId 로 Criteria 를 만든다`() {
        val criteria = slot<TradePreparationCriteria.RegisterTarget>()
        every { facade.registerTarget(capture(criteria)) } returns detail(status = "WATCHING")

        mockMvc.post("/api/v1/trade-preparations/42/target") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = """{"desiredEntryPremiumRate": 0.5, "memberId": 999}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("WATCHING") }
        }

        assertThat(criteria.captured.memberId).isEqualTo(7L)
        assertThat(criteria.captured.planId).isEqualTo(42L)
        assertThat(criteria.captured.desiredEntryPremiumRate).isEqualByComparingTo("0.5")
    }

    @Test
    fun `refresh 와 invalidate 는 각자의 Criteria 로 200 을 돌려준다`() {
        every { facade.refresh(TradePreparationCriteria.Refresh(42L, 7L)) } returns detail(status = "INVALIDATED")
        every { facade.invalidate(TradePreparationCriteria.Invalidate(42L, 7L)) } returns detail(status = "INVALIDATED")

        mockMvc.post("/api/v1/trade-preparations/42/refresh") {
            principal = this@TradePreparationControllerTest.principal
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("INVALIDATED") }
        }
        mockMvc.post("/api/v1/trade-preparations/42/invalidate") {
            principal = this@TradePreparationControllerTest.principal
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `단건 조회 미발견은 404 envelope 다`() {
        every { facade.findById(TradePreparationCriteria.FindById(42L, 7L)) } throws
            ApplicationException(ApplicationError.TRADE_PREPARATION_NOT_FOUND)

        mockMvc.get("/api/v1/trade-preparations/42") {
            principal = this@TradePreparationControllerTest.principal
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("TRADE_PREPARATION_NOT_FOUND") }
        }
    }

    @Test
    fun `보유 추적이 있으면 409 다`() {
        every { facade.prepare(any()) } throws ApplicationException(ApplicationError.ACTIVE_TRACKING_EXISTS)

        mockMvc.post("/api/v1/trade-preparations") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody()
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ACTIVE_TRACKING_EXISTS") }
        }
    }

    @Test
    fun `해외 잔고가 0 이면 transport 400 이다`() {
        mockMvc.post("/api/v1/trade-preparations") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = prepareBody(foreignBalance = "0")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `목표 프리미엄이 없으면 transport 400 이다`() {
        mockMvc.post("/api/v1/trade-preparations/42/target") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isBadRequest() } }
    }

    /** 역프리미엄 진입은 정상 시나리오다 — 목표값에 부호 제약을 두지 않는다. */
    @Test
    fun `음수 목표 프리미엄도 받는다`() {
        val criteria = slot<TradePreparationCriteria.RegisterTarget>()
        every { facade.registerTarget(capture(criteria)) } returns detail(status = "WATCHING")

        mockMvc.post("/api/v1/trade-preparations/42/target") {
            principal = this@TradePreparationControllerTest.principal
            contentType = MediaType.APPLICATION_JSON
            content = """{"desiredEntryPremiumRate": -1.25}"""
        }.andExpect { status { isOk() } }

        assertThat(criteria.captured.desiredEntryPremiumRate).isEqualByComparingTo("-1.25")
    }

    private fun prepareBody(foreignBalance: String = "1000", extra: String = ""): String = """
        {
          "symbol": "BTC",
          "koreaExchange": "BITHUMB",
          "foreignExchange": "BINANCE",
          "koreaBalance": 5000000,
          "foreignBalance": $foreignBalance$extra
        }
    """.trimIndent()

    @Suppress("LongParameterList")
    private fun preparation(
        planId: Long? = 1L,
        status: String? = "DRAFT",
        plannable: Boolean = true,
        capViolations: List<String> = emptyList(),
    ) = TradePreparationResult.Preparation(
        planId = planId,
        status = status,
        symbol = "BTC",
        koreaExchange = "BITHUMB",
        foreignExchange = "BINANCE",
        balanceSnapshotId = "declared-1",
        koreaBalance = BigDecimal("5000000"),
        foreignBalance = BigDecimal("1000"),
        balanceBasis = "UNVERIFIED",
        balanceObservedAt = OBSERVED_AT,
        balanceRatio = BigDecimal("3.49"),
        rawLeverage = BigDecimal("3.45"),
        rawQuantity = BigDecimal("0.0385"),
        koreaRoundedQuantity = BigDecimal("0.0385"),
        foreignRoundedQuantity = BigDecimal("0.038"),
        quantity = BigDecimal("0.038"),
        leverage = BigDecimal("3.401"),
        koreaShare = BigDecimal("0.777"),
        liquidationDistance = BigDecimal("0.294"),
        capViolations = capViolations,
        plannable = plannable,
        referenceForeignPrice = BigDecimal("89500"),
        referenceFxRate = BigDecimal("1432.6"),
        referencePremiumRate = BigDecimal("1.04"),
        referenceObservedAt = OBSERVED_AT,
        referenceFxSource = "FX_PROVIDER",
        referenceFxObservedAt = OBSERVED_AT,
        previousTracking = null,
    )

    private fun detail(status: String) = TradePreparationResult.Detail(
        id = 42L,
        symbol = "BTC",
        koreaExchange = "BITHUMB",
        foreignExchange = "BINANCE",
        status = status,
        boundBalanceSnapshotId = "declared-1",
        boundBalanceBasis = "UNVERIFIED",
        quantity = BigDecimal("0.038"),
        leverage = BigDecimal("3.401"),
        referenceForeignPrice = BigDecimal("89500"),
        referenceFxRate = BigDecimal("1432.6"),
        referencePremiumRate = BigDecimal("1.04"),
        referenceObservedAt = OBSERVED_AT,
        referenceFxSource = "FX_PROVIDER",
        referenceFxObservedAt = OBSERVED_AT,
        desiredEntryPremiumRate = BigDecimal("0.5"),
        conditionFirstMetAt = null,
        conditionFirstMetPremiumRate = null,
        invalidationReason = if (status == "INVALIDATED") "OWNER_REFRESH" else null,
        invalidatedAt = if (status == "INVALIDATED") OBSERVED_AT else null,
        version = 1L,
    )

    companion object {
        private val OBSERVED_AT: Instant = Instant.parse("2026-08-30T00:00:00Z")
    }
}
