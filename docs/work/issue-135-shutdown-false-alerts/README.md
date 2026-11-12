# batch 종료 시 운영 알림 오탐 정리 (issue #135)

batch 종료 신호 뒤 새 Job 실행은 건너뛰고, 진행 중 실행의 실패는 `SKIPPED("shutdown")`으로 기록해 `BATCH_JOB_FAILED` 운영 알림 오탐을 막는다.

## 문서

- [설계](design.md)
- [실행 계획](plan.md)
- [완료 기준 계약서](dod.md)
- [개발자 이해문서](understanding.md)
- 운영: [owner 운영 알림 runbook](../../runbooks/owner-ops-alert.md)
- 이슈: #135
