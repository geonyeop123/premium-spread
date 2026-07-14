package io.premiumspread.domain.premium

import io.premiumspread.domain.aggregation.AggregationZone
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PremiumService(
    private val premiumRepository: PremiumRepository,
) {
    companion object {
        private val MAX_RANGE = mapOf(
            "1m" to Duration.ofHours(24),
            "1h" to Duration.ofDays(30),
            "1d" to Duration.ofDays(365),
        )
    }

    @Transactional
    fun create(command: PremiumCommand.Create): Premium = premiumRepository.save(
        Premium.create(command.koreaTicker, command.foreignTicker, command.fxTicker),
    )

    @Transactional
    fun save(premium: Premium): Premium = premiumRepository.save(premium)

    @Transactional(readOnly = true)
    fun findLatest(pair: MarketPair): Premium? = premiumRepository.findLatestByPair(pair)

    @Transactional(readOnly = true)
    fun findLatestBySymbol(symbol: Symbol): Premium? = findLatest(MarketPair.default(symbol))

    @Transactional(readOnly = true)
    fun findLatestSnapshot(pair: MarketPair): PremiumSnapshot? = premiumRepository.findLatestSnapshotByPair(pair)
        ?.also { require(it.pair == pair) { "Premium snapshot pair does not match requested pair." } }

    @Transactional(readOnly = true)
    fun findLatestSnapshotBySymbol(symbol: Symbol): PremiumSnapshot? = findLatestSnapshot(MarketPair.default(symbol))

    @Transactional(readOnly = true)
    fun findAll(pair: MarketPair, from: Instant, to: Instant): List<Premium> =
        premiumRepository.findAllByPair(pair, from, to)

    @Transactional(readOnly = true)
    fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium> =
        findAll(MarketPair.default(symbol), from, to)

    @Transactional(readOnly = true)
    fun findAggregation(
        pair: MarketPair,
        interval: String,
        from: Instant,
        to: Instant,
        zone: AggregationZone,
    ): List<PremiumAggregationSnapshot> {
        require(from < to) { "Aggregation range must have from < to." }
        val maxRange = MAX_RANGE[interval]
            ?: throw IllegalArgumentException("Invalid interval: $interval. Allowed: 1m, 1h, 1d")
        val clampedFrom = maxOf(from, to.minus(maxRange))
        val (normalizedFrom, normalizedTo) = normalizeRange(interval, clampedFrom, to, zone)
        return premiumRepository.findAggregationByPair(pair, interval, normalizedFrom, normalizedTo)
            .also { snapshots ->
                require(snapshots.all { it.pair == pair }) {
                    "Premium aggregation snapshot pair does not match requested pair."
                }
            }
    }

    @Transactional(readOnly = true)
    fun findAggregation(
        symbol: Symbol,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot> = findAggregation(
        pair = MarketPair.default(symbol),
        interval = interval,
        from = from,
        to = to,
        zone = AggregationZone.DEFAULT,
    )

    private fun normalizeRange(
        interval: String,
        from: Instant,
        to: Instant,
        zone: AggregationZone,
    ): Pair<Instant, Instant> = when (interval) {
        "1m" -> from.truncatedTo(ChronoUnit.MINUTES) to
            to.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
        "1h" -> from.truncatedTo(ChronoUnit.HOURS) to
            to.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
        "1d" -> {
            val zoneId = zone.zoneId
            from.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant() to
                to.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant()
        }
        else -> error("Interval validation must run before normalization: $interval")
    }
}
