# 실계정 순손익 알림 — 바이낸스 수동 입력 모드 (Live Account PnL Manual Binance)

바이낸스 API key 없이도 실계정 순손익 알림을 받도록, 바이낸스 leg 를 `BINANCE_MODE=api`(서명 REST) | `manual`(env 로 입력한 포지션
+ 공개 호가) 중 하나로 구동한다. 직전 단위 [실계정 순손익 알림](../live-account-pnl-alert/README.md) 위에 얹는다.

## 문서

- [설계](design.md) — 모드·설정 해석(§3), 기동 조건(§4), 메일 표기(§5.4), 공유 클라이언트(§8.1), 구현 중 변경
- [실행 계획](plan.md) — Task 0~8
- [완료 기준 계약서](dod.md) — 수용기준 9개 (`status: FROZEN`, 판정 AWAITING_HUMAN — AC8 runbook 검토, AC9 owner 실계정 확인)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속 과제
- [운영 runbook](../../runbooks/account-pnl-alert.md) — 모드 선택·manual env 표·한계·manual 검산 체크리스트
