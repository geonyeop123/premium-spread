package io.premiumspread.infrastructure.common.persistence.jdbc.notification

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal

enum class ThresholdDirectionView { ABOVE, BELOW }

data class ActiveSubscriptionView(
    val id: Long,
    val memberId: Long,
    val memberEmail: String,
    val memberNickname: String,
    val symbol: String,
    val direction: ThresholdDirectionView,
    val threshold: BigDecimal,
) {
    fun matches(rate: BigDecimal): Boolean = when (direction) {
        ThresholdDirectionView.ABOVE -> rate >= threshold
        ThresholdDirectionView.BELOW -> rate <= threshold
    }
}

@Repository
class ActiveSubscriptionReadRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findActiveBySymbol(symbol: String): List<ActiveSubscriptionView> {
        return jdbcTemplate.query(
            """
            SELECT ns.id, ns.member_id, m.email, m.nickname,
                   ns.symbol, ns.direction, ns.threshold
            FROM notification_subscription ns
            INNER JOIN member m ON m.id = ns.member_id
            WHERE ns.status = 'ACTIVE'
              AND ns.deleted_at IS NULL
              AND m.deleted_at IS NULL
              AND ns.symbol = ?
            """.trimIndent(),
            { rs, _ ->
                ActiveSubscriptionView(
                    id = rs.getLong("id"),
                    memberId = rs.getLong("member_id"),
                    memberEmail = rs.getString("email"),
                    memberNickname = rs.getString("nickname"),
                    symbol = rs.getString("symbol"),
                    direction = ThresholdDirectionView.valueOf(rs.getString("direction")),
                    threshold = rs.getBigDecimal("threshold"),
                )
            },
            symbol.uppercase(),
        )
    }
}
