package io.premiumspread.interfaces.scheduling

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.support.CronExpression
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
    @field:Valid val tradePreparationEvaluation: FixedRate = FixedRate(Duration.ofSeconds(1)),
    /**
     * reconcile 은 평가와 달리 초 단위일 이유가 없다. 대조 대상은 계정 잔고 스냅샷이라 프리미엄
     * 관측값처럼 초마다 바뀌지 않고, 이 단위의 종점인 `ARMED` 는 주문을 제출하지 않는다 —
     * 불일치 발견이 1분 늦는 것의 대가가 없다. 대신 활성 계획 전체를 훑으므로 주기를 짧게 잡으면
     * 값싸지 않은 조회가 초마다 돈다.
     */
    @field:Valid val tradePreparationReconcile: FixedRate = FixedRate(Duration.ofMinutes(1)),
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
            validateCron(minuteCron, "premiumAggregation.minuteCron")
            validateCron(hourCron, "premiumAggregation.hourCron")
            validateCron(dayCron, "premiumAggregation.dayCron")
        }
    }

    data class TickerAggregation(
        @field:NotBlank val minuteCron: String = "2 * * * * *",
        @field:NotBlank val hourCron: String = "7 0 * * * *",
        @field:NotBlank val dayCron: String = "12 0 0 * * *",
    ) {
        init {
            validateCron(minuteCron, "tickerAggregation.minuteCron")
            validateCron(hourCron, "tickerAggregation.hourCron")
            validateCron(dayCron, "tickerAggregation.dayCron")
        }
    }

    companion object {
        private fun validateCron(expression: String, property: String) {
            require(runCatching { CronExpression.parse(expression) }.isSuccess) { "$property must be a valid cron expression." }
        }
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
