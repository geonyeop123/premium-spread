#!/usr/bin/env bash
# gather.sh — explain-pr 재료수집기. 판단 없음, 순수 수집.
# 사용법: gather.sh [--base <branch>] [--pr <number>] [--out <file>]
# 출력: 이해문서 작성용 컨텍스트 번들(markdown)을 --out(기본 stdout)으로.
set -euo pipefail

BASE="" ; PR="" ; OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="${2:-}"; shift 2 ;;
    --pr)   PR="${2:-}";   shift 2 ;;
    --out)  OUT="${2:-}";  shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || { echo "ERROR: not a git repository" >&2; exit 1; }

REMOTE="$(git remote get-url origin 2>/dev/null || true)"
if [[ "$REMOTE" == *github.com* ]]; then PLATFORM="github"; CLI="gh"; else PLATFORM="gitlab"; CLI="glab"; fi

default_branch() {
  local d
  d="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)"
  if [[ -n "$d" ]]; then echo "$d"; return; fi
  if git show-ref --verify --quiet refs/heads/main; then echo main; return; fi
  echo master
}

# base 결정: 인자 > PR target(gh/glab) > dev > 기본브랜치
if [[ -z "$BASE" && -n "$PR" ]] && command -v "$CLI" >/dev/null 2>&1; then
  if [[ "$PLATFORM" == github ]]; then
    BASE="$(gh pr view "$PR" --json baseRefName -q .baseRefName 2>/dev/null || true)"
  else
    BASE="$(glab mr view "$PR" 2>/dev/null | sed -n 's/^target branch: *//p' | head -1 || true)"
  fi
fi
if [[ -z "$BASE" ]]; then
  if git show-ref --verify --quiet refs/heads/dev; then BASE="dev"; else BASE="$(default_branch)"; fi
fi

HEAD_BRANCH="$(git branch --show-current 2>/dev/null || echo HEAD)"
RANGE="${BASE}...HEAD"

render() {
  echo "# explain-pr 컨텍스트 번들"
  echo
  echo "- 생성: $(date -Iseconds)"
  echo "- 플랫폼: ${PLATFORM}"
  echo "- base: ${BASE} / head: ${HEAD_BRANCH}"
  echo "- diff 범위: ${RANGE}"
  echo
  echo "## PR/MR 메타"
  if [[ -n "$PR" ]] && command -v "$CLI" >/dev/null 2>&1; then
    if [[ "$PLATFORM" == github ]]; then
      gh pr view "$PR" --json number,title,url,headRefName,baseRefName 2>/dev/null || echo "(gh 조회 실패)"
    else
      glab mr view "$PR" 2>/dev/null || echo "(glab 조회 실패)"
    fi
  else
    echo "(PR 번호 미지정 또는 CLI 없음 — 생성 전 상태일 수 있음)"
  fi
  echo
  echo "## 변경 파일 목록"
  echo '```'
  git diff --stat "$RANGE" 2>/dev/null || echo "(diff 실패: base=${BASE})"
  echo '```'
  echo
  echo "## 커밋 로그"
  echo '```'
  git log --oneline --stat "${BASE}..HEAD" 2>/dev/null || echo "(log 실패)"
  echo '```'
  echo
  echo "## 전체 diff"
  echo '```diff'
  git diff "$RANGE" 2>/dev/null || echo "(diff 실패)"
  echo '```'
  echo
  echo "## 연결 가능성 있는 spec/plan 문서"
  echo '```'
  for d in docs/work docs/specs docs/plans docs/superpowers/specs docs/superpowers/plans; do
    [[ -d "$d" ]] && find "$d" -name '*.md' 2>/dev/null || true
  done | sort
  echo '```'
}

if [[ -n "$OUT" ]]; then render > "$OUT"; echo "wrote bundle to $OUT" >&2; else render; fi
