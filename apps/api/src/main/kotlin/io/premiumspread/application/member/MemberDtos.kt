package io.premiumspread.application.member

class MemberCriteria private constructor() {
    data class Register(val email: String, val password: String)

    data class FindMe(val memberId: Long)
}

class MemberResult private constructor() {
    data class Detail(val id: Long, val email: String, val nickname: String, val status: String)
}
