package io.premiumspread

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PremiumSpreadApplication

fun main(args: Array<String>) {
    runApplication<PremiumSpreadApplication>(*args)
}
