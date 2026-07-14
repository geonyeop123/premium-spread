package io.premiumspread

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import io.premiumspread.config.AggregationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [MailSenderAutoConfiguration::class])
@EnableConfigurationProperties(AggregationProperties::class)
@ComponentScan(
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["io\\.premiumspread\\.domain\\..*Service"],
        ),
    ],
)
class PremiumSpreadBatchApplication

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig

fun main(args: Array<String>) {
    runApplication<PremiumSpreadBatchApplication>(*args)
}
