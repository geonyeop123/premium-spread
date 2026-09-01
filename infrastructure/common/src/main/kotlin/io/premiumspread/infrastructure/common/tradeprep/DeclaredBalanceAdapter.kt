package io.premiumspread.infrastructure.common.tradeprep

import io.premiumspread.domain.tradeprep.BalanceSnapshot
import io.premiumspread.domain.tradeprep.BalanceSnapshotReadPort
import java.math.BigDecimal
import java.time.Instant

/**
 * owner가 요청 본문에 실어 보낸 신고 잔고를 표시용 [BalanceSnapshot]으로 만든다 (design.md D1).
 *
 * 신고값은 실계정과 전혀 대조되지 않았으므로 [BalanceSnapshot.declared]가
 * [io.premiumspread.domain.tradeprep.BalanceBasis.UNVERIFIED]로 고정한다 — 이 결과에서
 * 판정용 [io.premiumspread.domain.tradeprep.VerifiedBalance]는 만들어지지 않는다
 * (D9, `VerifiedBalance.from`이 강제).
 *
 * [BalanceSnapshotReadPort.findLatest]는 인자를 받지 않지만 신고값은 요청마다 다르다. 그래서 이
 * 어댑터는 Spring singleton bean이 아니라 요청 값으로 그때그때 만들어 쓰는 값 객체다 — 요청
 * 스코프 빈 같은 추가 장치 없이 `.ai/rules/coding` "과도한 추상화 금지"를 지킨다.
 *
 * **`apps:api` Facade는 이 클래스를 직접 만들지 않는다.** 앱은 infrastructure 를 `runtimeOnly`
 * 로만 소비하므로(`.ai/rules/architecture.md`) 이 타입은 앱의 컴파일 경로에 없다. Facade 는
 * 신고 잔고를 [BalanceSnapshot.declared] 로 직접 만들고, 이 어댑터는 `BalanceSnapshotReadPort`
 * 계약의 production 구현이 declared 하나뿐임을 배선 수준에서 증명하는 자리(AC20)와, 실제 원천이
 * 생기기 전까지의 참조 구현으로 남는다. 두 경로가 같은 [BalanceSnapshot.declared] 를 쓰므로
 * `UNVERIFIED` 규칙의 정의는 여전히 한 곳이다.
 */
class DeclaredBalanceAdapter(
    private val koreaBalance: BigDecimal,
    private val foreignBalance: BigDecimal,
    private val observedAt: Instant,
) : BalanceSnapshotReadPort {

    override fun findLatest(): BalanceSnapshot = BalanceSnapshot.declared(
        koreaBalance = koreaBalance,
        foreignBalance = foreignBalance,
        observedAt = observedAt,
    )
}
