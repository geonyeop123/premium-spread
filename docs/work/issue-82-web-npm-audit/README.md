# apps/web npm audit 취약점 해소 (issue #82)

Quality Gate 6번 잡(`ci/run-npm-audit.sh`)을 다시 통과시키기 위해 `apps/web` 의존성을 올렸다. high·critical 15건, 전 등급 23건을 0건으로 만들었다.
웹 소스는 바꾸지 않았다.

## 문서

- [설계](design.md) — 상향 대상과 결정 D1~D7, D4-결과(overrides 경로)
- [실행 계획](plan.md) — Task 1~7
- [완료 기준 계약서](dod.md) — 수용기준 7개 (`status: FROZEN`, AC6 화면 육안 확인 대기)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
