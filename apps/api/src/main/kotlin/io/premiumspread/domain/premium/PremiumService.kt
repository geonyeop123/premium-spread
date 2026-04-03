package io.premiumspread.domain.premium

import io.premiumspread.domain.ticker.Symbol
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    fun create(command: PremiumCommand.Create): Premium {
        val premium = Premium.create(
            koreaTicker = command.koreaTicker,
            foreignTicker = command.foreignTicker,
            fxTicker = command.fxTicker,
        )
        return premiumRepository.save(premium)
    }

    @Transactional
    fun save(premium: Premium): Premium {
        return premiumRepository.save(premium)
    }

    @Transactional(readOnly = true)
    fun findLatestBySymbol(symbol: Symbol): Premium? {
        return premiumRepository.findLatestBySymbol(symbol)
    }

    @Transactional(readOnly = true)
    fun findLatestSnapshotBySymbol(symbol: Symbol): PremiumSnapshot? {
        return premiumRepository.findLatestSnapshotBySymbol(symbol)
    }

    @Transactional(readOnly = true)
    fun findAllBySymbolAndPeriod(symbol: Symbol, from: Instant, to: Instant): List<Premium> {
        return premiumRepository.findAllBySymbolAndPeriod(symbol, from, to)
    }

    @Transactional(readOnly = true)
    fun findAggregation(symbol: Symbol, interval: String, from: Instant, to: Instant): List<PremiumAggregationSnapshot> {
        val maxRange = MAX_RANGE[interval]
            ?: throw IllegalArgumentException("Invalid interval: $interval. Allowed: 1m, 1h, 1d")

        val clampedFrom = maxOf(from, to.minus(maxRange))

        val truncatedFrom = when (interval) {
            "1m" -> clampedFrom.truncatedTo(ChronoUnit.MINUTES)
            "1h" -> clampedFrom.truncatedTo(ChronoUnit.HOURS)
            "1d" -> clampedFrom.truncatedTo(ChronoUnit.DAYS)
            else -> clampedFrom
        }
        val truncatedTo = when (interval) {
            "1m" -> to.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
            "1h" -> to.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
            "1d" -> to.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
            else -> to
        }

        return premiumRepository.findAggregation(symbol, interval, truncatedFrom, truncatedTo)
    }
}
