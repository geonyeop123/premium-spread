# apps/web npm overrides 재검토 (issue #97)

`apps/web/package.json`의 `overrides` 14개를 실측(하나씩·전부 제거 후 `npm audit`)으로 판정해 모두 제거했다.
lockfile은 재해결 15건만 반영했고, override 재도입 정책의 정본은 runbook에 둔다.

## 문서

- [설계](design.md) — 실측, 14행 판정표와 열린 advisory 2건, lockfile 15건 계약, `npm audit fix` 재확인, D1~D6
- [실행 계획](plan.md) — Task 0~5
- [완료 기준 계약서](dod.md) — 수용기준·증거 로그
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- 이슈: #97 (선행 #82, #98)
