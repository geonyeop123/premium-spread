package io.premiumspread.domain.member

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.premiumspread.withId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MemberServiceTest {

    private lateinit var memberRepository: MemberRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var service: MemberService

    @BeforeEach
    fun setUp() {
        memberRepository = mockk()
        passwordEncoder = mockk()
        service = MemberService(memberRepository, passwordEncoder)
    }

    @Nested
    inner class Register {

        @Test
        fun `회원가입에 성공한다`() {
            val command = MemberCommand.Register(
                email = "test@example.com",
                rawPassword = "password123",
            )

            every { memberRepository.existsByEmail("test@example.com") } returns false
            every { passwordEncoder.encode("password123") } returns "encoded_password"

            val memberSlot = slot<Member>()
            every { memberRepository.save(capture(memberSlot)) } answers {
                memberSlot.captured.withId(1L)
            }

            val result = service.register(command)

            assertThat(result.id).isEqualTo(1L)
            assertThat(result.email).isEqualTo("test@example.com")
            assertThat(result.nickname).isEqualTo("test")
            assertThat(result.status).isEqualTo(MemberStatus.ACTIVE)
            verify(exactly = 1) { memberRepository.save(any()) }
        }

        @Test
        fun `중복 이메일로 가입하면 예외가 발생한다`() {
            val command = MemberCommand.Register(
                email = "test@example.com",
                rawPassword = "password123",
            )

            every { memberRepository.existsByEmail("test@example.com") } returns true

            assertThatThrownBy { service.register(command) }
                .isInstanceOf(DuplicateEmailException::class.java)
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `ID로 회원을 조회한다`() {
            val member = Member.create(
                email = "test@example.com",
                encodedPassword = "encoded_password",
            ).withId(1L)

            every { memberRepository.findById(1L) } returns member

            val result = service.findById(1L)

            assertThat(result).isNotNull
            assertThat(result!!.email).isEqualTo("test@example.com")
        }

        @Test
        fun `회원이 없으면 null을 반환한다`() {
            every { memberRepository.findById(999L) } returns null

            val result = service.findById(999L)

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class Authenticate {

        @Test
        fun `이메일과 비밀번호가 일치하면 회원을 반환한다`() {
            val member = Member.create(
                email = "member@example.com",
                encodedPassword = "encoded_password",
            ).withId(7L)
            every { memberRepository.findByEmail("member@example.com") } returns member
            every { passwordEncoder.matches("password123", "encoded_password") } returns true

            val result = service.authenticate("member@example.com", "password123")

            assertThat(result).isEqualTo(member)
        }

        @Test
        fun `회원이 없거나 비밀번호가 다르면 null을 반환한다`() {
            every { memberRepository.findByEmail("missing@example.com") } returns null
            every { memberRepository.findByEmail("member@example.com") } returns Member.create(
                email = "member@example.com",
                encodedPassword = "encoded_password",
            ).withId(7L)
            every { passwordEncoder.matches("wrong-password", "encoded_password") } returns false

            assertThat(service.authenticate("missing@example.com", "password123")).isNull()
            assertThat(service.authenticate("member@example.com", "wrong-password")).isNull()
        }
    }
}
