# owner 계정 알림 — 포지션 변경·순수익률 급변·미반영 리마인더 (issue #89)

기존 실계정 모니터 회차가 owner 계정 알림 3종을 판정해 #88 라우팅·outbox 로 보낸다. 상태는 V20 이 `account_pnl_monitor_state` 에 더한 열에
기존 저장과 같은 트랜잭션·version 으로 남는다. 상위 이슈 #87, 기반 #88.

## 문서

- [설계](design.md) — Domain 정책 3개·조합기(§3), V20 상태 저장(§4), Job(§5), 라우팅·설정(§6), 렌더링(§7), 사용자 확정(§12), 구현 중 변경(§13)
- [실행 계획](plan.md) — Task 0~14
- [완료 기준 계약서](dod.md) — 수용기준 14개 (`status: FROZEN`, ⑩ PASS T1 12/12 · AWAITING_HUMAN — AC13 runbook 검토, AC14 owner Discord 수신)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- [운영 runbook](../../runbooks/account-pnl-alert.md) — 계정 알림 env·라우팅·배포/롤백·AC14 체크리스트
