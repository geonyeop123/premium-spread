package io.premiumspread.infrastructure.common.cache

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * DB transaction과 함께 cache를 갱신하는 경로에서 미커밋 값을 노출하지 않도록 한다.
 * Transaction 밖의 순수 ingestion cache write는 즉시 실행한다.
 */
@Component
class AfterCommitCacheExecutor {
    fun execute(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}
