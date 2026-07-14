package io.premiumspread.infrastructure.premium

import io.premiumspread.domain.premium.Premium
import io.premiumspread.domain.premium.PremiumAggregationSnapshot
import io.premiumspread.domain.premium.PremiumRepository
import io.premiumspread.domain.premium.PremiumSnapshot
import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.ticker.Symbol
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PremiumRepositoryImpl(
    private val premiumJpaRepository: PremiumJpaRepository,
    private val premiumCacheReader: PremiumCacheReader,
    private val premiumAggregationCacheReader: PremiumAggregationCacheReader,
    private val premiumAggregationQueryRepository: PremiumAggregationQueryRepository,
) : PremiumRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(premium: Premium): Premium {
        return premiumJpaRepository.save(premium)
    }

    override fun findById(id: Long): Premium? {
        return premiumJpaRepository.findByIdOrNull(id)
    }

    override fun findLatestByPair(pair: MarketPair): Premium? = premiumJpaRepository.findLatestByPair(
        symbol = pair.symbol.code,
        koreaExchange = pair.koreaExchange,
        foreignExchange = pair.foreignExchange,
    )

    override fun findLatestSnapshotByPair(pair: MarketPair): PremiumSnapshot? {
        val symbol = pair.symbol
        val cached = premiumCacheReader.get(symbol.code)
        if (cached != null && pair == cached.pair) {
            log.debug("Premium snapshot cache hit: {}", symbol.code)
            return PremiumSnapshot(
                pair = cached.pair,
                premiumRate = cached.premiumRate,
                koreaPrice = cached.koreaPrice,
                foreignPrice = cached.foreignPrice,
                foreignPriceInKrw = cached.foreignPriceInKrw,
                fxRate = cached.fxRate,
                observedAt = cached.observedAt,
                fxSource = cached.fxSource,
                fxObservedAt = cached.fxObservedAt,
            )
        }

        log.debug("Premium snapshot cache miss, falling back to pair DB JOIN query: {}", pair)
        return premiumJpaRepository.findLatestSnapshotByPair(
            symbol = symbol.code,
            koreaExchange = pair.koreaExchange,
            foreignExchange = pair.foreignExchange,
        )?.toDomain()
    }

    override fun findAllByPair(pair: MarketPair, from: Instant, to: Instant): List<Premium> =
        premiumJpaRepository.findAllByPairAndPeriod(
            symbol = pair.symbol.code,
            koreaExchange = pair.koreaExchange,
            foreignExchange = pair.foreignExchange,
            from = from,
            to = to,
        )

    override fun findAggregationByPair(
        pair: MarketPair,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<PremiumAggregationSnapshot> {
        if (pair != MarketPair.default(pair.symbol)) return emptyList()
        val symbol = pair.symbol
        val cached = premiumAggregationCacheReader.findByInterval(symbol.code, interval, from, to)
        val persisted = premiumAggregationQueryRepository.findByInterval(symbol.code, interval, from, to)
        if (cached == null) {
            log.debug("Aggregation cache miss, using DB: {} {}", symbol.code, interval)
            return persisted
        }

        // 현재 Redis payload에는 범위가 완전히 적재됐음을 증명하는 coverage marker가 없다.
        // eviction/rebuild 중의 부분 cache가 DB의 과거 bucket을 가리지 않도록 병합하되,
        // 같은 시각의 bucket은 영속 데이터(DB)를 정본으로 삼는다.
        log.debug(
            "Aggregation cache/DB merge: {} {} (cache={}, DB={})",
            symbol.code,
            interval,
            cached.size,
            persisted.size,
        )
        return (cached + persisted)
            .associateBy(PremiumAggregationSnapshot::observedAt)
            .values
            .sortedBy(PremiumAggregationSnapshot::observedAt)
    }
}
