package io.premiumspread

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PremiumSpreadBatchApplication

fun main(args: Array<String>) {
    runApplication<PremiumSpreadBatchApplication>(*args)
}
