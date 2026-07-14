package io.premiumspread.interfaces.api.member

import io.premiumspread.application.member.MemberCriteria
import io.premiumspread.application.member.MemberFacade
import io.premiumspread.interfaces.api.auth.LoginMemberId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/members")
class MemberController(
    private val memberFacade: MemberFacade,
) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: MemberRequest.Register): ResponseEntity<MemberResponse.Detail> {
        val criteria = MemberCriteria.Register(
            email = request.email,
            password = request.password,
        )
        val result = memberFacade.register(criteria)
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.Detail.from(result))
    }

    @GetMapping("/me")
    fun me(@LoginMemberId memberId: Long): ResponseEntity<MemberResponse.Detail> {
        val result = memberFacade.findMe(MemberCriteria.FindMe(memberId))
        return ResponseEntity.ok(MemberResponse.Detail.from(result))
    }
}
