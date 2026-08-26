# 거래 준비 (Trade Preparation)

실계정 잔고로 잡을 수 있는 물량·레버리지를 산출하고, owner 가 걸어둔 희망 프리미엄에 도달하면
계획을 실행 가능 상태로 전이한다. **주문은 제출하지 않는다.**

상위 spec §5 roadmap 상 Phase 3 outcome 을 앞당긴 단위다. 재순서화 근거는
[`progress.md`](../../../.ai/planning/private-live-autotrader/progress.md) 2026-08-26 절이 소유한다.

## 문서

- [스펙](design.md) — 목표·범위, 결정 D1~D8, 사이징 관계식, 미해결 결정, 상위 추적성
- [실행 계획](plan.md) — T1~T8 태스크
- [완료 기준 계약서](dod.md) — 수용기준 10개 (`status: DRAFT`)

## 근거

- [프로그램 스펙](../private-live-autotrader/design.md)
- [`ECO-5` 산출](../private-live-autotrader/eco-5-capital-cycle.md) — 캡·배분·관계식의 정본
- [Phase 0 계획 검토](../private-live-autotrader-phase-0/review-findings.md) — 이 단위를 당긴 근거
