package io.premiumspread.application.common

sealed class JobResult {
    data object Success : JobResult()
    data class Skipped(val reason: String) : JobResult()
    data class Failure(val exception: Exception) : JobResult()
}
