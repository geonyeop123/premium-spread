# owner 알림 채널 기반 — Discord·메일 라우팅 (issue #88)

owner 알림(실계정 순손익 4종)을 메일·Discord 로 라우팅하는 공통 기반. 알림 종류별 기본 경로는 Domain `OwnerAlertKind` 한 곳에 두고
env 로 덮어쓴다. 상위 이슈 #87, 후속 #89(계정)·#90(시장)·#91(운영).

## 문서

- [③ 합의 기록](_brief.md) — 사용자 합의, 데이터 재고, 알림별 채널 라우팅 초안
- [설계](design.md) — outbox 채널화(V19), 라우팅(§3), Discord(§5), 설정·기동 조건(§7), 배포·롤백(§4.5), 구현 중 변경
- [실행 계획](plan.md) — Task 0~12
- [완료 기준 계약서](dod.md) — 수용기준 13개 (`status: FROZEN`, 판정 AWAITING_HUMAN — AC12 runbook 검토, AC13 owner Discord 수신)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- [운영 runbook](../../runbooks/account-pnl-alert.md) — owner 알림 채널 env·Discord 설정·배포/롤백·복구 SQL
