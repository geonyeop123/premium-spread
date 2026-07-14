package io.premiumspread.config.jpa

import io.premiumspread.domain.common.time.ClockDateTimeProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class JpaAuditingConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clock(): Clock = Clock.systemUTC()

    @Bean("auditingDateTimeProvider")
    @ConditionalOnMissingBean(name = ["auditingDateTimeProvider"])
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider = ClockDateTimeProvider(clock)
}
