package io.premiumspread.domain.common

import io.premiumspread.domain.BaseEntity
import io.premiumspread.domain.common.time.ClockDateTimeProvider
import io.premiumspread.domain.member.Member
import io.premiumspread.withId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BaseEntityTest {
    @Test
    fun `삭제 시각은 호출자가 전달하고 삭제와 복원은 멱등이다`() {
        val entity = Member.create("member@example.com", "encoded")
        val deletedAt = Instant.parse("2026-07-14T01:02:03Z")

        entity.delete(deletedAt)
        entity.delete(deletedAt.plusSeconds(1))

        assertThat(entity.deletedAt).isEqualTo(deletedAt)
        entity.restore()
        entity.restore()
        assertThat(entity.deletedAt).isNull()
    }

    @Test
    fun `영속화 전 객체는 자신만 같고 영속 identity는 id로 비교한다`() {
        val first = Member.create("first@example.com", "encoded")
        val second = Member.create("first@example.com", "encoded")

        assertThat(first).isNotEqualTo(second)
        first.withId(7L)
        second.withId(7L)
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    @Test
    fun `프록시 하위 타입과 원본 엔티티는 같은 영속 identity로 비교한다`() {
        val entity = TestEntity().withId(11L)
        val proxy = TestEntityProxy().withId(11L)

        assertThat(entity).isEqualTo(proxy)
        assertThat(proxy).isEqualTo(entity)
        assertThat(entity.hashCode()).isEqualTo(proxy.hashCode())
    }

    @Test
    fun `영속화 전후 hashCode가 안정적이라 HashSet membership을 유지한다`() {
        val entity = TestEntity()
        val entities = hashSetOf(entity)
        val transientHash = entity.hashCode()

        entity.withId(12L)

        assertThat(entity.hashCode()).isEqualTo(transientHash)
        assertThat(entities).contains(entity)
    }

    @Test
    fun `audit 필드는 Instant 계약이다`() {
        assertThat(BaseEntity::class.java.getDeclaredField("createdAt").type).isEqualTo(Instant::class.java)
        assertThat(BaseEntity::class.java.getDeclaredField("updatedAt").type).isEqualTo(Instant::class.java)
        assertThat(BaseEntity::class.java.getDeclaredField("deletedAt").type).isEqualTo(Instant::class.java)
    }

    @Test
    fun `DateTimeProvider는 주입된 Clock의 시각을 반환한다`() {
        val now = Instant.parse("2026-07-14T01:02:03.123456Z")
        val provider = ClockDateTimeProvider(Clock.fixed(now, ZoneOffset.UTC))

        assertThat(provider.now).contains(now)
    }

    open class TestEntity : BaseEntity()

    class TestEntityProxy : TestEntity()
}
