package io.premiumspread.infrastructure.member

import io.premiumspread.config.TestConfig
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberRepository
import io.premiumspread.testcontainers.MySqlTestContainersConfig
import io.premiumspread.testcontainers.RedisTestContainersConfig
import io.premiumspread.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, TestConfig::class)
class MemberRepositoryTest @Autowired constructor(
    private val memberRepository: MemberRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
    }

    private fun createMember(
        email: String = "test@example.com",
        encodedPassword: String = "encoded_password",
    ): Member = memberRepository.save(
        Member.create(email = email, encodedPassword = encodedPassword),
    )

    @Nested
    @DisplayName("save")
    inner class Save {
        @Test
        fun `회원을 저장하고 ID가 부여된다`() {
            val member = Member.create(
                email = "test@example.com",
                encodedPassword = "encoded_password",
            )

            val saved = memberRepository.save(member)

            assertThat(saved.id).isGreaterThan(0)
            assertThat(saved.email).isEqualTo("test@example.com")
            assertThat(saved.nickname).isEqualTo("test")
        }
    }

    @Nested
    @DisplayName("findByEmail")
    inner class FindByEmail {
        @Test
        fun `이메일로 회원을 조회한다`() {
            val saved = createMember()

            val found = memberRepository.findByEmail("test@example.com")

            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(saved.id)
            assertThat(found.email).isEqualTo("test@example.com")
        }

        @Test
        fun `존재하지 않는 이메일이면 null을 반환한다`() {
            val found = memberRepository.findByEmail("unknown@example.com")

            assertThat(found).isNull()
        }

        @Test
        fun `삭제된 회원은 조회되지 않는다`() {
            val saved = createMember()
            saved.delete(Instant.parse("2026-07-14T03:00:00Z"))
            memberRepository.save(saved)

            val found = memberRepository.findByEmail("test@example.com")

            assertThat(found).isNull()
        }
    }

    @Nested
    @DisplayName("findById")
    inner class FindById {
        @Test
        fun `ID로 회원을 조회한다`() {
            val saved = createMember()

            val found = memberRepository.findById(saved.id)

            assertThat(found).isNotNull
            assertThat(found!!.email).isEqualTo("test@example.com")
        }

        @Test
        fun `존재하지 않는 ID이면 null을 반환한다`() {
            val found = memberRepository.findById(999L)

            assertThat(found).isNull()
        }

        @Test
        fun `soft-deleted 회원은 ID로도 조회되지 않는다`() {
            val saved = createMember()
            saved.delete(Instant.parse("2026-07-14T03:00:00Z"))
            memberRepository.save(saved)

            assertThat(memberRepository.findById(saved.id)).isNull()
        }
    }

    @Nested
    @DisplayName("existsByEmail")
    inner class ExistsByEmail {
        @Test
        fun `존재하는 이메일이면 true를 반환한다`() {
            createMember()

            val exists = memberRepository.existsByEmail("test@example.com")

            assertThat(exists).isTrue()
        }

        @Test
        fun `존재하지 않는 이메일이면 false를 반환한다`() {
            val exists = memberRepository.existsByEmail("unknown@example.com")

            assertThat(exists).isFalse()
        }

        @Test
        fun `삭제된 회원은 존재하지 않는 것으로 처리한다`() {
            val saved = createMember()
            saved.delete(Instant.parse("2026-07-14T03:00:00Z"))
            memberRepository.save(saved)

            val exists = memberRepository.existsByEmail("test@example.com")

            assertThat(exists).isFalse()
        }
    }
}
