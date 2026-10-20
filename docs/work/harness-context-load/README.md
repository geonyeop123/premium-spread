# 세션 자동 로드 문서 정리

Claude Code 세션마다 자동으로 실리는 문서(`CLAUDE.md` + `.claude/rules/*.md` import)에서 무관하거나 틀린 내용을
걷어낸 작업이다. path-scoped 래퍼의 `@import`가 조건을 무력화하던 문제를 고치고, 모듈 트리를 `AGENTS.md` 포인터로,
변경 이력을 `docs/harness-changelog.md`로 옮겼다. 기본 로드량 15,687 → 9,752자. 애플리케이션 코드는 변경하지 않았다.

## 문서

- [설계](design.md) — 원인 실측, 래퍼 안내문·포인터·이력 이전 설계, 구현 중 바뀐 사항
- [실행 계획](plan.md) — 태스크 1~6
- [완료 기준 계약서](dod.md) — 수용기준 8개 (`status: FROZEN`, AC6·AC7 개정, 판정 PASS)
- [개발자 이해문서](understanding.md) — 배경·결정·함정(ugrep 래퍼, transcript 판정)
- [규칙 로딩 판정 스크립트](probe-rule-load.sh) — AC6·AC7 검증
