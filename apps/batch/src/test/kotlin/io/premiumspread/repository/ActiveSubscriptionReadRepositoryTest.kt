package io.premiumspread.repository

import io.premiumspread.infrastructure.common.persistence.jdbc.notification.ActiveSubscriptionReadRepository
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ActiveSubscriptionReadRepositoryTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var sut: ActiveSubscriptionReadRepository

    @Test
    fun `활성 구독 + 회원 정보를 JOIN해서 반환한다`() {
        jdbcTemplate.update(
            "INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('u@x.com','p','user','ACTIVE', NOW(6), NOW(6))",
        )
        val memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at) VALUES (?, 'BTC', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6))",
            memberId,
        )

        val rows = sut.findActiveBySymbol("BTC")

        assertThat(rows).hasSize(1)
        assertThat(rows[0].memberEmail).isEqualTo("u@x.com")
        assertThat(rows[0].symbol).isEqualTo("BTC")
    }

    @Test
    fun `INACTIVE 구독은 반환되지 않는다`() {
        jdbcTemplate.update(
            "INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('y@x.com','p','user','ACTIVE', NOW(6), NOW(6))",
        )
        val memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at) VALUES (?, 'BTC', 'ABOVE', 5.0, 'INACTIVE', NOW(6), NOW(6))",
            memberId,
        )

        assertThat(sut.findActiveBySymbol("BTC")).noneMatch { it.memberEmail == "y@x.com" }
    }

    @Test
    fun `다른 symbol은 반환되지 않는다`() {
        jdbcTemplate.update(
            "INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('z@x.com','p','user','ACTIVE', NOW(6), NOW(6))",
        )
        val memberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at) VALUES (?, 'ETH', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6))",
            memberId,
        )

        assertThat(sut.findActiveBySymbol("BTC")).noneMatch { it.memberEmail == "z@x.com" }
    }

    @Test
    fun `soft delete된 구독과 회원은 반환되지 않는다`() {
        jdbcTemplate.update(
            "INSERT INTO member (email, password, nickname, status, created_at, updated_at) VALUES ('deleted-sub@x.com','p','user','ACTIVE', NOW(6), NOW(6))",
        )
        val deletedSubscriptionMemberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at, deleted_at) VALUES (?, 'BTC', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6), NOW(6))",
            deletedSubscriptionMemberId,
        )
        jdbcTemplate.update(
            "INSERT INTO member (email, password, nickname, status, created_at, updated_at, deleted_at) VALUES ('deleted-member@x.com','p','user','ACTIVE', NOW(6), NOW(6), NOW(6))",
        )
        val deletedMemberId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbcTemplate.update(
            "INSERT INTO notification_subscription (member_id, symbol, direction, threshold, status, created_at, updated_at) VALUES (?, 'BTC', 'ABOVE', 5.0, 'ACTIVE', NOW(6), NOW(6))",
            deletedMemberId,
        )

        val rows = sut.findActiveBySymbol("BTC")

        assertThat(rows).noneMatch { it.memberEmail in setOf("deleted-sub@x.com", "deleted-member@x.com") }
    }
}
