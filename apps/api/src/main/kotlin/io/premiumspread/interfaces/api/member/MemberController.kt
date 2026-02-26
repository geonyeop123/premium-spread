package io.premiumspread.interfaces.api.member

import io.premiumspread.domain.member.MemberCommand
import io.premiumspread.domain.member.MemberService
import io.premiumspread.interfaces.api.auth.LoginMemberId
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
    private val memberService: MemberService,
) {

    @PostMapping("/register")
    fun register(@RequestBody request: MemberRequest.Register): ResponseEntity<MemberResponse.Detail> {
        val command = MemberCommand.Register(
            email = request.email,
            rawPassword = request.password,
        )
        val member = memberService.register(command)
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.Detail.from(member))
    }

    @GetMapping("/me")
    fun me(@LoginMemberId memberId: Long): ResponseEntity<MemberResponse.Detail> {
        val member = memberService.findById(memberId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(MemberResponse.Detail.from(member))
    }
}
