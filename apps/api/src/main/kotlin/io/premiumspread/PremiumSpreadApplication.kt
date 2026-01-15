package io.premiumspread

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PremiumSpreadApplication

fun main(args: Array<String>) {
    runApplication<PremiumSpreadApplication>(*args)
}
