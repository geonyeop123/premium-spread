package io.premiumspread.domain.accountpnl

/**
 * 기준 포지션 단계 알림(#131)의 부트스트랩용 조회 port.
 *
 * 상태에 기준이 없는데 이미 무포지션인 회차에서, 직전 포지션의 기준 평단을 이력에서 찾는다.
 */
fun interface AccountReferencePositionReadPort {
    /**
     * 열린 건의 reference 를 우선하고, 없으면 reference 가 있는 가장 최근 닫힌 건의 reference 를 돌려준다.
     * 둘 다 없으면 null. 조회만 하며 잠금을 잡지 않는다.
     */
    fun findLatestReference(): ReferenceLegs?
}
