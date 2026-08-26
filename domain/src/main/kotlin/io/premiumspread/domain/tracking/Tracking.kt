package io.premiumspread.domain.tracking

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.premium.PremiumPolicy
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class TrackingRecordSpec(
    val memberId: Long,
    val pair: MarketPair,
    val koreaQuantity: BigDecimal,
    val koreaEntryPrice: BigDecimal,
    val foreignQuantity: BigDecimal,
    val foreignEntryPrice: BigDecimal,
    val foreignLeverage: Int,
    val entryFxRate: BigDecimal,
    val entryObservedAt: Instant,
)

// JPA aggregate state stays explicit so persistence and domain invariants share one constructor.
@Suppress("LongParameterList")
@Entity
@Table(name = "position")
class Tracking private constructor(
    @Embedded
    @AttributeOverride(name = "code", column = Column(name = "symbol"))
    val symbol: Symbol,

    @Enumerated(EnumType.STRING)
    @Column(name = "korea_exchange", nullable = false)
    val koreaExchange: Exchange,

    @Column(name = "korea_quantity", nullable = false, precision = 30, scale = 10)
    val koreaQuantity: BigDecimal,

    @Column(name = "korea_entry_price", nullable = false, precision = 30, scale = 10)
    val koreaEntryPrice: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "foreign_exchange", nullable = false)
    val foreignExchange: Exchange,

    @Column(name = "foreign_quantity", nullable = false, precision = 30, scale = 10)
    val foreignQuantity: BigDecimal,

    @Column(name = "foreign_entry_price", nullable = false, precision = 30, scale = 10)
    val foreignEntryPrice: BigDecimal,

    /**
     * 필요 증거금에만 영향을 주며 손익 금액에는 반영되지 않는다.
     * 선형 무기한선물에서 수량이 고정이면 손익은 레버리지와 무관하다.
     * 어떤 계산에도 쓰이지 않는 것이 정상이며, 기록·표시 전용이다 (design.md §5.6).
     */
    @Column(name = "foreign_leverage", nullable = false)
    val foreignLeverage: Int,

    @Column(name = "entry_fx_rate", nullable = false, precision = 20, scale = 6)
    val entryFxRate: BigDecimal,

    @Column(name = "entry_premium_rate", nullable = false, precision = 10, scale = 2)
    val entryPremiumRate: BigDecimal,

    @Column(name = "entry_observed_at", nullable = false)
    val entryObservedAt: Instant,

    @Convert(converter = TrackingStatusConverter::class)
    @Column(name = "status", nullable = false)
    var status: TrackingStatus = TrackingStatus.ACTIVE,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,
) : BaseEntity() {

    // ── 종료 시점 확정 스냅샷 ────────────────────────────────────────────
    // 전부 nullable 이다. V15 이전에 종료된 행과, V15 적용 후 이전 application image 가
    // 종료시킨 행은 이 컬럼들을 모른 채 status 만 바꾸기 때문이다 (design.md §5.8).

    @Column(name = "closed_at")
    var closedAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "close_price_source", length = 30)
    var closePriceSource: TrackingClosePriceSource? = null
        protected set

    @Column(name = "close_observed_at")
    var closeObservedAt: Instant? = null
        protected set

    @Column(name = "close_fx_observed_at")
    var closeFxObservedAt: Instant? = null
        protected set

    @Column(name = "close_korea_price", precision = 30, scale = 10)
    var closeKoreaPrice: BigDecimal? = null
        protected set

    @Column(name = "close_foreign_price", precision = 30, scale = 10)
    var closeForeignPrice: BigDecimal? = null
        protected set

    @Column(name = "close_fx_rate", precision = 20, scale = 6)
    var closeFxRate: BigDecimal? = null
        protected set

    @Column(name = "close_premium_rate", precision = 10, scale = 2)
    var closePremiumRate: BigDecimal? = null
        protected set

    /**
     * 확정 판정 규칙 (design.md §5.3.2) — **단일 정의**.
     *
     * 확정 응답이 노출하는 모든 값의 원천을 빠짐없이 포함한다. 응답 계약에 필드를 추가하면
     * 그 원천 컬럼도 여기에 들어가야 한다. 하나라도 빠지면 fail-closed 다.
     */
    val hasConfirmedClose: Boolean
        get() = status == TrackingStatus.ARCHIVED &&
            closePriceSource == TrackingClosePriceSource.MARKET_SNAPSHOT &&
            closedAt != null && closeObservedAt != null && closeFxObservedAt != null &&
            closeKoreaPrice != null && closeForeignPrice != null &&
            closeFxRate != null && closePremiumRate != null

    val pair: MarketPair
        get() = MarketPair(symbol, koreaExchange, foreignExchange)

    /**
     * gross 손익을 계산한다. 계산식 자체는 Phase 0 에서 바뀌지 않았다 — 이름과 메타데이터만 정직해졌다.
     * 입력 시세의 출처(현재 시장 / 확정 스냅샷)는 호출자가 결정한다.
     */
    fun grossPnl(
        koreaPrice: BigDecimal,
        foreignPrice: BigDecimal,
        fxRate: BigDecimal,
        premiumRate: BigDecimal,
        observedAt: Instant,
        fxObservedAt: Instant,
        calculatedAt: Instant,
    ): TrackingGrossPnl {
        require(koreaPrice > BigDecimal.ZERO) { "koreaPrice must be positive" }
        require(foreignPrice > BigDecimal.ZERO) { "foreignPrice must be positive" }
        require(fxRate > BigDecimal.ZERO) { "fxRate must be positive" }

        val koreaLeg = koreaPrice.subtract(koreaEntryPrice).multiply(koreaQuantity)
        val foreignLegUsd = foreignEntryPrice.subtract(foreignPrice).multiply(foreignQuantity)
        val foreignLeg = foreignLegUsd.multiply(fxRate)
        val total = koreaLeg.add(foreignLeg)
        val koreaNotional = koreaPrice.multiply(koreaQuantity)
        val percent = total
            .divide(koreaNotional, 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(2, RoundingMode.HALF_UP)

        return TrackingGrossPnl(
            premiumRateDelta = premiumRate.subtract(entryPremiumRate),
            entryPremiumRate = entryPremiumRate,
            referencePremiumRate = premiumRate,
            koreaLegGrossPnlKrw = koreaLeg,
            foreignLegGrossPnlKrw = foreignLeg,
            totalGrossPnlKrw = total,
            koreaLegNotionalKrw = koreaNotional,
            grossPnlPercentOfKoreaNotional = percent,
            calculatedAt = calculatedAt,
            observedAt = observedAt,
            fxObservedAt = fxObservedAt,
        )
    }

    /** 확정된 종료 스냅샷으로 gross 손익을 계산한다. [hasConfirmedClose] 가 참일 때만 호출한다. */
    fun confirmedGrossPnl(calculatedAt: Instant): TrackingGrossPnl {
        check(hasConfirmedClose) { "Tracking has no confirmed close snapshot" }
        return grossPnl(
            koreaPrice = closeKoreaPrice!!,
            foreignPrice = closeForeignPrice!!,
            fxRate = closeFxRate!!,
            premiumRate = closePremiumRate!!,
            observedAt = closeObservedAt!!,
            fxObservedAt = closeFxObservedAt!!,
            calculatedAt = calculatedAt,
        )
    }

    /**
     * 추적을 종료하고 그 시점 시세를 확정 저장한다.
     *
     * `snapshot` 이 `null` 이면 종료는 성공시키되 확정 손익을 갖지 않는다. 사용자가 종료하려는 순간에
     * 시세가 없다고 거절하는 것은 추적 도구로서 부당하고, 없는 시세를 추정해 채우는 것은 거짓 확정이다.
     */
    fun archive(snapshot: TrackingCloseSnapshot?, archivedAt: Instant) {
        if (status == TrackingStatus.ARCHIVED) {
            throw InvalidTrackingException("Tracking is already archived.")
        }
        status = TrackingStatus.ARCHIVED
        closedAt = archivedAt
        if (snapshot == null) {
            closePriceSource = TrackingClosePriceSource.SNAPSHOT_UNAVAILABLE
            return
        }
        closePriceSource = TrackingClosePriceSource.MARKET_SNAPSHOT
        closeObservedAt = snapshot.observedAt
        closeFxObservedAt = snapshot.fxObservedAt
        closeKoreaPrice = snapshot.koreaPrice
        closeForeignPrice = snapshot.foreignPrice
        closeFxRate = snapshot.fxRate
        closePremiumRate = snapshot.premiumRate
    }

    companion object {
        fun create(spec: TrackingRecordSpec): Tracking {
            validatePositive("koreaQuantity", spec.koreaQuantity)
            validatePositive("koreaEntryPrice", spec.koreaEntryPrice)
            validatePositive("foreignQuantity", spec.foreignQuantity)
            validatePositive("foreignEntryPrice", spec.foreignEntryPrice)
            validatePositive("entryFxRate", spec.entryFxRate)
            validateLeverage(spec.foreignLeverage)

            val entryPremiumRate = PremiumPolicy.calculate(
                koreaPrice = spec.koreaEntryPrice,
                foreignPriceUsd = spec.foreignEntryPrice,
                fxRate = spec.entryFxRate,
            ).entityPremiumRate

            return Tracking(
                symbol = spec.pair.symbol,
                koreaExchange = spec.pair.koreaExchange,
                koreaQuantity = spec.koreaQuantity,
                koreaEntryPrice = spec.koreaEntryPrice,
                foreignExchange = spec.pair.foreignExchange,
                foreignQuantity = spec.foreignQuantity,
                foreignEntryPrice = spec.foreignEntryPrice,
                foreignLeverage = spec.foreignLeverage,
                entryFxRate = spec.entryFxRate,
                entryPremiumRate = entryPremiumRate,
                entryObservedAt = spec.entryObservedAt,
                memberId = spec.memberId,
            )
        }

        private fun validatePositive(name: String, value: BigDecimal) {
            if (value <= BigDecimal.ZERO) {
                throw InvalidTrackingException("Tracking $name must be positive.")
            }
        }

        private fun validateLeverage(leverage: Int) {
            if (leverage < 1 || leverage > 125) {
                throw InvalidTrackingException("Foreign leverage must be between 1 and 125.")
            }
        }
    }
}
