# Git Conventions

- 브랜치 규칙: `<type>/<short-description>` (`type`: `feat|fix|refactor|docs|test|chore`)
- 커밋 규칙: `<type>: <subject>` + 한글 bullet 본문
- PR 규칙: 제목은 커밋 첫 줄과 동일, 본문에 `Summary`/`Test plan` 포함
- 상세 Git 정책은 `.ai/context/git-policy.md`를 기준으로 따른다.
- **커밋에 Co-Authored-By: Claude 넣지 않기**
- **신규 작업 시 반드시 브랜치 분리** — 새로운 기능/리팩토링/버그 수정 등 신규 작업은 항상 현재 브랜치에서 새 브랜치를 생성하여 진행한다.
