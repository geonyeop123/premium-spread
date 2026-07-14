package io.premiumspread.infrastructure.batch.exchange

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "exchange-rate")
data class ExchangeRateClientProperties(
    val endpoint: URI = URI.create("https://v6.exchangerate-api.com"),
    @field:NotBlank
    val apiKey: String,
    @field:NotBlank
    val base: String = "USD",
    @field:NotBlank
    val quote: String = "KRW",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(3),
    @field:Min(0)
    @field:Max(10)
    val maxRetries: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(250),
    val maxBackoff: Duration = Duration.ofSeconds(2),
    val retryableStatuses: Set<Int> = setOf(429) + (500..599),
) {
    init {
        require(endpoint.scheme == "http" || endpoint.scheme == "https") { "exchange-rate.endpoint must use http or https" }
        require(endpoint.host != null) { "exchange-rate.endpoint must be absolute" }
        require(connectTimeout.isPositive()) { "exchange-rate.connect-timeout must be positive" }
        require(readTimeout.isPositive()) { "exchange-rate.read-timeout must be positive" }
        require(initialBackoff.isPositive()) { "exchange-rate.initial-backoff must be positive" }
        require(maxBackoff >= initialBackoff) { "exchange-rate.max-backoff must be >= initial-backoff" }
        require(retryableStatuses.all { it in 400..599 }) { "exchange-rate.retryable-statuses must contain HTTP error statuses" }
    }

    fun isRetryable(status: Int): Boolean = status in retryableStatuses

    fun overallDeadline(): Duration {
        val attempts = maxRetries.toLong() + 1
        val requestBudget = connectTimeout.plus(readTimeout).multipliedBy(attempts)
        val backoffBudget = maxBackoff.multipliedBy(maxRetries.toLong())
        return requestBudget.plus(backoffBudget).plusSeconds(1)
    }
}
