package io.premiumspread.infrastructure.tradeprep

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.TradePreparationRepository
import io.premiumspread.domain.tradeprep.TradePreparationSpec
import io.premiumspread.domain.tradeprep.TradePreparation
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant

/**
 * AC14(design.md D12) — 계획 레코드가 `MarketPair`와 해외가·FX·프리미엄의 snapshot id·관측
 * 시각·출처를 보존해, 같은 계획을 나중에 같은 입력으로 재현할 수 있다.
 *
 * `JpaTradePreparationRepositoryAdapter`(T4)를 실제 MySQL round-trip으로 검증한다 — save 뒤
 * `findById`로 다시 읽은 값이 저장 전 입력과 필드 단위로 일치해야 provenance 재현이 성립한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, io.premiumspread.config.TestConfig::class)
class TradePreparationProvenanceIntegrationTest @Autowired constructor(
    private val tradePreparationRepository: TradePreparationRepository,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    private var ownerId: Long = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        val member = memberRepository.save(
            Member.create(
                email = "trade-prep-provenance@example.com",
                encodedPassword = passwordEncoder.encode("password123"),
            ),
        )
        ownerId = member.id
    }

    @Test
    fun `저장 뒤 조회한 계획은 MarketPair와 해외가·FX·프리미엄 provenance를 그대로 보존한다`() {
        val referenceObservedAt = Instant.parse("2026-08-30T00:00:00Z")
        val referenceFxObservedAt = Instant.parse("2026-08-29T23:30:00Z")
        val pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE)

        val spec = TradePreparationSpec(
            ownerId = ownerId,
            pair = pair,
            boundBalanceSnapshotId = "declared-provenance-1",
            boundBalanceBasis = BalanceBasis.UNVERIFIED,
            referenceForeignPrice = BigDecimal("70123.4567891234"),
            referenceFxRate = BigDecimal("1401.234567"),
            referencePremiumRate = BigDecimal("3.51"),
            referenceObservedAt = referenceObservedAt,
            referenceFxSource = Exchange.FX_PROVIDER,
            referenceFxObservedAt = referenceFxObservedAt,
            quantity = BigDecimal("0.1234567891"),
            leverage = BigDecimal("3.0000000000"),
        )

        val saved = tradePreparationRepository.save(TradePreparation.create(spec))
        val reloaded = tradePreparationRepository.findById(saved.id)

        assertThat(reloaded).isNotNull
        val plan = reloaded!!
        assertThat(plan.pair).isEqualTo(pair)
        assertThat(plan.referenceForeignPrice).isEqualByComparingTo(spec.referenceForeignPrice)
        assertThat(plan.referenceFxRate).isEqualByComparingTo(spec.referenceFxRate)
        assertThat(plan.referencePremiumRate).isEqualByComparingTo(spec.referencePremiumRate)
        assertThat(plan.referenceObservedAt).isEqualTo(spec.referenceObservedAt)
        assertThat(plan.referenceFxSource).isEqualTo(spec.referenceFxSource)
        assertThat(plan.referenceFxObservedAt).isEqualTo(spec.referenceFxObservedAt)
        assertThat(plan.quantity).isEqualByComparingTo(spec.quantity)
        assertThat(plan.leverage).isEqualByComparingTo(spec.leverage)
        assertThat(plan.boundBalanceSnapshotId).isEqualTo(spec.boundBalanceSnapshotId)
        assertThat(plan.boundBalanceBasis).isEqualTo(spec.boundBalanceBasis)
    }

    @Test
    fun `soft-deleted 계획은 provenance가 남아있어도 findById로 조회되지 않는다`() {
        val plan = tradePreparationRepository.save(
            TradePreparation.create(
                TradePreparationSpec(
                    ownerId = ownerId,
                    pair = MarketPair(Symbol("BTC"), Exchange.BITHUMB, Exchange.BINANCE),
                    boundBalanceSnapshotId = "declared-provenance-2",
                    boundBalanceBasis = BalanceBasis.UNVERIFIED,
                    referenceForeignPrice = BigDecimal("70000"),
                    referenceFxRate = BigDecimal("1400"),
                    referencePremiumRate = BigDecimal("3.50"),
                    referenceObservedAt = Instant.parse("2026-08-30T00:00:00Z"),
                    referenceFxSource = Exchange.FX_PROVIDER,
                    referenceFxObservedAt = Instant.parse("2026-08-29T23:30:00Z"),
                    quantity = BigDecimal("0.1"),
                    leverage = BigDecimal("3"),
                ),
            ),
        )
        plan.delete(Instant.parse("2026-08-30T01:00:00Z"))
        tradePreparationRepository.save(plan)

        assertThat(tradePreparationRepository.findById(plan.id)).isNull()
    }
}
