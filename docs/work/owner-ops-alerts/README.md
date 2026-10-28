# owner 운영 알림 — 환율 지연·시세 끊김·Job 연속 실패 (issue #91)

batch 가 환율 수집 지연(30분)·거래소 시세 끊김(5분)·Job 연속 실패(3회·5분)를 지속 상태로 감지해 owner Discord ops 채널(Job 연속 실패는 메일 포함)로 알린다.
6시간마다 재알림, 해소 시 무음 복구. PnL 모니터와 독립인 공용 owner 알림 기반(범용 enqueue port·독립 전달 Job·채널 bean 분리)을 함께 만든다.

## 문서

- [설계](design.md) — 신호·3상태 관측(§5), 연속 실패 카운터(§6), 전달 Job(§7), 신호 Job(§8), 기동 조건(§9), 본문(§10), 변경 이력(§15)
- [실행 계획](plan.md) — Task 0~16
- [완료 기준 계약서](dod.md) — 수용기준 15개 (`status: FROZEN`, 변경 요청 #1 전달 주기 60s, ⑩ AWAITING_HUMAN — AC13 runbook 검토, AC14 Discord 수신)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- [운영 runbook](../../runbooks/owner-ops-alert.md) — 판정 경계·라우팅·env·기동 로그·정리 SQL·Discord 수신 체크리스트
