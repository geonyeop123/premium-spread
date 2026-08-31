# 하네스 aic-api 정합 재구성

`.claude/` 하네스를 사내 `aic-api` 저장소와 같은 구조(에이전트 6축 · 진입 스킬 `orchestrator` ·
패턴 스킬 8 · 벤더링 2)로 교체하고, 내용을 이 저장소의 실제 계약(Kotlin · MySQL/Redis ·
포트-어댑터 · `infrastructure:common` Flyway · MarketPair)으로 재작성한 작업이다.
애플리케이션 코드는 변경하지 않았다.

## 문서

- [설계](design.md) — 현행 drift 8건, 타깃 구조, aic-api→premium-spread 계약 치환표, 리스크
- [실행 계획](plan.md) — 태스크 1~8
- [완료 기준 계약서](dod.md) — 수용기준 8개 (`status: FROZEN`, 판정 PASS)
- [개발자 이해문서](understanding.md) — 배경·결정·함정(벤더링 심볼릭 링크 등)

## 관련

- [CLAUDE.md `## 하네스`](../../../CLAUDE.md) — 진입점 포인터와 변경 이력
- [orchestrator 스킬](../../../.claude/skills/orchestrator/SKILL.md) — 단계·게이트·검증 명령의 단독 소유자
