package io.premiumspread.repository

import io.premiumspread.infrastructure.common.persistence.jdbc.exchangerate.JdbcExchangeRateWriteRepository
import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.math.BigDecimal
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

@DisplayName("ExchangeRateRepository")
@Import(ExchangeRateRepositoryTest.FixedClockConfig::class)
class ExchangeRateRepositoryTest : BatchIntegrationTestBase() {

    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-05-12T00:00:10Z"), ZoneOffset.UTC)
    }

    @Autowired
    lateinit var repository: JdbcExchangeRateWriteRepository

    private val fixedObservedAt = Instant.ofEpochSecond(1_706_486_401L)
    private val laterObservedAt = Instant.ofEpochSecond(1_706_490_001L)

    @Nested
    @DisplayName("save")
    inner class Save {

        @Test
        fun `환율을 저장한다`() {
            // when
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)

            // then
            val row = requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT base_currency, quote_currency, rate, observed_at FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                ) { resultSet, _ ->
                    listOf(
                        resultSet.getString("base_currency"),
                        resultSet.getString("quote_currency"),
                        resultSet.getBigDecimal("rate"),
                        resultSet.getTimestamp("observed_at").toInstant(),
                    )
                },
            )
            assertThat(row[0]).isEqualTo("USD")
            assertThat(row[1]).isEqualTo("KRW")
            assertThat(row[2] as BigDecimal).isEqualByComparingTo("1432.60")
            assertThat(row[3]).isEqualTo(fixedObservedAt)
        }

        @Test
        fun `UTC 세션에서 observedAt과 Clock 기반 createdAt을 손실 없이 저장한다`() {
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)

            val observedEpoch = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(observed_at) FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                Long::class.java,
            )
            val createdEpoch = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(created_at) FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                Long::class.java,
            )

            assertThat(observedEpoch).isEqualTo(fixedObservedAt.epochSecond)
            assertThat(createdEpoch).isEqualTo(Instant.parse("2026-05-12T00:00:10Z").epochSecond)
        }

        @Test
        fun `동일 통화쌍과 관측시각에 재저장 시 rate가 갱신된다`() {
            // given
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)

            // when - 같은 observed_at → ON DUPLICATE KEY UPDATE
            repository.save("USD", "KRW", BigDecimal("1450.00"), fixedObservedAt)

            // then - 갱신된 rate 반환
            val rate = jdbcTemplate.queryForObject(
                "SELECT rate FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                BigDecimal::class.java,
            )
            assertThat(rate).isEqualByComparingTo("1450.00")
            val count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                Int::class.java,
            )
            assertThat(count).isEqualTo(1)
        }

        @Test
        fun `서로 다른 관측시각이면 각각 별도 레코드로 저장된다`() {
            // when
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)
            repository.save("USD", "KRW", BigDecimal("1450.00"), laterObservedAt)

            // then
            val count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
                Int::class.java,
            )
            assertThat(count).isEqualTo(2)
        }

        @Test
        fun `통화 코드는 대문자로 저장된다`() {
            // when
            repository.save("usd", "krw", BigDecimal("1432.60"), fixedObservedAt)

            // then
            val results = jdbcTemplate.queryForList(
                "SELECT * FROM exchange_rate WHERE base_currency = 'USD' AND quote_currency = 'KRW'",
            )
            assertThat(results).hasSize(1)
        }
    }

}
