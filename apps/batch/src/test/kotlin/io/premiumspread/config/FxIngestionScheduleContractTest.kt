package io.premiumspread.config

import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.time.Duration

/** FX 수집 주기는 허용 공백(30분)의 절반 이하여야 한다 (dod AC3). */
class FxIngestionScheduleContractTest {
    @Test
    fun `기본 수집 주기는 5분 시작 지연은 5초`() {
        val default = BatchSchedulingProperties.ExchangeRate()

        assertThat(default.fixedRate).isEqualTo(Duration.ofMinutes(5))
        assertThat(default.startupDelay).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun `수집 주기가 허용 공백의 절반이면 생성된다`() {
        val rate = BatchSchedulingProperties.ExchangeRate(fixedRate = Duration.ofMinutes(15))

        assertThat(rate.fixedRate).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `수집 주기가 허용 공백의 절반을 1밀리초라도 넘으면 기동을 거부한다`() {
        assertThatThrownBy {
            BatchSchedulingProperties.ExchangeRate(fixedRate = Duration.ofMinutes(15).plusMillis(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("exchangeRate.fixedRate")
    }

    @Test
    fun `application yml 은 5분 주기 재시도 1회 Twelve Data 를 선언한다`() {
        val yaml = ClassPathResource("application.yml").inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }

        assertThat(yaml)
            .contains("fixed-rate: \${EXCHANGE_RATE_FIXED_RATE:5m}")
            .contains("max-retries: \${EXCHANGE_RATE_MAX_RETRIES:1}")
            .contains("api.twelvedata.com")
            .doesNotContain("exchangerate-api.com")
    }
}
