package io.premiumspread.repository

import io.premiumspread.support.BatchIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant

@DisplayName("ExchangeRateRepository")
class ExchangeRateRepositoryTest : BatchIntegrationTestBase() {

    @Autowired
    lateinit var repository: ExchangeRateRepository

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
            val result = repository.findLatest("USD", "KRW")
            assertThat(result).isNotNull
            assertThat(result!!.baseCurrency).isEqualTo("USD")
            assertThat(result.quoteCurrency).isEqualTo("KRW")
            assertThat(result.rate).isEqualByComparingTo("1432.60")
            assertThat(result.observedAt).isEqualTo(fixedObservedAt)
        }

        @Test
        fun `동일 통화쌍과 관측시각에 재저장 시 rate가 갱신된다`() {
            // given
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)

            // when - 같은 observed_at → ON DUPLICATE KEY UPDATE
            repository.save("USD", "KRW", BigDecimal("1450.00"), fixedObservedAt)

            // then - 갱신된 rate 반환
            val result = repository.findLatest("USD", "KRW")
            assertThat(result!!.rate).isEqualByComparingTo("1450.00")
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

    @Nested
    @DisplayName("findLatest")
    inner class FindLatest {

        @Test
        fun `데이터가 없으면 null을 반환한다`() {
            assertThat(repository.findLatest("USD", "KRW")).isNull()
        }

        @Test
        fun `여러 레코드 중 observed_at이 가장 늦은 것을 반환한다`() {
            // given
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)
            repository.save("USD", "KRW", BigDecimal("1450.00"), laterObservedAt)

            // when
            val result = repository.findLatest("USD", "KRW")

            // then
            assertThat(result!!.rate).isEqualByComparingTo("1450.00")
        }

        @Test
        fun `다른 통화쌍은 반환하지 않는다`() {
            // given
            repository.save("USD", "KRW", BigDecimal("1432.60"), fixedObservedAt)

            // when
            val result = repository.findLatest("EUR", "KRW")

            // then
            assertThat(result).isNull()
        }
    }
}
