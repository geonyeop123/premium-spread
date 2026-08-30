package io.premiumspread.domain.tradeprep

import java.math.BigDecimal
import java.time.Instant

/**
 * `VerifiedBalanceReadPort`의 **test 전용 fixture**다 (design.md D9·D22, `dod.md` AC13·AC20).
 * "기록된"(검증 가능한) 원천을 흉내 내 `ARMED` 전이의 code-ready 경로(`P3-O3`)를 검증하는 데
 * 쓴다 — 실거래소 조회(`ExchangeBalanceAdapter`)는 `ACT-2` 이후다.
 *
 * **반드시 test source set에만 둔다.** main classpath로 옮기면 배선 실수 한 번으로 D9의 안전장치
 * (declared 신고값으로는 판정용 잔고를 만들 수 없다)가 무너진다. `domain`은 `infrastructure:*`에
 * 의존하지 않으므로 이 클래스는 이 모듈의 test 소스에만 존재해야 다른 모듈(`apps:api` 등)의
 * production 배선에 노출되지 않는다 — `TradePreparationWiringContractTest`(`:apps:api:test`,
 * AC20)가 production classpath에 이 클래스가 없음을 검증한다.
 */
class RecordedBalanceAdapter(
    private val koreaBalance: BigDecimal,
    private val foreignBalance: BigDecimal,
    private val observedAt: Instant,
    private val balanceBasis: BalanceBasis = BalanceBasis.FRESH,
    private val snapshotId: String = "recorded-$observedAt",
) : VerifiedBalanceReadPort {

    override fun findForDecision(): VerifiedBalance? = VerifiedBalance.from(
        BalanceSnapshot(
            id = snapshotId,
            koreaBalance = koreaBalance,
            foreignBalance = foreignBalance,
            balanceBasis = balanceBasis,
            observedAt = observedAt,
        ),
    )
}
