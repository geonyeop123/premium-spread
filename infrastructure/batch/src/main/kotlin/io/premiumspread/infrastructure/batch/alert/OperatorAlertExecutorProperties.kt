package io.premiumspread.infrastructure.batch.alert

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "batch.alert.executor")
data class OperatorAlertExecutorProperties(
    @field:Min(1)
    @field:Max(32)
    val threads: Int = 2,
    @field:Min(1)
    @field:Max(10_000)
    val queueCapacity: Int = 100,
    val keepAlive: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!keepAlive.isZero && !keepAlive.isNegative) { "batch.alert.executor.keep-alive must be positive" }
    }
}
