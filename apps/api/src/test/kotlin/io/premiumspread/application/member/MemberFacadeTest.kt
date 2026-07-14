package io.premiumspread.application.member

import io.mockk.every
import io.mockk.mockk
import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.member.DuplicateEmailException
import io.premiumspread.domain.member.MemberService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MemberFacadeTest {
    private val service = mockk<MemberService>()
    private val facade = MemberFacade(service)

    @Test
    fun `중복 이메일 도메인 오류를 안정된 Application 오류로 변환한다`() {
        every { service.register(any()) } throws DuplicateEmailException("secret@example.com")

        assertThatThrownBy { facade.register(MemberCriteria.Register("secret@example.com", "password123")) }
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.DUPLICATE_EMAIL)
    }

    @Test
    fun `존재하지 않는 내 정보는 MEMBER_NOT_FOUND를 반환한다`() {
        every { service.findById(1L) } returns null

        assertThatThrownBy { facade.findMe(MemberCriteria.FindMe(1L)) }
            .isInstanceOf(ApplicationException::class.java)
            .hasFieldOrPropertyWithValue("error", ApplicationError.MEMBER_NOT_FOUND)
    }
}
