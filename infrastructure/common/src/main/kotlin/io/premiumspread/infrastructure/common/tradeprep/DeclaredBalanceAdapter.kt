package io.premiumspread.infrastructure.common.tradeprep

import io.premiumspread.domain.tradeprep.BalanceBasis
import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * owner가 요청 본문에 실어 보낸 신고 잔고를 표시용 [BalanceSnapshot]으로 만든다 (design.md D1).
 *
 * 신고값은 실계정과 전혀 대조되지 않았으므로 [BalanceBasis.UNVERIFIED]로 고정한다 — 이 결과에서
 * 판정용 [io.premiumspread.domain.tradeprep.VerifiedBalance]는 만들어지지 않는다
 * (D9, `VerifiedBalance.from`이 강제).
 *
 * [BalanceSnapshotReadPort.findLatest]는 인자를 받지 않지만 신고값은 요청마다 다르다. 그래서 이
 * 어댑터는 Spring singleton bean이 아니라, 호출자(Facade, T5)가 요청 값으로 그때그때 만들어 쓰는
 * 값 객체다 — 요청 스코프 빈 같은 추가 장치 없이 `.ai/rules/coding` "과도한 추상화 금지"를 지킨다.
 */
class DeclaredBalanceAdapter(
    private val koreaBalance: BigDecimal,
    private val foreignBalance: BigDecimal,
    private val observedAt: Instant,
) : BalanceSnapshotReadPort {

    override fun findLatest(): BalanceSnapshot = BalanceSnapshot(
        id = "declared-${UUID.randomUUID()}",
        koreaBalance = koreaBalance,
        foreignBalance = foreignBalance,
        balanceBasis = BalanceBasis.UNVERIFIED,
        observedAt = observedAt,
    )
}
