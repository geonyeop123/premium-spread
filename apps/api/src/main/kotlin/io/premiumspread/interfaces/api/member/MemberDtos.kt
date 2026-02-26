package io.premiumspread.interfaces.api.member

import io.premiumspread.domain.member.Member

class MemberRequest private constructor() {
    data class Register(
        val email: String,
        val password: String,
    )
}

class MemberResponse private constructor() {
    data class Detail(
        val id: Long,
        val email: String,
        val nickname: String,
        val status: String,
    ) {
        companion object {
            fun from(member: Member): Detail = Detail(
                id = member.id,
                email = member.email,
                nickname = member.nickname,
                status = member.status.name,
            )
        }
    }
}
