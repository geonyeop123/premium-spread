# 실계정 순손익 회차별 로그와 정시 보고 메일 (Account PnL Round Log)

batch 가 매분 계산하는 실계정 순손익을 회차마다 INFO 로그 한 줄로 남기고, 매 정시 이후 첫 계산 성공 회차에 정시 보고 메일을 1통
보낸다. 소규모 기능이라 설계·계획·DoD 단계를 건너뛰었다(owner 요청 기반, orchestrator 소규모 경로).

## 문서

- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속 과제
- [운영 runbook](../../runbooks/account-pnl-alert.md) — 로그 확인 명령, `ACCOUNT_PNL_HOURLY_REPORT_ENABLED`, 백그라운드 실행
