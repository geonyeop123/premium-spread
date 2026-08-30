package io.premiumspread.domain.tradeprep

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

/**
 * [TradePreparation.create]의 생성 입력이다. D12가 요구하는 provenance(가격·FX·프리미엄의
 * 관측 시각·출처)와 결속 잔고 스냅샷 정보를 이 시점에 전부 받는다 — 이후 값은 immutable하다.
 */
data class TradePreparationSpec(
    val ownerId: Long,
    val pair: MarketPair,
    val boundBalanceSnapshotId: String,
    val boundBalanceBasis: BalanceBasis,
    val referenceForeignPrice: BigDecimal,
    val referenceFxRate: BigDecimal,
    val referencePremiumRate: BigDecimal,
    val referenceObservedAt: Instant,
    val referenceFxSource: Exchange,
    val referenceFxObservedAt: Instant,
    val quantity: BigDecimal,
    val leverage: BigDecimal,
)

/**
 * 거래 준비 계획 (design.md §2 D4~D23). 상태 기계는 [TradePreparationStatus]가 정의한다.
 *
 * `version`은 D11의 조건부 갱신(`WHERE id=? AND version=? AND status=?`)이 참조하는 business
 * 카운터다. 모든 상태 전이가 이 값을 증가시킨다 — 실제 CAS 갱신문(영속화 시점의 SQL)은
 * infrastructure adapter(T3·T4)가 구성한다. `lockVersion`은 Hibernate 자체 낙관적 락 안전망이며
 * 도메인 로직이 직접 다루지 않는다(`NotificationSubscription.lockVersion`과 같은 패턴).
 *
 * 무효화 트리거는 [invalidateOnTrackingEvent]·[invalidateOnOwnerRefresh]·
 * [invalidateOnReconcileMismatch] 셋뿐이다(D4) — 시간 경과로 상태를 바꾸는 메서드는 존재하지
 * 않는다. [TradePreparationStatus.INVALIDATED]는 종점이라 이 클래스의 어떤 메서드도 거기서
 * 다른 상태로 되돌리지 않는다(D11).
 */
