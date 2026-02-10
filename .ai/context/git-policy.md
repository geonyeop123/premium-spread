# Git 정책

## 브랜치 정책

- 작업당 하나의 브랜치를 사용한다: `<type>/<short-description>`
- 기본 base branch는 `feature/premium`이다.

## 커밋 정책

- 커밋 메시지 형식:
  - 제목: `<type>: <subject>`
  - 본문: 한글 명령형 bullet 목록
- 노이즈 파일 제외:
  - `.claude/settings.local.json`
  - `logs/`
  - `apps/api/logs/`
  - `apps/batch/logs/`

## PR 정책

- 작업 브랜치당 PR 1개를 생성한다.
- PR 제목은 커밋 첫 줄과 동일하게 맞춘다.
- PR 본문에는 반드시 아래를 포함한다:
  - `## Summary`
  - `## Test plan`

## 차단 조건

아래 조건이면 commit/push/PR을 실행하지 않는다:
- `Critical` 또는 `High` 리뷰 이슈가 남아 있는 경우
- 테스트가 실패한 경우
- 문서 업데이트가 누락된 경우
