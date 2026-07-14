package io.premiumspread.infrastructure.common.cache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

class AfterCommitCacheExecutorTest {
    private val executor = AfterCommitCacheExecutor()

    @AfterEach
    fun cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `transaction 밖에서는 즉시 실행한다`() {
        var writes = 0

        executor.execute { writes++ }

        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `commit 전에는 쓰지 않고 afterCommit에서 실행한다`() {
        var writes = 0
        TransactionSynchronizationManager.initSynchronization()

        executor.execute { writes++ }
        assertThat(writes).isZero()

        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `rollback이면 cache action을 실행하지 않는다`() {
        var writes = 0
        TransactionSynchronizationManager.initSynchronization()

        executor.execute { writes++ }
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(1) }

        assertThat(writes).isZero()
    }
}
