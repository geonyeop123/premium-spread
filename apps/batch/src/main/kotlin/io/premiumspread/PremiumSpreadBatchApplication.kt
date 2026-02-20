package io.premiumspread

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
class PremiumSpreadBatchApplication

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig

fun main(args: Array<String>) {
    runApplication<PremiumSpreadBatchApplication>(*args)
}
