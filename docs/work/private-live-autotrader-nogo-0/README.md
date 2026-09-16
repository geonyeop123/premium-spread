# NOGO-0 — 비용·기간 상한과 재평가 시점 승인

PRIVATE LIVE 자동매매 프로그램의 `NOGO-0` 승인 실행 단위다. **값을 정하지 않는다.** owner가 8개 값을
결정할 수 있는 형태를 만들고, 그 결정을 소유할 실행 단위를 상위 plan에 등재한다.

[Phase 0 계획 검토](../private-live-autotrader-phase-0/review-findings.md) §1의 유일한
critical — *"`NOGO-0` 에 상한값도 소유 실행 단위도 없다"* — 을 닫는 것이 목적이다.

## 문서

- [스펙](design.md) — 왜 지금인가(Phase 1 진입 차단 판정), "프로그램 개시 시" 문면 판정, owner 결정
  입력표 8개와 근거, 의존 구조, `N0` 행 내용 계약
- [실행 계획](plan.md) — T1~T7 태스크
- [완료 기준 계약서](dod.md) — 수용기준 6개 (`status: DRAFT`)
- [검증 러너](verify.sh) — `bash docs/work/private-live-autotrader-nogo-0/verify.sh --static`

## 상위 문서

- [프로그램 스펙](../private-live-autotrader/design.md) — §4.4 `ECG-4` · §4.5 정책값 승인 규칙 ·
  §9.4 `NOGO-0`
- [프로그램 plan](../private-live-autotrader/plan.md) — §1 gate 표에 `N0` 행을, §3에 위임 한 줄을 추가한다
- [남은 실행 단위 예상 소요](../private-live-autotrader/remaining-effort-estimate.md) — §7이 결정 입력
  8개의 정본이고 §5 민감도표가 `E` 지배항의 근거다
- [ECO-5 자본 사이클 산출](../private-live-autotrader/eco-5-capital-cycle.md) — 배분 권고(빗썸 79~81%)의 근거
- [ACT-1 평가 시점 결정](../private-live-autotrader/act1-evaluation-timing.md) — 재평가 시점 후보와
  허용 손실 소속 쟁점
- [진행 기록](../../../.ai/planning/private-live-autotrader/progress.md) — 승인값과 승인 시각이 append될 곳
