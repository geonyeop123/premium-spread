package io.premiumspread.application.member

import io.premiumspread.application.common.ApplicationError
import io.premiumspread.application.common.ApplicationException
import io.premiumspread.domain.member.DuplicateEmailException
import io.premiumspread.domain.member.MemberCommand
import io.premiumspread.domain.member.Member
import io.premiumspread.domain.member.MemberService
import org.springframework.stereotype.Service

@Service
class MemberFacade(private val memberService: MemberService) {
    fun register(criteria: MemberCriteria.Register): MemberResult.Detail =
        translate {
            toDetail(
                memberService.register(
                    MemberCommand.Register(
                        email = criteria.email,
                        rawPassword = criteria.password,
                    ),
                ),
            )
        }

    fun findMe(criteria: MemberCriteria.FindMe): MemberResult.Detail {
        val member = memberService.findById(criteria.memberId)
            ?: throw ApplicationException(ApplicationError.MEMBER_NOT_FOUND)
        return toDetail(member)
    }

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: DuplicateEmailException) {
            throw ApplicationException(ApplicationError.DUPLICATE_EMAIL, ex)
        }

    private fun toDetail(member: Member): MemberResult.Detail = MemberResult.Detail(
        id = member.id,
        email = member.email,
        nickname = member.nickname,
        status = member.status.name,
    )
}
