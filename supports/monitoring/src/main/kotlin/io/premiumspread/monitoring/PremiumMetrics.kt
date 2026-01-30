package io.premiumspread.monitoring

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * 프리미엄 관련 메트릭
 *
 * 실시간 프리미엄 값을 Gauge로 노출하여 Prometheus/Grafana에서 모니터링
 */
class PremiumMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private val premiumRates = mutableMapOf<String, AtomicReference<Double>>()
    private val koreaPrices = mutableMapOf<String, AtomicReference<Double>>()
    private val foreignPrices = mutableMapOf<String, AtomicReference<Double>>()
    private val fxRates = AtomicReference(0.0)

    init {
        // 기본 BTC 프리미엄 게이지 등록
        registerPremiumGauge("btc")

        // USD/KRW 환율 게이지
        Gauge.builder("fx.rate.current", fxRates) { it.get() }
            .tag("base", "usd")
            .tag("quote", "krw")
            .description("Current USD/KRW exchange rate")
            .register(meterRegistry)
    }

    /**
     * 프리미엄 게이지 등록
     */
    fun registerPremiumGauge(symbol: String) {
        val rateRef = premiumRates.computeIfAbsent(symbol) { AtomicReference(0.0) }
        val koreaRef = koreaPrices.computeIfAbsent(symbol) { AtomicReference(0.0) }
        val foreignRef = foreignPrices.computeIfAbsent(symbol) { AtomicReference(0.0) }

        Gauge.builder("premium.rate.current", rateRef) { it.get() }
            .tag("symbol", symbol)
            .description("Current premium rate percentage")
            .register(meterRegistry)

        Gauge.builder("ticker.price.current", koreaRef) { it.get() }
            .tag("symbol", symbol)
            .tag("market", "korea")
            .description("Current ticker price in Korean market")
            .register(meterRegistry)

        Gauge.builder("ticker.price.current", foreignRef) { it.get() }
            .tag("symbol", symbol)
            .tag("market", "foreign")
            .description("Current ticker price in foreign market (in KRW)")
            .register(meterRegistry)
    }

    /**
     * 프리미엄 값 업데이트
     */
    fun updatePremium(
        symbol: String,
        premiumRate: BigDecimal,
        koreaPrice: BigDecimal,
        foreignPriceInKrw: BigDecimal,
    ) {
        premiumRates[symbol]?.set(premiumRate.toDouble())
        koreaPrices[symbol]?.set(koreaPrice.toDouble())
        foreignPrices[symbol]?.set(foreignPriceInKrw.toDouble())
    }

    /**
     * 환율 업데이트
     */
    fun updateFxRate(rate: BigDecimal) {
        fxRates.set(rate.toDouble())
    }
}
