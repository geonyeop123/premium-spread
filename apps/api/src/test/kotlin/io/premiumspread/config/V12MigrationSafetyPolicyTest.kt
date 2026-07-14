package io.premiumspread.config

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class V12MigrationSafetyPolicyTest {

    private val policy = V12MigrationSafetyPolicy()

    @Test
    fun `V12가 아니면 position row와 무관하게 통과한다`() {
        assertThatCode { policy.validate("11", 10, approvedForEmpty = false) }.doesNotThrowAnyException()
    }

    @Test
    fun `V12 pending이고 position 데이터가 있으면 승인 여부와 무관하게 차단한다`() {
        assertThatThrownBy { policy.validate("12", 1, approvedForEmpty = true) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PENDING_WITH_DATA")
    }

    @Test
    fun `V12 pending이고 position이 비어도 일회성 승인이 없으면 차단한다`() {
        assertThatThrownBy { policy.validate("12", 0, approvedForEmpty = false) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PENDING_EMPTY")
    }

    @Test
    fun `V12 pending이고 position이 비었으며 일회성 승인이 있으면 통과한다`() {
        assertThatCode { policy.validate("12", 0, approvedForEmpty = true) }.doesNotThrowAnyException()
    }

    @Test
    fun `Flyway callback은 V12 실행 직전에만 동작한다`() {
        val context = migrationContext("12")
        val callback = V12MigrationSafetyCallback(V12MigrationSafetyProperties(true))

        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, context)).isTrue()
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, null)).isTrue()
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context)).isFalse()
    }

    @Test
    fun `Flyway callback은 V12 직전에 실제 position row를 검사한다`() {
        val resultSet = mockk<ResultSet>()
        every { resultSet.next() } returns true
        every { resultSet.getLong(1) } returns 3L
        every { resultSet.close() } returns Unit
        val statement = mockk<Statement>()
        every { statement.executeQuery("SELECT COUNT(*) FROM `position`") } returns resultSet
        every { statement.close() } returns Unit
        val connection = mockk<Connection>()
        every { connection.createStatement() } returns statement
        val context = migrationContext("12", connection)
        val callback = V12MigrationSafetyCallback(V12MigrationSafetyProperties(true))

        assertThatThrownBy { callback.handle(Event.BEFORE_EACH_MIGRATE, context) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PENDING_WITH_DATA")
    }

    private fun migrationContext(version: String, connection: Connection = mockk()): Context {
        val migrationInfo = mockk<MigrationInfo>()
        every { migrationInfo.version } returns MigrationVersion.fromVersion(version)
        val context = mockk<Context>()
        every { context.migrationInfo } returns migrationInfo
        every { context.connection } returns connection
        return context
    }
}
