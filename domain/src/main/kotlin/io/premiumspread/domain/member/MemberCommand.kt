package io.premiumspread.domain.member

class MemberCommand private constructor() {
    data class Register(val email: String, val rawPassword: String)
}
