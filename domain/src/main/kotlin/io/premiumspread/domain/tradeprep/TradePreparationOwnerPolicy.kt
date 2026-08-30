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
 * ## 정확 일치로 비교한다 — 저장된 표기를 그대로 넣어야 한다
 *
 * `member` 테이블은 `utf8mb4_unicode_ci` collation 이라(`V7__create_member_table.sql`)
 * `uk_member_email` 도 case-insensitive 다. 즉 대소문자만 다른 두 회원 행은 **존재할 수 없고**,
 * 비교를 접든 안 접든 다른 회원이 통과할 위험은 없다. 정확 일치는 안전을 위해 강제된 것이 아니라
 * 더 좁은 쪽을 고른 결과다 — 느슨하게 할 이유가 없어서 그대로 둔다.
 *
 * 대신 운영에서 이런 일이 생긴다. MySQL 은 입력 표기를 그대로 저장하므로
 * `Owner@example.com` 으로 가입한 회원은 **로그인은 되고**(조회가 case-insensitive) 허가 목록에
 * `owner@example.com` 이 있으면 영구히 거절당한다. 그래서 허가 목록에는 **가입 시 저장된 표기
 * 그대로** 넣어야 한다. 증상과 대조 절차는 `docs/runbooks/deployment.md` 가 소유한다.
 */
class TradePreparationOwnerPolicy(allowedEmails: Collection<String>) {

    /** 설정 표기에서 생긴 공백·빈 항목만 제거한다. 그 외에는 원문 그대로 비교한다. */
    val allowedEmails: Set<String> = allowedEmails.map(String::trim).filter(String::isNotEmpty).toSet()

    /**
     * [email] 이 허가된 owner 인지 판정한다. 저장된 표기와 정확히 일치해야 한다(위 KDoc).
     * 회원을 찾지 못해 `null` 인 경우(탈퇴 회원이 아직 유효한 access token 을 들고 있는 경우
     * 등)도 허가되지 않는다.
     */
    fun isAuthorized(email: String?): Boolean = email != null && email in allowedEmails
}
