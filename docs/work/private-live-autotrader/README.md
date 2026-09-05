# 개인용 PRIVATE LIVE 자동매매

개인 단일 계정용 PRIVATE LIVE 자동매매를 단계적으로 구축하는 프로그램의 workflow 산출물이다.

## 프로그램 문서

- [스펙 (master specification)](design.md) — 제품 목표·보장·안전 불변식·Phase 정의
- [상위 plan](plan.md) — Phase를 workflow 실행 단위로 분해한 매핑과 현재 태스크
- [완료 기준 계약서](dod.md) — 현재 실행 단위의 DoD (`status: DRAFT`)
- [개발자 이해문서 (master spec 재작성)](understanding-master-spec.md) — 이번 실행 단위의 배경·결정·함정
- [진행·acceptance 증거](../../../.ai/planning/private-live-autotrader/progress.md) — 상태축 현재값의 단독 소유처
- [`ECO-5` 산출](eco-5-capital-cycle.md) — 자본 소진과 연속 cycle 수 (항목 1·2). `ACT-1` 필수 입력
- [`ACT-1` 평가 시점 결정](act1-evaluation-timing.md) — 세 문서 불일치의 판정과 정정안. **owner 확정 대기**

## 완료된 실행 단위

Phase -1 (PR #63, DoD `VERIFIED`) — 동결 증거이므로 경로를 옮기지 않는다.

- [Phase -1 설계](../../../.ai/planning/private-live-autotrader/phase-minus-1-design.md)
- [Phase -1 실행 계획](../../../.ai/planning/private-live-autotrader/phase-minus-1-plan.md)
- [Phase -1 완료 조건](../../dod/private-live-autotrader-phase-minus-1.dod.md)
- [개발자 이해문서](understanding.md)

## As-Is 아키텍처

현재 구현 경계는 [`.ai/architecture/ARCHITECTURE_DESIGN.md`](../../../.ai/architecture/ARCHITECTURE_DESIGN.md)가
소유한다. 이 디렉터리의 문서는 Planned capability 이며 둘은 서로를 참조한다 (`P0-O5`).

## 다음 Phase

각 Phase는 별도의 `feature-workflow` 실행 단위이며 진입 시 자신의 `docs/work/{phase-slug}/design.md`, `plan.md`,
`dod.md`를 만든다. 진입 조건과 slug 후보는 [plan.md](plan.md) §1이 소유한다.
