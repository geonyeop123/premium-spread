package io.premiumspread.interfaces.api.tradeprep

import io.premiumspread.application.tradeprep.TradePreparationCriteria
import io.premiumspread.application.tradeprep.TradePreparationFacade
import io.premiumspread.application.tradeprep.TradePreparationResult
import io.premiumspread.interfaces.api.auth.LoginMemberId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 거래 준비 REST 경계다 (`.ai/rules/http.md`, `.ai/rules/architecture.md` Controller → Facade).
 *
 * Request validation · Criteria 변환 · Result→Response/HTTP status 매핑만 한다. Facade 하나만
 * 주입하며 Domain·Infrastructure 타입을 직접 반환하지 않는다.
 *
 * ## owner
 *
 * 모든 유스케이스의 owner 는 `@LoginMemberId` 로 인증 principal 에서 도출한다. 요청 body 에
 * owner 필드는 없다 (design.md D10, `dod.md` AC12). 남의 계획에 대한 요청은 Facade 가
 * `TRADE_PREPARATION_NOT_FOUND` 로 답하고 그것이 404 가 된다 — 403 은 그 id 의 계획이
 * **존재한다**는 사실을 노출한다.
 *
 * ## 인증
 *
 * 이 경로들은 `PublicEndpointPolicy` 에 없다 (`dod.md` AC9). 거기 넣는 순간 인증 없이 열린다.
 */
@RestController
@RequestMapping("/api/v1/trade-preparations")
class TradePreparationController(private val tradePreparationFacade: TradePreparationFacade) {

    /**
     * 준비 계산과 `DRAFT` 계획 생성 (design.md D2·D5·D12).
     *
     * 캡을 위반하면 Facade 가 예외 대신 `planId = null` + `capViolations` 를 담은 결과를 준다
     * (design.md §3 이 "위반한 캡을 응답에 명시한다"를 요구하므로 오류 envelope 에 담을 수 없다).
     * 여기서 그 결과를 422 로 옮기되 **본문은 그대로 내보낸다** — 상태 코드만 바꾸고 캡 정보를
     * 떨어뜨리면 AC3 을 충족하지 못한다.
     *
     * 반올림 뒤 물량이 0 이 되는 경우도 `plannable = false` 다. 계획이 만들어지지 않았으므로
     * 201 이 아니고, 위반한 캡이 없으므로 `code` 도 비어 있다 — 본문의 산출값이 왜 계획이
     * 만들어지지 않았는지를 말한다.
     */
    @PostMapping
    fun prepare(
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: TradePreparationRequest.Prepare,
    ): ResponseEntity<TradePreparationResponse.Preparation> {
        val criteria = TradePreparationCriteria.Prepare(
            memberId = memberId,
            symbol = request.symbol,
            koreaExchange = request.koreaExchange,
            foreignExchange = request.foreignExchange,
            koreaBalance = request.koreaBalance,
            foreignBalance = request.foreignBalance,
        )
        val result = tradePreparationFacade.prepare(criteria)
        return ResponseEntity.status(statusOf(result)).body(TradePreparationResponse.Preparation.from(result))
    }

    /** 진입 목표 프리미엄 등록 → `WATCHING` (design.md D6·D13·D18·D20·D23). */
    @PostMapping("/{id}/target")
    fun registerTarget(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
        @Valid @RequestBody request: TradePreparationRequest.RegisterTarget,
    ): ResponseEntity<TradePreparationResponse.Detail> {
        val criteria = TradePreparationCriteria.RegisterTarget(
            planId = id,
            memberId = memberId,
            desiredEntryPremiumRate = request.desiredEntryPremiumRate,
        )
        return ResponseEntity.ok(TradePreparationResponse.Detail.from(tradePreparationFacade.registerTarget(criteria)))
    }

    /** owner 의 명시 refresh (design.md D4·D11). 결속 잔고를 더 이상 신뢰하지 않겠다는 선언이다. */
    @PostMapping("/{id}/refresh")
    fun refresh(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<TradePreparationResponse.Detail> {
        val result = tradePreparationFacade.refresh(TradePreparationCriteria.Refresh(id, memberId))
        return ResponseEntity.ok(TradePreparationResponse.Detail.from(result))
    }

    /** owner 의 명시 무효화 (design.md D4·D11). */
    @PostMapping("/{id}/invalidate")
    fun invalidate(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<TradePreparationResponse.Detail> {
        val result = tradePreparationFacade.invalidate(TradePreparationCriteria.Invalidate(id, memberId))
        return ResponseEntity.ok(TradePreparationResponse.Detail.from(result))
    }

    /** owner-scoped 단건 조회 (design.md D10). 남의 계획은 404 다. */
    @GetMapping("/{id}")
    fun getById(
        @PathVariable id: Long,
        @LoginMemberId memberId: Long,
    ): ResponseEntity<TradePreparationResponse.Detail> {
        val result = tradePreparationFacade.findById(TradePreparationCriteria.FindById(id, memberId))
        return ResponseEntity.ok(TradePreparationResponse.Detail.from(result))
    }

    private fun statusOf(result: TradePreparationResult.Preparation): HttpStatus =
        if (result.plannable) HttpStatus.CREATED else HttpStatus.UNPROCESSABLE_ENTITY
}
