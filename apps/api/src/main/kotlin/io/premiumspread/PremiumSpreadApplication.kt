package io.premiumspread

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import io.premiumspread.config.AggregationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootApplication(excludeName = ["org.redisson.spring.starter.RedissonAutoConfigurationV2"])
@EnableConfigurationProperties(AggregationProperties::class)
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
            pattern = [
                "io\\.premiumspread\\.infrastructure\\..*",
                "io\\.premiumspread\\.config\\.jpa\\..*",
                "io\\.premiumspread\\.redis\\..*",
            ],
        ),
    ],
)
class PremiumSpreadApplication

fun main(args: Array<String>) {
    runApplication<PremiumSpreadApplication>(*args)
}
