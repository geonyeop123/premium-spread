package io.premiumspread.interfaces.scheduling

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnExpression("\${batch.scheduling.enabled:true} and \${scheduling.enabled:true}")
annotation class ConditionalOnBatchScheduling
