package io.premiumspread.domain.tradeprep

/**
 * 계획을 만들 수 있는 회원을 정하는 허가 정책이다 (design.md D10, `dod.md` AC12).
 *
 * V1 은 **단일 owner** 시스템이다(design.md §1.2). 회원 가입은 공개 endpoint 이므로 인증만으로
 * 게이트를 삼으면 아무나 가입해 자동매매 준비 계획을 만들 수 있다 — 상위 `P3-O12`("다른 회원이나
 * account 가 자동매매 권한을 얻지 않는다")가 금지하는 것이다.
 *
 * ## 비어 있으면 아무도 허가되지 않는다
 *
 * 허가 목록이 비었다는 것은 "허가된 owner 가 없다"는 뜻이다. 이를 "전원 허가"로 읽으면 설정을
 * 빠뜨린 배포가 곧바로 D10 이 막으려던 상태가 된다 — `ARMED` 게이트와 같은 fail-closed 기본값이다
 * (D9·D19).
 *
 * ## 대소문자를 접지 않는다
 *
 * `member.email` 은 unique 이지만 대소문자까지 접어 비교하지는 않으므로 `owner@x`와 `Owner@x`는
 * **서로 다른 회원 행**이 될 수 있다. 비교할 때 대소문자를 접으면 허가하지 않은 그 다른 행까지
 * owner 로 통과한다. 그래서 정확 일치만 허가다 — 설정 오타는 "아무도 허가되지 않음"으로
 * 드러나므로 fail-closed 다.
 */
class TradePreparationOwnerPolicy(allowedEmails: Collection<String>) {

    /** 설정 표기에서 생긴 공백·빈 항목만 제거한다. 그 외에는 원문 그대로 비교한다. */
    val allowedEmails: Set<String> = allowedEmails.map(String::trim).filter(String::isNotEmpty).toSet()

    /**
     * [email] 이 허가된 owner 인지 판정한다. 회원을 찾지 못해 `null` 인 경우(탈퇴 회원이 아직
     * 유효한 access token 을 들고 있는 경우 등)도 허가되지 않는다.
     */
    fun isAuthorized(email: String?): Boolean = email != null && email in allowedEmails
}
