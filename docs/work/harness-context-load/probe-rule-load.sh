#!/usr/bin/env bash
# probe-rule-load.sh — AC6·AC7(개정) 검증. claude -p 세션을 하나 띄우고, 그 세션 transcript 의
# attachment 기록에서 .claude/rules/{batch,http,testing}.md 가 언제 어떤 형태로 실렸는지 판정한다.
# 모델의 자기보고가 아니라 Claude Code 가 남긴 기록을 읽으므로 결정적이다.
# 사용법: probe-rule-load.sh <none|batch|http>   (worktree 루트에서 실행)
# 출력: "<모드> instructions=[...] nested_memory=[...]" 한 줄
set -euo pipefail

mode="${1:?none|batch|http}"
case "$mode" in
  none)  target="" ;;
  batch) target="apps/batch/src/main/kotlin/io/premiumspread/config/ClockConfig.kt" ;;
  http)  target="apps/api/src/main/kotlin/io/premiumspread/interfaces/api/premium/PremiumController.kt" ;;
  *) echo "ERROR: 알 수 없는 모드: $mode" >&2; exit 2 ;;
esac

out="$(mktemp)"
trap 'rm -f "$out"' EXIT

if [[ -z "$target" ]]; then
  printf '%s' "Reply OK." \
    | claude -p --model haiku --max-turns 1 --output-format json \
        --disallowedTools "Read,Bash,Grep,Glob,Edit,Write,Task" > "$out"
else
  [[ -f "$target" ]] || { echo "ERROR: 대상 파일 없음: $target" >&2; exit 2; }
  printf '%s' "Read $target with the Read tool, then reply DONE." \
    | claude -p --model haiku --max-turns 3 --output-format json \
        --allowedTools "Read" --disallowedTools "Bash,Grep,Glob,Edit,Write,Task" > "$out"
fi

python3 -I - "$out" "$mode" "$PWD" <<'PY'
import json, os, sys
out, mode, cwd = sys.argv[1:4]
sid = json.load(open(out))["session_id"]
proj = os.path.expanduser("~/.claude/projects/" + cwd.replace("/", "-").replace(".", "-"))
transcript = os.path.join(proj, sid + ".jsonl")
found = {"instructions": set(), "nested_memory": set()}
for line in open(transcript):
    a = json.loads(line).get("attachment") or {}
    kind = a.get("type")
    if kind not in found:
        continue
    blob = json.dumps(a, ensure_ascii=False)
    for name in ("batch", "http", "testing"):
        if f".claude/rules/{name}.md" in blob:
            found[kind].add(name)
print(mode, *(f"{k}={sorted(v)}" for k, v in found.items()))
PY
