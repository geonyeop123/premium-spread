package io.premiumspread.application.tradeprep

import java.math.BigDecimal

/**
 * 거래 준비 유스케이스 입력이다 (`.ai/rules/naming.md` inner class 패턴).
 *
 * **어떤 입력도 owner 를 받지 않는다.** `memberId` 는 controller 가 인증 principal 에서 도출해
 * 채우며 요청 body 에서 오지 않는다 (design.md D10, `dod.md` AC12). 이름이 `ownerId` 가 아니라
 * `memberId` 인 것은 기존 `TrackingCriteria` 와 같은 축을 쓰기 위해서다 — 계획의 owner 와
 * tracking 의 member 는 같은 회원이다.
 */
class TradePreparationCriteria private constructor() {

    /**
     * 준비 계산과 `DRAFT` 계획 생성 (design.md D2·D5·D8·D12).
     *
     * [koreaBalance]·[foreignBalance] 는 owner 신고값이다. 이 값들은 `UNVERIFIED` 표시용
     * 스냅샷이 되며 판정용 잔고로 승격되지 않는다 (D9). 판정용 원천이 배선되면 그쪽이 우선한다.
     */
    data class Prepare(
        val memberId: Long,
        val symbol: String,
        val koreaExchange: String,
        val foreignExchange: String,
        val koreaBalance: BigDecimal,
        val foreignBalance: BigDecimal,
    )

    /** 진입 목표 프리미엄 등록 → `WATCHING` (design.md D6·D13·D18·D20·D23). */
    data class RegisterTarget(
        val planId: Long,
        val memberId: Long,
        val desiredEntryPremiumRate: BigDecimal,
    )

    /** owner 의 명시 무효화 (design.md D4·D11). */
    data class Invalidate(val planId: Long, val memberId: Long)

    /** owner 의 명시 refresh (design.md D4·D11). */
    data class Refresh(val planId: Long, val memberId: Long)

    /** owner-scoped 단건 조회 (design.md D10). */
    data class FindById(val planId: Long, val memberId: Long)
}
