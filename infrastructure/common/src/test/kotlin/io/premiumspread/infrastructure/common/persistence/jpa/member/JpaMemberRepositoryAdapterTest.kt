package io.premiumspread.infrastructure.common.persistence.jpa.member

import io.premiumspread.domain.member.DuplicateEmailException
import io.premiumspread.domain.member.Member
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

class JpaMemberRepositoryAdapterTest {

    private val springDataRepository = Mockito.mock(SpringDataMemberRepository::class.java)
    private val adapter = JpaMemberRepositoryAdapter(springDataRepository)
    private val member = Member.create("member@example.com", "encoded")

    @Test
    fun `알려진 email unique constraint 위반은 Domain conflict로 변환한다`() {
        Mockito.`when`(springDataRepository.saveAndFlush(member)).thenThrow(
            DataIntegrityViolationException("Duplicate entry for key 'uk_member_email'"),
        )

        assertThatThrownBy { adapter.save(member) }
            .isInstanceOf(DuplicateEmailException::class.java)
    }

    @Test
    fun `알 수 없는 DB 무결성 오류는 Domain conflict로 오인하지 않고 그대로 전파한다`() {
        val unknown = DataIntegrityViolationException("foreign key constraint fails")
        Mockito.`when`(springDataRepository.saveAndFlush(member)).thenThrow(unknown)

        assertThatThrownBy { adapter.save(member) }
            .isSameAs(unknown)
    }
}
