package io.premiumspread.domain.member

interface MemberRepository {
    fun save(member: Member): Member
    fun findByEmail(email: String): Member?
    fun findById(id: Long): Member?
    fun existsByEmail(email: String): Boolean

    /**
     * owner 단위 직렬화를 위해 회원 행을 `SELECT … FOR UPDATE` 로 잠근다 (design.md D18).
     *
     * `ACTIVE` tracking 존재 검사(D13)와 체결 무효화(D17)는 서로 다른 테이블을 읽고 자기 테이블만
     * 쓰기 때문에 둘 다 커밋되면 `ACTIVE` tracking 과 활성 계획이 공존한다(write-skew). 두 경로가
     * 트랜잭션 시작점에서 같은 회원 행을 잠가야 나중 트랜잭션이 앞의 커밋을 반드시 본다.
     *
     * **잠금 순서는 항상 member → tracking/plan 이다** — archive 가 이미 잡는 tracking 행 잠금
     * ([io.premiumspread.domain.tracking.TrackingRepository.findOwnedByIdForUpdate])보다 먼저
     * 잡아 교착을 막는다. 이 순서를 뒤집지 않는다.
     */
    fun findByIdForUpdate(id: Long): Member?
}