@Entity
@Table(
    name = "trade_preparation",
    indexes = [Index(name = "idx_trade_preparation_owner_id", columnList = "owner_id")],
)
@Suppress("LongParameterList")
class TradePreparation private constructor(
    @Column(name = "owner_id", nullable = false, updatable = false)
    val ownerId: Long,

    @Embedded
    @AttributeOverride(name = "code", column = Column(name = "symbol"))
    val symbol: Symbol,

    @Enumerated(EnumType.STRING)
    @Column(name = "korea_exchange", nullable = false, updatable = false, length = 50)
    val koreaExchange: Exchange,

    @Enumerated(EnumType.STRING)
    @Column(name = "foreign_exchange", nullable = false, updatable = false, length = 50)
    val foreignExchange: Exchange,

    @Column(name = "reference_foreign_price", nullable = false, updatable = false, precision = 30, scale = 10)
    val referenceForeignPrice: BigDecimal,

    @Column(name = "reference_fx_rate", nullable = false, updatable = false, precision = 20, scale = 6)
    val referenceFxRate: BigDecimal,

    @Column(name = "reference_premium_rate", nullable = false, updatable = false, precision = 10, scale = 2)
    val referencePremiumRate: BigDecimal,

    @Column(name = "reference_observed_at", nullable = false, updatable = false)
    val referenceObservedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_fx_source", nullable = false, updatable = false, length = 50)
    val referenceFxSource: Exchange,

    @Column(name = "reference_fx_observed_at", nullable = false, updatable = false)
    val referenceFxObservedAt: Instant,

    @Column(name = "quantity", nullable = false, updatable = false, precision = 30, scale = 10)
    val quantity: BigDecimal,

    @Column(name = "leverage", nullable = false, updatable = false, precision = 20, scale = 10)
    val leverage: BigDecimal,

    boundBalanceSnapshotId: String,
    boundBalanceBasis: BalanceBasis,
    status: TradePreparationStatus,
) : BaseEntity() {

    @Column(name = "bound_balance_snapshot_id", nullable = false, length = 100)
    var boundBalanceSnapshotId: String = boundBalanceSnapshotId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "bound_balance_basis", nullable = false, length = 20)
    var boundBalanceBasis: BalanceBasis = boundBalanceBasis
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: TradePreparationStatus = status
        protected set

    @Column(name = "desired_premium_rate", precision = 10, scale = 2)
    var desiredPremiumRate: BigDecimal? = null
        protected set

    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @Version
    @Column(name = "lock_version", nullable = false)
    private var lockVersion: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "invalidation_reason", length = 30)
    var invalidationReason: TradePreparationInvalidationReason? = null
        protected set

    @Column(name = "invalidated_at")
    var invalidatedAt: Instant? = null
        protected set

    @Column(name = "condition_first_met_at")
    var conditionFirstMetAt: Instant? = null
        protected set

    @Column(name = "condition_first_met_premium_rate", precision = 10, scale = 2)
    var conditionFirstMetPremiumRate: BigDecimal? = null
        protected set

    val pair: MarketPair
        get() = MarketPair(symbol, koreaExchange, foreignExchange)

    /** owner당 유일해야 하는 활성 상태다 (D16·D23). DB `active_key`(T3)가 이 정의와 동기화된다. */
    val isActive: Boolean
        get() = status == TradePreparationStatus.WATCHING || status == TradePreparationStatus.ARMED

    /**
     * owner 희망 프리미엄을 받아 `WATCHING`으로 전이한다 (D6·D7). 결속 잔고는 이 시점에
     * (재)기록한다 — 검증 수준은 호출자가 가용한 원천에 따라 결정한다(D20).
     */
    fun registerTarget(
        desiredPremiumRate: BigDecimal,
        boundBalanceSnapshotId: String,
        boundBalanceBasis: BalanceBasis,
        at: Instant,
    ) {
        if (status != TradePreparationStatus.DRAFT) {
            throw InvalidTradePreparationException("registerTarget requires DRAFT status, was $status")
        }
        if (boundBalanceSnapshotId.isBlank()) {
            throw InvalidTradePreparationException("boundBalanceSnapshotId must not be blank.")
        }
        this.desiredPremiumRate = desiredPremiumRate
        this.boundBalanceSnapshotId = boundBalanceSnapshotId
        this.boundBalanceBasis = boundBalanceBasis
        this.status = TradePreparationStatus.WATCHING
        version++
    }

    /**
     * 현재 프리미엄으로 조건을 평가한다 (D7·D14·D19). 신선도·`MarketPair` 일치 판정은 호출자
     * 책임이다(D14, D21) — 이 메서드는 이미 유효하다고 확인된 관측값만 받는다.
     */
    fun evaluateCondition(currentPremiumRate: BigDecimal, observedAt: Instant): TradePreparationConditionOutcome {
        if (status != TradePreparationStatus.WATCHING) {
            throw InvalidTradePreparationException("evaluateCondition requires WATCHING status, was $status")
        }
        val desired = desiredPremiumRate
            ?: throw InvalidTradePreparationException("desiredPremiumRate must be set before evaluateCondition")

        // 이 단위는 진입 준비다 — 낮은 프리미엄에서 진입하고 높은 프리미엄에서 종료한다
        // (master spec §1.2, ECO-5 §1 "재진입 | 프리미엄이 직전 진입 수준으로 복귀 시"). 종료
        // 직후 프리미엄은 진입보다 높으므로 재진입은 프리미엄이 목표까지 "내려와야" 충족이다.
        // 정확히 같아도 충족으로 판정한다(경계 포함).
        if (currentPremiumRate > desired) {
            return TradePreparationConditionOutcome.NOT_MET
        }

        if (conditionFirstMetAt == null) {
            conditionFirstMetAt = observedAt
            conditionFirstMetPremiumRate = currentPremiumRate
        }

        if (boundBalanceBasis == BalanceBasis.UNVERIFIED) {
            return TradePreparationConditionOutcome.OBSERVED_ONLY
        }

        status = TradePreparationStatus.ARMED
        version++
        return TradePreparationConditionOutcome.ARMED
    }

    /** 체결 무효화 (D4·D17) — tracking 생성·종료 경로가 같은 트랜잭션에서 호출한다. */
    fun invalidateOnTrackingEvent(at: Instant): Boolean =
        invalidate(TradePreparationInvalidationReason.TRACKING_EVENT, at)

    /** owner 명시 refresh 무효화 (D4·D11). */
    fun invalidateOnOwnerRefresh(at: Instant): Boolean =
        invalidate(TradePreparationInvalidationReason.OWNER_REFRESH, at)

    /**
     * reconcile 불일치 무효화 (D4·D5·D17). 판정용 잔고 스냅샷 id가 결속된 id와 다를 때만
     * 무효화한다(AC5) — 같으면 아무 것도 하지 않고 `false`를 반환한다.
     */
    fun invalidateOnReconcileMismatch(currentBalanceSnapshotId: String, at: Instant): Boolean {
        if (currentBalanceSnapshotId == boundBalanceSnapshotId) return false
        return invalidate(TradePreparationInvalidationReason.RECONCILE_MISMATCH, at)
    }

    /**
     * `INVALIDATED`는 종점이다(D11) — 이미 무효화됐으면 아무 것도 하지 않고 `false`를 반환한다.
     */
    private fun invalidate(reason: TradePreparationInvalidationReason, at: Instant): Boolean {
        if (status == TradePreparationStatus.INVALIDATED) return false
        status = TradePreparationStatus.INVALIDATED
        invalidationReason = reason
        invalidatedAt = at
        version++
        return true
    }

    companion object {
        fun create(spec: TradePreparationSpec): TradePreparation {
            validatePositive("quantity", spec.quantity)
            validatePositive("leverage", spec.leverage)
            validatePositive("referenceForeignPrice", spec.referenceForeignPrice)
            validatePositive("referenceFxRate", spec.referenceFxRate)
            if (spec.boundBalanceSnapshotId.isBlank()) {
                throw InvalidTradePreparationException("boundBalanceSnapshotId must not be blank.")
            }

            return TradePreparation(
                ownerId = spec.ownerId,
                symbol = spec.pair.symbol,
                koreaExchange = spec.pair.koreaExchange,
                foreignExchange = spec.pair.foreignExchange,
                referenceForeignPrice = spec.referenceForeignPrice,
                referenceFxRate = spec.referenceFxRate,
                referencePremiumRate = spec.referencePremiumRate,
                referenceObservedAt = spec.referenceObservedAt,
                referenceFxSource = spec.referenceFxSource,
                referenceFxObservedAt = spec.referenceFxObservedAt,
                quantity = spec.quantity,
                leverage = spec.leverage,
                boundBalanceSnapshotId = spec.boundBalanceSnapshotId,
                boundBalanceBasis = spec.boundBalanceBasis,
                status = TradePreparationStatus.DRAFT,
            )
        }

        private fun validatePositive(name: String, value: BigDecimal) {
            if (value <= BigDecimal.ZERO) {
                throw InvalidTradePreparationException("TradePreparation $name must be positive: $value")
            }
        }
    }
}
