package io.premiumspread.infrastructure.common.persistence.jdbc.notification

import io.premiumspread.domain.market.MarketPair
import io.premiumspread.domain.notification.ActiveNotificationSubscription
import io.premiumspread.domain.notification.ActiveNotificationSubscriptionPort
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Symbol
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

typealias ActiveSubscriptionView = ActiveNotificationSubscription
typealias ThresholdDirectionView = ThresholdDirection

@Repository
class ActiveSubscriptionReadRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ActiveNotificationSubscriptionPort {

    override fun findActiveByPair(pair: MarketPair): List<ActiveNotificationSubscription> =
        jdbcTemplate.query(
            """
            SELECT ns.id, ns.member_id, m.email, m.nickname,
                   ns.symbol, ns.korea_exchange, ns.foreign_exchange,
                   ns.revision, ns.direction, ns.threshold
            FROM notification_subscription ns
            INNER JOIN member m ON m.id = ns.member_id
            WHERE ns.status = 'ACTIVE'
              AND ns.deleted_at IS NULL
              AND m.deleted_at IS NULL
              AND ns.symbol = ?
              AND ns.korea_exchange = ?
              AND ns.foreign_exchange = ?
            """.trimIndent(),
            { rs, _ ->
                ActiveNotificationSubscription(
                    id = rs.getLong("id"),
                    memberId = rs.getLong("member_id"),
                    memberEmail = rs.getString("email"),
                    memberNickname = rs.getString("nickname"),
                    pair = MarketPair(
                        symbol = Symbol(rs.getString("symbol")),
                        koreaExchange = Exchange.valueOf(rs.getString("korea_exchange")),
                        foreignExchange = Exchange.valueOf(rs.getString("foreign_exchange")),
                    ),
                    revision = rs.getLong("revision"),
                    direction = ThresholdDirection.valueOf(rs.getString("direction")),
                    threshold = rs.getBigDecimal("threshold"),
                )
            },
            pair.symbol.code,
            pair.koreaExchange.name,
            pair.foreignExchange.name,
        )

    /** Phase 7 전환 중인 legacy evaluator의 default pair 호환 경로다. */
    fun findActiveBySymbol(symbol: String): List<ActiveSubscriptionView> =
        findActiveByPair(MarketPair.default(Symbol(symbol)))
}
