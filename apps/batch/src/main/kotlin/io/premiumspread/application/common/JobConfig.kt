package io.premiumspread.application.common

import java.util.concurrent.TimeUnit

data class JobConfig(
    val jobName: String,
    val lockKey: String,
    val leaseTime: Long,
    val leaseTimeUnit: TimeUnit = TimeUnit.SECONDS,
)
