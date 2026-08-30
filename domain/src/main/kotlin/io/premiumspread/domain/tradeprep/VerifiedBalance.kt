package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.time.Instant

/**
 * 판정용(exposure-increasing) 잔고 (design.md D2·D9).
 *
 * 생성자를 이 클래스 밖에 감춰 [from] 외의 경로로는 인스턴스를 만들 수 없다. `UNVERIFIED`·
 * `UNAVAILABLE` 스냅샷으로는 [from]이 `null`을 반환하므로, declared 원천(`DeclaredBalanceAdapter`)만
 * 있는 입력으로는 이 타입에 영원히 도달할 수 없다 — 컴파일러(생성자 가시성)와 [from]의 판정으로
 * 함께 강제하는 신뢰 경계다 (`dod.md` AC13).
 */
class VerifiedBalance private constructor(
    val snapshotId: String,
    val koreaBalance: BigDecimal,
    val foreignBalance: BigDecimal,
    val balanceBasis: BalanceBasis,
    val observedAt: Instant,
) {
    companion object {
        /**
         * `FRESH`·`STALE` 스냅샷만 변환한다. `UNVERIFIED`(declared)·`UNAVAILABLE`(조회 실패)은
         * `null`이다 — 이 분기가 D9의 신뢰 경계다.
         */
        fun from(snapshot: BalanceSnapshot): VerifiedBalance? =
            when (snapshot.balanceBasis) {
                BalanceBasis.FRESH, BalanceBasis.STALE ->
                    VerifiedBalance(
                        snapshotId = snapshot.id,
                        koreaBalance = snapshot.koreaBalance,
                        foreignBalance = snapshot.foreignBalance,
                        balanceBasis = snapshot.balanceBasis,
                        observedAt = snapshot.observedAt,
                    )
                BalanceBasis.UNAVAILABLE, BalanceBasis.UNVERIFIED -> null
            }
    }
}
