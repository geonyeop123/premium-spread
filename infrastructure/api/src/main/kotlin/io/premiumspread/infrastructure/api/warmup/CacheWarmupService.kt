package io.premiumspread.infrastructure.api.warmup

import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.validation.annotation.Validated
import java.util.concurrent.TimeUnit

@Validated
@ConfigurationProperties(prefix = "warmup")
data class WarmupProperties(
    val enabled: Boolean = true,
    val symbols: List<String> = listOf("btc"),
    val exchanges: List<ExchangePair> = listOf(ExchangePair("bithumb", "btc"), ExchangePair("binance", "btc")),
) {
    init {
        require(symbols.all(String::isNotBlank)) { "warmup.symbols must not contain blank values" }
        require(exchanges.all { it.exchange.isNotBlank() && it.symbol.isNotBlank() }) {
            "warmup.exchanges exchange and symbol must not be blank"
        }
    }

    data class ExchangePair(val exchange: String = "", val symbol: String = "")
}

class CacheWarmupService(
    private val premiumService: PremiumService,
    private val tickerService: TickerService,
    private val properties: WarmupProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun warmup() {
        if (!properties.enabled) return
        val startedAt = System.nanoTime()
        var successCount = 0
        var failCount = 0
        properties.symbols.forEach { symbol ->
            runCatching { premiumService.findLatestSnapshotBySymbol(Symbol(symbol)) }
                .onSuccess { successCount++ }
                .onFailure {
                    failCount++
                    log.warn("Failed to warm up premium snapshot: {}", symbol, it)
                }
        }
        properties.exchanges.forEach { pair ->
            runCatching { tickerService.findLatestSnapshot(pair.exchange, pair.symbol) }
                .onSuccess { successCount++ }
                .onFailure {
                    failCount++
                    log.warn("Failed to warm up ticker snapshot: {}:{}", pair.exchange, pair.symbol, it)
                }
        }
        log.info(
            "Cache warmup completed in {}ms (success={}, fail={})",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            successCount,
            failCount,
        )
    }
}
