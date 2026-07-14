package io.premiumspread.infrastructure.batch

import org.springframework.boot.autoconfigure.AutoConfiguration

@AutoConfiguration(after = [io.premiumspread.infrastructure.common.CommonInfrastructureAutoConfiguration::class])
class BatchInfrastructureAutoConfiguration
