package io.premiumspread.infrastructure.api

import org.springframework.boot.autoconfigure.AutoConfiguration

@AutoConfiguration(after = [io.premiumspread.infrastructure.common.CommonInfrastructureAutoConfiguration::class])
class ApiInfrastructureAutoConfiguration
