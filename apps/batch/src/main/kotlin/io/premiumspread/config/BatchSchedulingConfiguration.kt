package io.premiumspread.config

import io.premiumspread.interfaces.scheduling.ConditionalOnBatchScheduling
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBatchScheduling
class BatchSchedulingConfiguration
