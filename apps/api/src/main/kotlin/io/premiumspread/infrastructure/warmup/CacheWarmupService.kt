package io.premiumspread.infrastructure.warmup

import io.premiumspread.domain.premium.PremiumService
import io.premiumspread.domain.ticker.Symbol
import io.premiumspread.domain.ticker.TickerService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "warmup")
data class WarmupProperties(
    val enabled: Boolean = true,
    val symbols: List<String> = listOf("btc"),
    val exchanges: List<ExchangePair> = listOf(
        ExchangePair("bithumb", "btc"),
        ExchangePair("binance", "btc"),
    ),
) {
    data class ExchangePair(
        val exchange: String = "",
        val symbol: String = "",
    )
}

/**
 * API 서버 시작 시 주요 데이터의 DB fallback 경로를 미리 실행하여 캐시를 워밍업한다.
 *
 * 각 데이터 조회는 RepositoryImpl 내부에서 cache miss 시 DB fallback을 수행하므로,
 * 여기서는 단순히 도메인 서비스의 조회 메서드를 호출하는 것만으로 워밍업이 완료된다.
 *
 * 워밍업 실패 시 서버 시작을 막지 않는다 (runCatching).
 */
@Component
class CacheWarmupService(
    private val premiumService: PremiumService,
    private val tickerService: TickerService,
    private val warmupProperties: WarmupProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun warmup() {
        if (!warmupProperties.enabled) {
            log.info("Cache warmup is disabled")
            return
        }

        log.info("Cache warmup started")
        val startTime = System.currentTimeMillis()
        var successCount = 0
        var failCount = 0

        // 프리미엄 스냅샷 워밍업
        for (symbol in warmupProperties.symbols) {
            runCatching {
                premiumService.findLatestSnapshotBySymbol(Symbol(symbol))
            }.onSuccess {
                successCount++
                log.debug("Warmed up premium snapshot: {} (found={})", symbol, it != null)
            }.onFailure {
                failCount++
                log.warn("Failed to warm up premium snapshot: {}", symbol, it)
            }
        }

        // 티커 스냅샷 워밍업
        for (pair in warmupProperties.exchanges) {
            runCatching {
                tickerService.findLatestSnapshot(pair.exchange, pair.symbol)
            }.onSuccess {
                successCount++
                log.debug("Warmed up ticker snapshot: {}:{} (found={})", pair.exchange, pair.symbol, it != null)
            }.onFailure {
                failCount++
                log.warn("Failed to warm up ticker snapshot: {}:{}", pair.exchange, pair.symbol, it)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        log.info("Cache warmup completed in {}ms (success={}, fail={})", elapsed, successCount, failCount)
    }
}
