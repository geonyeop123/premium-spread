# 실계정 순손익 알림 (Live Account PnL Alert)

빗썸 현물·바이낸스 USDⓈ-M 선물 계정을 read-only key 로 1분마다 읽어, 수수료·펀딩·예상 청산 수수료를 반영한
"지금 전량 청산하면 남는" 순수익률이 1% 이상이면 메일로 알리는 batch 기능이다. 주문은 하지 않으며 기본값은 꺼짐이다.

## 문서

- [설계](design.md) — 순손익 공식(§3), 알림 규칙(§4), master spec 이탈 결정(§0.1), 구현 반영 사항
- [실행 계획](plan.md) — 태스크 0~14, Task 0 보정 P1~P8, 구현 중 변경 표
- [완료 기준 계약서](dod.md) — 수용기준 13개 (`status: FROZEN`, AC10 명령 개정 1건, 판정 AWAITING_HUMAN — AC13 owner 실계정 검산)
- [개발자 이해문서](understanding.md) — 배경·결정·함정, 외부 API 조사 기록(AC12)
- [운영 runbook](../../runbooks/account-pnl-alert.md) — key 발급·환경변수·로컬 실행·AC13 체크리스트
