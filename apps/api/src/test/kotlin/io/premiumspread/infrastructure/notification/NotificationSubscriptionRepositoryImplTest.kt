package io.premiumspread.infrastructure.notification

import io.premiumspread.config.TestConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.domain.notification.NotificationSubscription
import io.premiumspread.domain.notification.SubscriptionStatus
import io.premiumspread.domain.notification.ThresholdDirection
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class NotificationSubscriptionRepositoryImplTest @Autowired constructor(
    private val sut: NotificationSubscriptionRepositoryImpl,
    private val memberRepository: MemberRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `저장 후 ID로 조회 가능`() {
        val member = memberRepository.save(Member.create(email = "a@a.com", encodedPassword = "x"))
        val saved = sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))

        val found = sut.findById(saved.id)

        assertThat(found).isNotNull
        assertThat(found?.symbol).isEqualTo("BTC")
    }

    @Test
    fun `findAllByMemberId는 멤버의 활성 구독을 반환한다`() {
        val member = memberRepository.save(Member.create(email = "b@b.com", encodedPassword = "x"))
        sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))
        sut.save(NotificationSubscription.create(member.id, "ETH", ThresholdDirection.BELOW, BigDecimal("-1.00")))

        val list = sut.findAllByMemberId(member.id)

        assertThat(list).hasSize(2)
    }

    @Test
    fun `delete는 soft delete로 처리되어 조회되지 않는다`() {
        val member = memberRepository.save(Member.create(email = "c@c.com", encodedPassword = "x"))
        val sub = sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))

        sut.delete(sub)

        assertThat(sut.findById(sub.id)).isNull()
        assertThat(sut.findAllByMemberId(member.id)).isEmpty()
    }

    @Test
    fun `change 후 save는 새 row를 만들지 않는다 (UPDATE)`() {
        val member = memberRepository.save(Member.create(email = "d@d.com", encodedPassword = "x"))
        val sub = sut.save(NotificationSubscription.create(member.id, "BTC", ThresholdDirection.ABOVE, BigDecimal("5.00")))
        val originalId = sub.id

        sub.changeStatus(SubscriptionStatus.INACTIVE)
        sut.save(sub)

        assertThat(sut.findById(originalId)).isNotNull
        val all = sut.findAllByMemberId(member.id)
        assertThat(all).hasSizeLessThanOrEqualTo(1)
    }
}
