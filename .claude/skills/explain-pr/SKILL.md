---
name: explain-pr
description: PR/MR 하나를 '그 작업을 안 한 개발자'가 이해하도록 이해문서를 생성한다. 작업을 수행한 에이전트가 마무리 단계에서 문맥을 담아 작성(warm)하고, 임의 PR 지정 시 diff로 재구성(cold). "이해문서 만들어줘", "이 PR 설명 문서", "/explain-pr" 등에 사용. Claude는 finalize/feature-workflow, Codex는 work의 마무리 단계에서 호출된다.
---

# explain-pr — 개발자용 PR 이해문서

## 독자
이 문서의 독자는 **이 대화/작업 과정을 보지 못한 개발자**다(리뷰어·미래의 나·팀원).
그들이 PR 링크만 보고 "왜 · 무엇을 · 어떻게 · 조심할 점"을 이해·재현할 수 있게 쓴다.

## 절차

### 1. 재료 수집 (기계적)
`scripts/gather.sh --base <branch> [--pr <n>] --out <bundle.md>` 실행 → 번들 1개 생성.
base 미지정 시 스크립트가 자동 결정(인자 > PR target > dev > 기본브랜치). 번들을 한 번 읽는다.

### 2. 문맥 소스 판별 (warm vs cold)
- **같은 세션에서 이 작업을 방금 수행함(Claude: finalize/feature-workflow ⑪, Codex: work ⑪ 마무리)** → **warm**.
  "왜 / 버린 대안 / 시도했다 실패한 것 / 함정"을 세션 문맥에서 직접 가져온다.
- **과거·남의 PR을 지정받음, 세션 문맥 없음** → **cold**.
  번들만으로 의도를 재구성하고, 추정한 문장에는 반드시 `⚠️ 추정`을 붙인다.

### 3. 문서 작성
`template.md`를 복사해 7개 섹션을 채운다.
1. TL;DR — 3줄  2. 왜 — 배경/문제/목표/제약  3. 무엇을 바꿨나 — 변경+핵심파일
4. 설계 — mermaid 1~2개(이해에 기여하는 것만, 장식 금지)  5. 결정과 버린 대안
6. 동작 확인 방법 — 재현 커맨드/테스트  7. 후속·리스크·함정

### 4. 저장 + PR 통합
- 파일: `docs/work/<slug>/understanding.md`로 저장. `<slug>`는 브랜치 slug로 같은 기능의 spec/plan/dod와 동일 키다. PR 번호는 파일명이 아니라 문서 헤더와 PR 본문 링크에만 쓴다. (한 기능에 PR이 여럿이면 `understanding-PR<n>.md`로 폴백.)
- 폴더 랜딩: `docs/work/<slug>/README.md`가 없거나 오래됐으면 한 줄 설명 + 존재하는 문서(design/plan/dod/understanding) 링크로 생성·갱신하고, 상위 `docs/work/README.md`의 작업 목록에 `[한글 제목](<slug>/README.md)` 행을 추가한다.
- PR/MR 본문: 상단에 아래 블록을 삽입한다. **반드시 호스트까지 포함한 절대 URL**이어야 한다.
  경로 문자열만 적으면 리뷰어가 저장소를 뒤져야 하고, **상대 경로는 링크가 깨진다.**

  ```
  ## 개발자 이해문서
  <요약 3줄>

  📄 **[개발자 이해문서 전문 보기 →](<PERMALINK>)**
  <sub>`docs/work/<slug>/understanding.md` · 커밋 고정 링크라 브랜치 삭제 후에도 열립니다</sub>
  ```

  `<PERMALINK>`는 **브랜치명이 아니라 head 커밋 SHA**로 만든다. 브랜치 링크는 머지 후
  브랜치가 삭제되면 404가 되지만, 커밋 SHA는 머지 후에도 계속 열린다.

  **경로 세그먼트가 호스트마다 다르다.** GitHub은 `/blob/`, GitLab은 `/-/blob/` 이다 —
  GitLab은 프로젝트 하위 리소스 앞에 `/-/` 구분자를 넣는다. 한쪽 형식을 양쪽에 쓰지 않는다.

  ```bash
  # 링크 생성 (문서 커밋 후 실행 — SHA가 그 커밋을 가리켜야 한다)
  SLUG=<slug>
  SHA=$(git rev-parse HEAD)
  BASE=$(git remote get-url origin | sed -E 's#(git@|https://)([^:/]+)[:/]#https://\2/#; s#\.git$##')
  case "$BASE" in
    *github.*) SEG="/blob" ;;    # GitHub / GitHub Enterprise
    *)         SEG="/-/blob" ;;  # GitLab (self-hosted 포함)
  esac
  echo "${BASE}${SEG}/${SHA}/docs/work/${SLUG}/understanding.md"
  ```

  - **상대 경로를 쓰지 않는다.** GitLab은 PR/MR 본문의 상대 링크를 `<project>/-/blob/`을
    앞에 붙여 다시 쓴다. `../-/blob/...`으로 적으면 `/-/blob/-/blob/...`으로 겹쳐 404가 되고,
    루트 상대(`/ns/proj/-/blob/...`)도 같은 방식으로 망가지며, 저장소 상대(`docs/work/...`)는
    **기본 브랜치**를 가리켜 머지 전에는 404다. 절대 URL만 그대로 통과한다.
  - 의심스러우면 렌더링 결과를 직접 확인한다 — GitLab은
    `POST /api/v4/markdown` (`gfm:true`, `project:<ns/proj>`)이 실제 `href`를 돌려준다.
  - finalize 경로: `gh pr create` / `glab mr create` **전에** 본문에 포함.
  - 기존 PR: `gh pr edit <n> --body-file` / `glab mr update <n> --description` 로 주입.
  - 주입 후 `gh pr view <n> --json body --jq '.body' | head` 로 링크가 들어갔는지 눈으로 확인한다.

## 완료 판정 (exit checklist)
아래를 모두 만족해야 완료다.
- [ ] "왜" 섹션이 비어있지 않다
- [ ] 모든 mermaid 블록이 문법상 렌더 가능하다
- [ ] 미해결 TODO/TBD가 없다
- [ ] cold 경로면 추정 부분에 `⚠️ 추정`이 표기됐다
- [ ] **PR/MR 본문의 이해문서 링크가 클릭 가능한 절대 URL이고, 브랜치명이 아닌 커밋 SHA를 쓴다**
- [ ] 자문 통과: "이 PR 링크만 보고 개발자가 이해·재현 가능한가?"

## 비목표
- 별도 documenter 에이전트를 만들지 않는다(문맥 소실 방지가 목적).
- gather.sh에 판단을 넣지 않는다(순수 수집).
- HTML/Artifact 리치 시각화는 지금 도입하지 않는다(markdown+mermaid로 충분).
