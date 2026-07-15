package io.premiumspread.domain.member

import io.premiumspread.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "member")
class Member private constructor(
    @Column(nullable = false, unique = true)
    val email: String,
    @Column(nullable = false)
    val password: String,
    @Column(nullable = false, length = 50)
    val nickname: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: MemberStatus,
) : BaseEntity() {

    companion object {
        fun create(
            email: String,
            encodedPassword: String,
        ): Member = Member(
                email = email,
                password = encodedPassword,
                nickname = email.substringBefore("@"),
                status = MemberStatus.ACTIVE,
            )
    }
}
