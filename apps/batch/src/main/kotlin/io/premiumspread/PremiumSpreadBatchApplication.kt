package io.premiumspread

import io.premiumspread.config.AggregationProperties
import io.premiumspread.config.BatchJobProperties
import io.premiumspread.interfaces.scheduling.BatchSchedulingProperties
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootApplication(
    exclude = [MailSenderAutoConfiguration::class],
)
@EnableConfigurationProperties(
    AggregationProperties::class,
    BatchSchedulingProperties::class,
    BatchJobProperties::class,
)
@ComponentScan(
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.CUSTOM,
            classes = [TypeExcludeFilter::class],
        ),
        ComponentScan.Filter(
            type = FilterType.CUSTOM,
            classes = [AutoConfigurationExcludeFilter::class],
        ),
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["io\\.premiumspread\\.domain\\..*Service"],
        ),
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = [
                "io\\.premiumspread\\.infrastructure\\..*",
                "io\\.premiumspread\\.config\\.jpa\\..*",
                "io\\.premiumspread\\.redis\\..*",
            ],
        ),
    ],
)
class PremiumSpreadBatchApplication

fun main(args: Array<String>) {
    runApplication<PremiumSpreadBatchApplication>(*args)
}
