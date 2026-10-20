---
paths:
  - "http/**"
  - "apps/api/**/interfaces/**/*.kt"
---

# HTTP 규칙 진입 안내

`http/` 의 요청 샘플이나 `apps/api` 의 interfaces 계층을 고치기 전에 `.ai/rules/http.md` 를 Read 도구로
끝까지 읽는다. 규칙의 정본은 그 파일이다 — `AGENTS.md`(Codex)와 공유하므로 여기에 옮겨 적지 않는다.

이 파일에 `@` import 를 넣지 않는다. 래퍼 안의 import 는 `paths` 조건과 무관하게 세션 시작 시 펼쳐져
조건부 로딩을 무력화한다(실측 근거: `docs/harness-changelog.md` 2026-10-20 행).
