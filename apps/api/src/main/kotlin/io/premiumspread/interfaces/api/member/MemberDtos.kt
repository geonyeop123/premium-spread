package io.premiumspread.interfaces.api.member

import io.premiumspread.application.member.MemberResult
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

class MemberRequest private constructor() {
    data class Register(
        @field:NotBlank
        @field:Email
        @field:Size(max = 254)
        val email: String,
        @field:NotBlank
        @field:Size(min = 8, max = 100)
        val password: String,
    )
}

class MemberResponse private constructor() {
    data class Detail(val id: Long, val email: String, val nickname: String, val status: String) {
        companion object {
            fun from(result: MemberResult.Detail): Detail = Detail(
                id = result.id,
                email = result.email,
                nickname = result.nickname,
                status = result.status,
            )
        }
    }
}
