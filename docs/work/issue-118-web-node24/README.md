# apps/web Node 24 전환과 jsdom 30 (issue #118)

CI 6번 잡과 `apps/web/Dockerfile`의 Node 런타임을 20에서 24로 올리고 jsdom 30·`@types/node` 24를 적용했다.
Node major 네 곳(CI, Dockerfile, `engines.node`, `@types/node`)이 갈리면 계약 테스트가 실패한다.

## 문서

- [설계](design.md) — 실측, 결정 D1~D9, lockfile 재해결 기준
- [실행 계획](plan.md) — Task 0~7
- [완료 기준 계약서](dod.md) — 수용기준·증거 로그
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- 이슈: #118 (계기 PR #114, 선행 #98·#97)
