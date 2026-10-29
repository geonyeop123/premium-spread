# owner 시장 알림 — 김프 급변·기준선 돌파·신고가/신저가·일일 요약 (issue #90)

batch 가 기존 김프 데이터(Redis 현재값·분/시/일 집계)만으로 김프 급변·기준선 돌파·1h/24h 신고가·신저가·09:00 KST 일일 요약을 판정해 owner Discord market 채널(요약은 메일 포함)로 알린다.
실계정 순수익률은 PnL 모니터가 남긴 최신값을 읽고, 시장 알림은 PnL 모니터와 독립이다.

## 문서

- [설계](design.md) — 읽기 경로(§2~§3), 창·근거 충분성(§4), V22(§5), 실계정 최신값(§6), 정책 4종(§7), 본문(§8), 기동 조건·env(§9), 변경 이력(§15)
- [실행 계획](plan.md) — Task 0~17
- [완료 기준 계약서](dod.md) — 수용기준 17개 (`status: FROZEN`, ⑩ AWAITING_HUMAN — AC16 runbook 검토, AC17 Discord 수신)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- [운영 runbook](../../runbooks/owner-market-alert.md) — 판정 기준·라우팅·env·메일 상한·기동 로그·정리 SQL·수신 체크리스트
