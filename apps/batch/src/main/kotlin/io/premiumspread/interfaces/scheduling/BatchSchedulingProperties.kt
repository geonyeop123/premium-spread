package io.premiumspread.interfaces.scheduling

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/** 모든 scheduler trigger 값을 한 곳에서 검증하는 typed configuration contract. */
@Validated
@ConfigurationProperties(prefix = "batch.scheduling")
data class BatchSchedulingProperties(
    val enabled: Boolean = true,
    @field:Valid val binanceFlush: FixedRate = FixedRate(Duration.ofSeconds(1)),
    @field:Valid val bithumbFlush: FixedRate = FixedRate(Duration.ofSeconds(1)),
    @field:Valid val exchangeRate: ExchangeRate = ExchangeRate(),
    @field:Valid val premium: FixedRate = FixedRate(Duration.ofSeconds(1)),
    @field:Valid val premiumAggregation: PremiumAggregation = PremiumAggregation(),
    @field:Valid val tickerAggregation: TickerAggregation = TickerAggregation(),
    @field:NotBlank val zone: String = "Asia/Seoul",
) {
    data class FixedRate(val fixedRate: Duration) {
        init {
            require(fixedRate.isPositive()) { "fixedRate must be positive." }
        }
    }

    data class ExchangeRate(
        val fixedRate: Duration = Duration.ofMinutes(30),
        val startupDelay: Duration = Duration.ofSeconds(5),
    ) {
        init {
            require(fixedRate.isPositive()) { "exchangeRate.fixedRate must be positive." }
            require(!startupDelay.isNegative) { "exchangeRate.startupDelay must not be negative." }
        }
    }

    data class PremiumAggregation(
        val summaryFixedRate: Duration = Duration.ofSeconds(10),
        @field:NotBlank val minuteCron: String = "0 * * * * *",
        @field:NotBlank val hourCron: String = "5 0 * * * *",
        @field:NotBlank val dayCron: String = "10 0 0 * * *",
    ) {
        init {
            require(summaryFixedRate.isPositive()) { "premiumAggregation.summaryFixedRate must be positive." }
        }
    }

    data class TickerAggregation(
        @field:NotBlank val minuteCron: String = "2 * * * * *",
        @field:NotBlank val hourCron: String = "7 0 * * * *",
        @field:NotBlank val dayCron: String = "12 0 0 * * *",
    )
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
