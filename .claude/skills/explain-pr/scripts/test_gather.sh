#!/usr/bin/env bash
# gather.sh 동작 검증. 임시 git 리포를 만들어 번들 내용을 assert한다.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GATHER="$HERE/gather.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"
git init -q -b main
git config user.email t@t.io
git config user.name t
echo "hello" > a.txt
git add a.txt
git commit -qm "초기 커밋"
git checkout -q -b feat/x
echo "world" > b.txt
git add b.txt
git commit -qm "b.txt 추가"

OUT="$(bash "$GATHER" --base main)"

fail=0
assert_contains() {
  if ! grep -qF -- "$1" <<< "$OUT"; then echo "FAIL: 번들에 '$1' 없음"; fail=1; else echo "ok: $1"; fi
}
assert_contains "# explain-pr 컨텍스트 번들"
assert_contains "## 전체 diff"
assert_contains "b.txt"
assert_contains "b.txt 추가"
assert_contains "base: main"

if [[ $fail -ne 0 ]]; then echo "== 테스트 실패 =="; exit 1; fi
echo "== 모든 테스트 통과 =="
