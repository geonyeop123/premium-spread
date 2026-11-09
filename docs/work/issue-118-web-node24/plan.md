# apps/web Node 런타임 20→24 전환과 jsdom 30 적용 — 구현 계획

> design: `docs/work/issue-118-web-node24/design.md` · dod: `docs/work/issue-118-web-node24/dod.md`
> 모든 명령은 worktree 루트(`.worktrees/chore-issue-118-web-node24`)에서 실행한다.
> 변경 파일: `apps/web/package.json`, `apps/web/package-lock.json`, `.github/workflows/quality-gate.yml`, `apps/web/Dockerfile`,
> `ci/quality-gate-contract-test.sh`, `.github/dependabot.yml`, `docs/runbooks/deployment.md`, `README.md`,
> `.ai/PROJECT_STATUS.md`, `docs/work/README.md`, 이 디렉터리 파일. 이 밖의 파일이 diff에 나오면 멈춘다.
> 앱 코드 변경이 없어 `test-strategy`의 Domain→Controller 순서는 해당 없다. 순서는 RED 기록 → 의존성 선언 → 런타임 선언 → 계약 검사 → Node 24 검증 → 문서다.
> 변경 이력: ⑥ 스펙 리뷰 9건 반영 — Task 4 `git status` 범위, Task 1 기준 lock을 `BASE`로 고정, 변형 M11·M12(C2·C3) 추가, Task 1 증거 기록 지시,
> Task 6 커밋 단계, Task 4 컨테이너 스크립트의 `set -e` 판정, audit 패키지 이름 집합 비교(Task 0 기준선·Task 4)

## 공통 — 상수, 임시 디렉터리, audit 요약 스크립트

각 Task를 시작할 때 셸마다 한 번 정의한다. 임시 파일은 worktree 밖에 둔다.

```bash
SCRATCH="${SCRATCH:-$(mktemp -d)}"; echo "SCRATCH=$SCRATCH"
BASE="$(git merge-base HEAD origin/dev)"; echo "BASE=$BASE"
NODE24_IMAGE='node:24-alpine@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8'
NODE20_IMAGE='node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293'
NODE22_IMAGE='node:22-alpine@sha256:0e1754758c2df4cfa3e68d59e2e1b7374bb7fd9ea6328eaaacf15cc934af8202'   # 변형 검증 전용
AUDITSUM='
const a = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
console.log("counts=" + JSON.stringify(a.metadata.vulnerabilities));
console.log("packages=" + Object.keys(a.vulnerabilities).sort().join(","));
const adv = new Set();
for (const v of Object.values(a.vulnerabilities)) for (const s of v.via) if (typeof s === "object") adv.add(`${s.name} ${s.severity} ${s.url}`);
console.log("advisories=" + [...adv].sort().join(" | "));
'
# 사용: node -e "$AUDITSUM" <audit.json>  (컨테이너에는 docker run -e AUDITSUM="$AUDITSUM" 으로 넘긴다)
```

- `BASE`는 dev와의 분기 커밋이다. Task 0의 기준선, Task 1의 기준 lock, Task 3의 M0, dod AC8이 같은 값을 쓴다.
- `NODE24_IMAGE`는 Task 2(Dockerfile)·Task 3(M5)·Task 4(검증)가 같은 문자열을 쓴다. `NODE22_IMAGE`는 Task 3의 M4·M5에만 쓴다. `NODE20_IMAGE`는 Task 0·Task 2에 쓴다.
- `AUDITSUM`은 Task 0(기준선)과 Task 4(R1)가 같은 형식으로 출력하게 한다. `packages=` 줄이 R1 비교 대상이다.

---

## Task 0 — RED·기준선 기록 (커밋 없음)

**파일**: `docs/work/issue-118-web-node24/dod.md` 증거 로그만

**실행**
```bash
grep -n "node-version" .github/workflows/quality-gate.yml                                   # AC3 RED
grep -n '^FROM' apps/web/Dockerfile                                                         # AC3 RED
npm --prefix apps/web pkg get engines 'devDependencies.@types/node' devDependencies.jsdom   # AC3 RED
node -e 'const p=JSON.parse(require("fs").readFileSync("apps/web/package-lock.json","utf8")).packages;
console.log(JSON.stringify({engines:p[""].engines??null,types:p["node_modules/@types/node"].version,jsdom:p["node_modules/jsdom"].version}))'   # AC4 RED
docker run --rm "$NODE20_IMAGE" node -v                                                     # AC6 RED (현 runtime 베이스)
grep -n 'Node.js 20+' README.md; grep -n 'Node 런타임(20)' docs/runbooks/deployment.md; grep -n '이 20이므로' .github/dependabot.yml   # AC7 RED
bash ci/quality-gate-contract-test.sh                                                       # R2 기준선
bash docs/check-documentation.sh                                                            # R3 기준선
# R1 기준선: dev 상태(BASE)를 dev 런타임(Node 20) 컨테이너에서 설치한 뒤 audit 요약
git archive "$BASE" apps/web ci | docker run --rm -i -e AUDITSUM="$AUDITSUM" "$NODE20_IMAGE" sh -c '
  mkdir /work && tar -xf - -C /work && cd /work
  npm --prefix apps/web ci --include=optional > /dev/null 2>&1 && echo "baseline-ci exit=0"
  npm --prefix apps/web audit --json > /tmp/audit.json || true
  node -e "$AUDITSUM" /tmp/audit.json
' | tee "$SCRATCH/audit-baseline.txt"
```

**예상 결과**
- AC3 RED: `189:          node-version: '20'`, Dockerfile 1·8행 `node:20-alpine@sha256:fb4c…2293`, `pkg get` 출력에 `engines` 없음(`{}` 또는 키 누락), `"^20"`, `"^27.0.0"`
- AC4 RED: `{"engines":null,"types":"20.19.43","jsdom":"27.4.0"}`
- AC6 RED: `v20.` 으로 시작
- AC7 RED: 세 grep이 각각 1줄씩 나온다
- R2·R3 exit 0
- R1 기준선: `baseline-ci exit=0`. `counts={"info":0,"low":2,"moderate":11,"high":0,"critical":0,"total":13}`.
  `packages=`(패키지 이름 목록, 그대로 기록). `advisories=deepmerge low https://github.com/advisories/GHSA-gcqx-w48m-w2h4 | minimatch moderate https://github.com/advisories/GHSA-g3hv-75wg-m249`

dod `### AC3`, `### AC4`, `### AC6`, `### AC7`, `### R 기준선`에 `[RED] <ISO 시각> <HEAD sha>` 형식으로 붙인다. `### R 기준선`에는 `packages=` 줄 전문을 넣는다.

---

## Task 1 — package.json·lockfile (D2·D3·D4·D5)

**파일**: `apps/web/package.json`, `apps/web/package-lock.json`

**계약 1 — `apps/web/package.json` 최종 형태 (전문)**. 바뀌는 곳은 `engines` 블록 추가(`"private"` 바로 다음), `@types/node`, `jsdom` 세 곳이다. 그 외 바이트는 그대로다.

```json
{
  "name": "web",
  "version": "0.1.0",
  "private": true,
  "engines": {
    "node": "^24.15.0"
  },
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "eslint",
    "test": "vitest run"
  },
  "dependencies": {
    "axios": "^1.21.0",
    "class-variance-authority": "^0.7.1",
    "clsx": "^2.1.1",
    "lightweight-charts": "^5.2.1",
    "lucide-react": "^0.575.0",
    "next": "16.4.5",
    "radix-ui": "^1.8.1",
    "react": "19.3.1",
    "react-dom": "19.3.1",
    "tailwind-merge": "^3.7.0"
  },
  "devDependencies": {
    "@tailwindcss/postcss": "^4",
    "@testing-library/jest-dom": "^6.9.1",
    "@testing-library/react": "^16.3.3",
    "@types/node": "^24.19.2",
    "@types/react": "^19",
    "@types/react-dom": "^19",
    "@vitejs/plugin-react": "^6.2.1",
    "eslint": "^9",
    "eslint-config-next": "16.4.5",
    "jsdom": "^30.1.3",
    "shadcn": "^4.23.0",
    "tailwindcss": "^4",
    "tw-animate-css": "^1.4.0",
    "typescript": "^5",
    "vitest": "^4.1.12"
  }
}
```

형식 제약(Task 3 검사가 읽는다): `engines` 여는 줄은 정확히 `  "engines": {`, 값 줄은 정확히 `    "node": "^24.15.0"`(끝 쉼표 없음), 닫는 줄은 `  },`.
`@types/node` 줄은 4칸 들여쓰기 한 줄 `"키": "값",` 형태다.

**계약 2 — lockfile 판정 기준 (전문)**. 아래 검사 스크립트 `LOCKCHECK`의 출력이 다음 두 줄이어야 한다. 첫 줄은 정확히 같아야 한다. 둘째 줄의 `<N>`은 1 이상이고 실행 결과를 그대로 기록한다. `nonDev=[]`는 정확히 같아야 한다.

```
{"engines":{"node":"^24.15.0"},"types":"^24.19.2","jsdom":"^30.1.3","typesV":"24.19.2","jsdomV":"30.1.3","undiciV":"7.30.1","prodDepsSame":true}
changed=<N> nonDev=[]
```

```bash
LOCKCHECK='
const fs = require("fs");
const [b, a] = process.argv.slice(1).map((f) => JSON.parse(fs.readFileSync(f, "utf8")).packages);
const r = a[""];
console.log(JSON.stringify({
  engines: r.engines ?? null, types: r.devDependencies["@types/node"], jsdom: r.devDependencies.jsdom,
  typesV: a["node_modules/@types/node"]?.version, jsdomV: a["node_modules/jsdom"]?.version,
  undiciV: a["node_modules/undici"]?.version,
  prodDepsSame: JSON.stringify(b[""].dependencies) === JSON.stringify(r.dependencies),
}));
const isDev = (e) => e === undefined || e.dev === true || e.devOptional === true;
let n = 0; const nonDev = [];
for (const k of new Set([...Object.keys(b), ...Object.keys(a)])) {
  if (k === "" || JSON.stringify(b[k]) === JSON.stringify(a[k])) continue;
  n++; if (!(isDev(b[k]) && isDev(a[k]))) nonDev.push(k);
}
console.log(`changed=${n} nonDev=${JSON.stringify(nonDev)}`);
'
# 사용: node -e "$LOCKCHECK" <before-lock.json> <after-lock.json>
```

`nonDev=[]`는 "바뀐 lock 항목이 전부 devDependency 트리에 있다"는 뜻이다. production 트리(next·react 등)가 바뀌지 않았음을 보인다(R4).
기준 lock은 항상 `BASE`의 lockfile이다. 그래서 Task 1을 다시 실행하거나 ⑩에서 재검증해도 같은 비교가 된다.

**실행**
```bash
# 1) package.json에 engines 블록만 손으로 넣는다(계약 1의 5~7행). @types/node·jsdom 값은 2)가 쓴다.
# 2) lockfile만 다시 해결한다. node:24-alpine 컨테이너의 npm 11.19.1에서 실행한다(design D5 개정: 로컬 npm 10.8.2는 libc 필드를 지운다).
#    node_modules 설치·lockfile 전체 재생성·audit fix 금지.
docker run --rm -v "$PWD/apps/web":/app -w /app -u "$(id -u):$(id -g)" -e HOME=/tmp \
  node:24-alpine@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8 \
  sh -c 'node -v; npm -v; npm install --package-lock-only --ignore-scripts --no-audit --no-fund jsdom@^30.1.3 @types/node@^24'   # v24.22.2 / 11.19.1 예상
grep -c '"libc"' apps/web/package-lock.json                                         # 18 (BASE와 같은 수)
# 3) 계약 1·2와 대조한다.
git diff -- apps/web/package.json
git show "$BASE:apps/web/package-lock.json" > "$SCRATCH/lock-before.json"
node -e "$LOCKCHECK" "$SCRATCH/lock-before.json" apps/web/package-lock.json
git diff --stat -- apps/web
# 4) 두 파일만 커밋한다.
git add apps/web/package.json apps/web/package-lock.json
git commit
```

**예상 결과**
- `npm install` exit 0. Node 24이므로 `EBADENGINE` 경고는 없다. `"libc"` 개수가 BASE와 같은 18이다
- 사전 실측(2026-11-09, 개정 근거): 같은 입력에서 `changed=37 nonDev=[]`, `prodDepsSame:true`, libc 18 보존. 로컬 npm 10.8.2는 `changed=55`, libc 0, `nonDev`에 `@next/swc-linux-*` 4건
- `git diff -- apps/web/package.json`이 계약 1과 같다: `engines` 3줄 추가, `"^20"`→`"^24.19.2"`, `"^27.0.0"`→`"^30.1.3"`
- `LOCKCHECK` 출력이 계약 2와 같다(`changed`≥1, `nonDev=[]`)
- `git diff --stat -- apps/web`에 두 파일만 있다

**다르면**: 커밋하지 말고 멈춘다. `typesV`·`jsdomV`가 더 높게 나왔다면 registry에 새 patch가 나온 것이다. 실제 값을 dod `## 변경 요청`에 적고 승인을 받는다. `nonDev`가 비어 있지 않으면 production 트리가 바뀐 것이다. 목록을 그대로 오케스트레이터에 보고한다. `engines`가 `null`이면 1)을 빠뜨린 것이다.

**증거 기록**: 커밋 뒤 dod `### AC4`에 `[GREEN] <ISO 시각> <Task 1 커밋 sha> LOCKCHECK` 줄과 출력 두 줄 전문을 붙인다. 같은 출력의 `prodDepsSame:true`·`nonDev=[]`를 `### R2·R3·R4`의 R4 줄로 붙인다.

**커밋 메시지**: `chore: apps/web jsdom 30·@types/node 24 적용과 engines 추가 (#118)`. 본문에 "lockfile은 node:24-alpine npm 11.19.1 --package-lock-only 재해결, libc 필드 보존, 변경 항목 전부 dev 트리"를 적는다.

---

## Task 2 — CI와 Dockerfile의 Node 24 (D1·D8)

**파일**: `.github/workflows/quality-gate.yml`, `apps/web/Dockerfile`

**계약 1 — `.github/workflows/quality-gate.yml` 189행**. 이 한 줄만 바뀐다.

교체 전: `          node-version: '20'`
교체 후: `          node-version: '24'`

(10칸 들여쓰기, 작은따옴표 유지. Task 3 검사가 따옴표 친 정수 major만 허용한다.)

**계약 2 — `apps/web/Dockerfile` 1행·8행 (전문)**. 이 두 줄만 바뀐다.

```dockerfile
FROM node:24-alpine@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8 AS builder
```
```dockerfile
FROM node:24-alpine@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8
```

**실행**
```bash
sed -i "189s/^          node-version: '20'$/          node-version: '24'/" .github/workflows/quality-gate.yml
sed -i "s|^FROM ${NODE20_IMAGE}|FROM ${NODE24_IMAGE}|" apps/web/Dockerfile
git diff -- .github/workflows/quality-gate.yml apps/web/Dockerfile
grep -n "node-version" .github/workflows/quality-gate.yml
grep -n '^FROM' apps/web/Dockerfile
bash ci/quality-gate-contract-test.sh
git add .github/workflows/quality-gate.yml apps/web/Dockerfile
git commit
```

**예상 결과**
- diff는 3줄 교체뿐이다(workflow 1줄, Dockerfile 2줄). 파일 줄 수는 그대로다(Task 3 M11이 30·189행 번호를 쓴다)
- `189:          node-version: '24'` 한 줄
- `1:FROM node:24-alpine@sha256:a34ab6d7…67c8 AS builder`, `8:FROM node:24-alpine@sha256:a34ab6d7…67c8`
- 계약 테스트 exit 0 (아직 Node 블록 없음)

**커밋 메시지**: `chore: apps/web CI·Docker 런타임 Node 24로 전환 (#118)`

---

## Task 3 — Node major 일치 계약 검사 (D6·D6-b, AC1·AC2)

**파일**: `ci/quality-gate-contract-test.sh`

**계약 1 — 추가할 블록 (전문)**. 위치: #98 블록의 마지막 `fail "apps/web next (...) and eslint-config-next (...) must share one version"` 줄 다음, 빈 줄 하나를 두고, 최종 `echo "quality gate and supply-chain contracts verified"` 앞에 빈 줄 하나를 둔다. `web_package`는 #98 블록이 이미 정의한다. `workflow_job_block`은 파일 상단 함수다.

```bash
# apps/web Node 런타임(#118): CI 6번 잡 node-version, Dockerfile 두 stage, engines.node, @types/node 는 같은 Node major 를 쓴다.
# 한 곳만 바뀌면 #114(jsdom 30 이 Node 20 CI 에서 실패)처럼 CI 에서만 깨진다. 갱신 절차는 docs/runbooks/deployment.md 에 있다.
web_dockerfile="${root_dir}/apps/web/Dockerfile"
mapfile -t ci_node_versions < <(sed -nE 's/^[[:space:]]+node-version:[[:space:]]*(.*)$/\1/p' "${quality_workflow}")
ci_node_pattern="^'([0-9]+)'$"
[[ "${#ci_node_versions[@]}" -eq 1 && "${ci_node_versions[0]}" =~ ${ci_node_pattern} ]] ||
  fail "Quality Gate must pin exactly one quoted major node-version (found: ${ci_node_versions[*]:-none})"
ci_node_major="${BASH_REMATCH[1]}"
grep -Fxq "          node-version: '${ci_node_major}'" <<< "$(workflow_job_block dependency-security)" ||
  fail "node-version must be set by the dependency-security job"
mapfile -t web_from_lines < <(grep -E '^FROM[[:space:]]' "${web_dockerfile}" || true)
web_from_pattern='^FROM node:([0-9]+)-alpine@sha256:[0-9a-f]{64}( AS [a-z]+)?$'
[[ "${#web_from_lines[@]}" -eq 2 ]] ||
  fail "apps/web Dockerfile must have exactly two FROM stages (found ${#web_from_lines[@]})"
for web_from_line in "${web_from_lines[@]}"; do
  [[ "${web_from_line}" =~ ${web_from_pattern} ]] ||
    fail "apps/web Dockerfile stage must pin node:<major>-alpine@sha256:<64 hex>: ${web_from_line}"
done
[[ "${web_from_lines[0]%% AS *}" == "${web_from_lines[1]%% AS *}" ]] ||
  fail "apps/web Dockerfile builder and runtime stages must use the same node image"
docker_node_major="$(sed -E 's/^FROM node:([0-9]+)-.*$/\1/' <<< "${web_from_lines[0]}")"
web_engines_block="$(
  awk '
    $0 == "  \"engines\": {" { inside = 1; next }
    inside && $0 ~ /^  [}]/ { exit }
    inside { print }
  ' "${web_package}"
)"
engines_node_pattern='^    "node": "\^([0-9]+)\.[0-9]+\.[0-9]+"$'
[[ "${web_engines_block}" =~ ${engines_node_pattern} ]] ||
  fail "apps/web package.json engines must be exactly one \"node\": \"^<major>.<minor>.<patch>\" entry"
engines_node_major="${BASH_REMATCH[1]}"
types_node_major="$(sed -nE 's/^    "@types\/node": "\^([0-9]+)(\.[0-9]+){0,2}",?$/\1/p' "${web_package}")"
[[ -n "${types_node_major}" && "${ci_node_major}" == "${docker_node_major}" &&
  "${ci_node_major}" == "${engines_node_major}" && "${ci_node_major}" == "${types_node_major}" ]] ||
  fail "apps/web Node major must match: CI node-version ${ci_node_major}, Dockerfile ${docker_node_major}, engines.node ${engines_node_major}, @types/node ${types_node_major:-missing}"
```

**검사 규칙 요약** (블록과 1:1, 리뷰용)

| # | 대상 | 규칙 | 실패 메시지 (`quality gate contract failed: ` 뒤) |
|---|---|---|---|
| C1 | `quality-gate.yml` 전체 | `node-version:` 줄이 정확히 1개이고 값이 `^'([0-9]+)'$` | `Quality Gate must pin exactly one quoted major node-version (found: <값들 또는 none>)` |
| C2 | `dependency-security` 잡 블록 | `          node-version: '<major>'` 줄이 그 잡 안에 있다 | `node-version must be set by the dependency-security job` |
| C3 | `apps/web/Dockerfile` | `^FROM[[:space:]]` 줄이 정확히 2개 | `apps/web Dockerfile must have exactly two FROM stages (found <N>)` |
| C4 | 같은 파일 각 `FROM` 줄 | `^FROM node:([0-9]+)-alpine@sha256:[0-9a-f]{64}( AS [a-z]+)?$` | `apps/web Dockerfile stage must pin node:<major>-alpine@sha256:<64 hex>: <그 줄>` |
| C5 | 두 `FROM` 줄 | ` AS ` 앞 이미지 참조 문자열이 같다 | `apps/web Dockerfile builder and runtime stages must use the same node image` |
| C6 | `package.json` `engines` 블록 | 블록 내용이 정확히 한 줄 `^    "node": "\^([0-9]+)\.[0-9]+\.[0-9]+"$` | `apps/web package.json engines must be exactly one "node": "^<major>.<minor>.<patch>" entry` |
| C7 | 네 major | `@types/node` major가 있고 CI == Dockerfile == engines == `@types/node` | `apps/web Node major must match: CI node-version <a>, Dockerfile <b>, engines.node <c>, @types/node <d 또는 missing>` |

**계약 2 — 변형 목록 (전문)**. 각 변형은 대상 파일 3개(`.github/workflows/quality-gate.yml`, `apps/web/Dockerfile`, `apps/web/package.json`)를 바꾼 뒤 계약 테스트를 실행하고, 원본으로 되돌린다. 통과 조건은 exit 1이고 stderr에 "기대 메시지 조각"이 들어 있는 것이다. 13종으로 C1~C7을 모두 덮는다.

| # | 변형 (함수 본문) | 기대 메시지 조각 | 잡는 규칙 |
|---|---|---|---|
| M0 | `for f in "${targets[@]}"; do git show "$BASE:$f" > "$f"; done` (dev 상태 복원 = 구현 전 RED) | `engines must be exactly one` | C6 |
| M1 | `sed -i "s/node-version: '24'/node-version: '22'/" .github/workflows/quality-gate.yml` | `Node major must match` | C7 |
| M2 | `sed -i "s/node-version: '24'/node-version: 24/" .github/workflows/quality-gate.yml` | `exactly one quoted major node-version` | C1 |
| M3 | `sed -i "s/node-version: '24'/node-version: '24.x'/" .github/workflows/quality-gate.yml` | `exactly one quoted major node-version` | C1 |
| M4 | `sed -i "8s|^FROM .*\$|FROM ${NODE22_IMAGE}|" apps/web/Dockerfile` | `builder and runtime stages must use the same node image` | C5 |
| M5 | `sed -i "s|^FROM ${NODE24_IMAGE}|FROM ${NODE22_IMAGE}|" apps/web/Dockerfile` | `Node major must match` | C7 |
| M6 | `sed -i '1s/@sha256:[0-9a-f]*//' apps/web/Dockerfile` | `must pin node:<major>-alpine@sha256` | C4 |
| M7 | `sed -i 's/"node": "\^24\.15\.0"/"node": ">=24.15.0"/' apps/web/package.json` | `engines must be exactly one` | C6 |
| M8 | `sed -i 's/"node": "\^24\.15\.0"/"node": "^22.22.2"/' apps/web/package.json` | `Node major must match` | C7 |
| M9 | `sed -i 's/"@types\/node": "\^24\.19\.2"/"@types\/node": "^22"/' apps/web/package.json` | `Node major must match` | C7 |
| M10 | `sed -i '/^  "engines": {$/,/^  },$/d' apps/web/package.json` | `engines must be exactly one` | C6 |
| M11 | `sed -i -e '189d' -e "30a\\          node-version: '24'" .github/workflows/quality-gate.yml` (6번 잡 189행의 `node-version` 줄을 지우고, 1번 잡 `compile-architecture`의 `actions/setup-java` step `with:` 아래 30행 `          java-version: '21'` 다음으로 옮긴다) | `node-version must be set by the dependency-security job` | C2 |
| M12 | `sed -i '$a FROM scratch' apps/web/Dockerfile` (파일 끝에 세 번째 `FROM` 줄 추가) | `apps/web Dockerfile must have exactly two FROM stages` | C3 |

M11 위치 근거: `quality-gate.yml` 27~30행은 `compile-architecture` 잡의 `- uses: actions/setup-java@… # v4.7.1` / `with:` / `distribution: temurin` / `java-version: '21'`이다. 이 잡에는 `setup-node`가 없어서 실재하는 `with:` 블록(setup-java) 아래로 옮긴다. 옮긴 뒤에도 `node-version` 줄은 하나이고 값이 `'24'`라 C1은 통과하고 C2만 실패한다. 행 번호는 Task 2 이후 기준이다(Task 2는 줄 수를 바꾸지 않는다). 실행 전에 `sed -n '30p;189p'`로 두 줄을 확인한다.
M12: 세 번째 `FROM` 줄 때문에 C3(개수 2)에서 먼저 실패한다. C1·C2는 workflow를 건드리지 않아 통과한다.

**실행**
```bash
# 1) 계약 1 블록을 넣는다.
bash -n ci/quality-gate-contract-test.sh
bash ci/quality-gate-contract-test.sh                                        # AC1 GREEN
sed -n '30p;189p' .github/workflows/quality-gate.yml                         # M11 전제: "          java-version: '21'" / "          node-version: '24'"
# 2) 변형 검증 (set -e 없이 대화형 셸에서)
targets=(.github/workflows/quality-gate.yml apps/web/Dockerfile apps/web/package.json)
m0()  { for f in "${targets[@]}"; do git show "$BASE:$f" > "$f"; done; }
m1()  { sed -i "s/node-version: '24'/node-version: '22'/" .github/workflows/quality-gate.yml; }
m2()  { sed -i "s/node-version: '24'/node-version: 24/" .github/workflows/quality-gate.yml; }
m3()  { sed -i "s/node-version: '24'/node-version: '24.x'/" .github/workflows/quality-gate.yml; }
m4()  { sed -i "8s|^FROM .*\$|FROM ${NODE22_IMAGE}|" apps/web/Dockerfile; }
m5()  { sed -i "s|^FROM ${NODE24_IMAGE}|FROM ${NODE22_IMAGE}|" apps/web/Dockerfile; }
m6()  { sed -i '1s/@sha256:[0-9a-f]*//' apps/web/Dockerfile; }
m7()  { sed -i 's/"node": "\^24\.15\.0"/"node": ">=24.15.0"/' apps/web/package.json; }
m8()  { sed -i 's/"node": "\^24\.15\.0"/"node": "^22.22.2"/' apps/web/package.json; }
m9()  { sed -i 's/"@types\/node": "\^24\.19\.2"/"@types\/node": "^22"/' apps/web/package.json; }
m10() { sed -i '/^  "engines": {$/,/^  },$/d' apps/web/package.json; }
m11() { sed -i -e '189d' -e "30a\\          node-version: '24'" .github/workflows/quality-gate.yml; }
m12() { sed -i '$a FROM scratch' apps/web/Dockerfile; }
mutate() {  # $1=id $2=기대 메시지 조각 $3=변형 함수
  local id="$1" expect="$2" fn="$3" rc f
  for f in "${targets[@]}"; do cp "$f" "$SCRATCH/orig.$(basename "$f")"; done
  "$fn"
  if git diff --quiet -- "${targets[@]}"; then
    echo "$id NOT-APPLIED"
  else
    bash ci/quality-gate-contract-test.sh > /dev/null 2> "$SCRATCH/$id.err"; rc=$?
    if [[ $rc -eq 1 ]] && grep -Fq -- "$expect" "$SCRATCH/$id.err"; then echo "$id RED ok"
    else echo "$id FAIL rc=$rc $(cat "$SCRATCH/$id.err")"; fi
  fi
  for f in "${targets[@]}"; do cp "$SCRATCH/orig.$(basename "$f")" "$f"; done
}
mutate M0  'engines must be exactly one' m0
mutate M1  'Node major must match' m1
mutate M2  'exactly one quoted major node-version' m2
mutate M3  'exactly one quoted major node-version' m3
mutate M4  'builder and runtime stages must use the same node image' m4
mutate M5  'Node major must match' m5
mutate M6  'must pin node:<major>-alpine@sha256' m6
mutate M7  'engines must be exactly one' m7
mutate M8  'Node major must match' m8
mutate M9  'Node major must match' m9
mutate M10 'engines must be exactly one' m10
mutate M11 'node-version must be set by the dependency-security job' m11
mutate M12 'apps/web Dockerfile must have exactly two FROM stages' m12
git diff --quiet -- "${targets[@]}" && echo "targets restored"
bash ci/quality-gate-contract-test.sh                                        # 복원 뒤 다시 GREEN
git add ci/quality-gate-contract-test.sh
git commit
```

**예상 결과**
- `bash -n` exit 0. 첫 실행 exit 0, `quality gate and supply-chain contracts verified`
- `sed -n '30p;189p'`가 `          java-version: '21'`, `          node-version: '24'` 두 줄. 다르면 M11을 실행하지 말고 멈춘다
- `M0 RED ok` ~ `M12 RED ok` 13줄. `NOT-APPLIED`·`FAIL`이 하나라도 있으면 멈춘다. 정규식을 느슨하게 고치지 말고 실패 줄을 그대로 보고한다
- `targets restored`, 마지막 실행 exit 0
- `git diff --stat HEAD~1`(커밋 뒤)에 `ci/quality-gate-contract-test.sh` 한 파일

dod `### AC1`(첫 실행 GREEN, M0을 RED로), `### AC2`(13줄 결과)에 기록한다.

**커밋 메시지**: `ci: apps/web Node major 일치 계약 검사 추가 (#118)`

---

## Task 4 — Node 24 환경 검증 (AC4·AC5·AC6, R1)

**파일**: dod 증거 로그만. dod 디렉터리 밖 worktree 파일은 바뀌면 안 된다.

커밋된 HEAD를 `git archive`로 컨테이너에 넣는다. 호스트 `node_modules`(glibc 바이너리, 소유자)와 섞이지 않고, 증거가 SHA에 묶인다.
컨테이너 스크립트는 `set -e`로 설치·lint·test·build 실패에서 멈춘다. 그 뒤의 `npm ls`·audit 단계는 실패해도 멈추지 않고, **출력 줄로 판정한다**.

**실행**
```bash
HEAD_SHA="$(git rev-parse --short HEAD)"; echo "HEAD=$HEAD_SHA"
# 1) Node 24 컨테이너: 설치·lint·test·build·ls·audit (AC4·AC5·R1)
git archive HEAD apps/web ci | docker run --rm -i -e AUDITSUM="$AUDITSUM" "$NODE24_IMAGE" sh -c '
  set -e
  apk add --no-cache bash > /dev/null
  mkdir /work && tar -xf - -C /work && cd /work
  node -v; npm -v
  npm --prefix apps/web ci --include=optional
  npm --prefix apps/web run lint
  npm --prefix apps/web run test
  npm --prefix apps/web run build
  npm --prefix apps/web ls jsdom @types/node undici --all || echo "ls-targets nonzero"
  npm --prefix apps/web ls --all > /tmp/ls.txt 2>&1 || true
  echo "invalid=$(grep -c invalid /tmp/ls.txt || true)"
  bash ci/run-npm-audit.sh && echo "audit-gate exit=0"
  npm --prefix apps/web audit --json > /tmp/audit.json || true
  node -e "$AUDITSUM" /tmp/audit.json
' 2>&1 | tee "$SCRATCH/node24-verify.log"
diff <(grep '^packages=' "$SCRATCH/audit-baseline.txt") <(grep '^packages=' "$SCRATCH/node24-verify.log") && echo "audit packages same as baseline"
# 2) 런타임 이미지 (AC6)
docker build -f apps/web/Dockerfile -t premium-spread-web:issue118 .
docker run --rm --entrypoint node premium-spread-web:issue118 -v
docker run -d --name ps-web-118 -p 127.0.0.1:13118:3000 premium-spread-web:issue118
sleep 5
curl -s -o /dev/null -w '/ %{http_code}\n' http://127.0.0.1:13118/
curl -s -o /dev/null -w '/login %{http_code}\n' http://127.0.0.1:13118/login
docker logs ps-web-118 2>&1 | tail -n 5
docker rm -f ps-web-118
git status --porcelain -- . ':!docs/work/issue-118-web-node24'
```

**예상 결과** (출력 줄 기준 판정)
- 1): `v24.22.2` / `11.19.1`. `npm ci`·lint·build exit 0(중간에 멈추지 않는다). test `Tests  10 passed (10)`. build 라우트 6개(`/`, `/_not-found`, `/login`, `/register`, `/trackings`, `/trackings/[id]`).
  `npm ls`에 `jsdom@30.1.3`, `@types/node@24.19.2`, `undici@7.30.1`(jsdom 하위 dedupe)이 있고 `ls-targets nonzero` 줄이 없다. `invalid=0`.
  `audit-gate exit=0` 줄이 있다. `counts={"info":0,"low":2,"moderate":11,"high":0,"critical":0,"total":13}`.
  `advisories=deepmerge low https://github.com/advisories/GHSA-gcqx-w48m-w2h4 | minimatch moderate https://github.com/advisories/GHSA-g3hv-75wg-m249`
- `audit packages same as baseline` (Task 0 `packages=` 줄과 같다)
- 2): `docker build` exit 0. `node -v` → `v24.22.2`. `/ 200`, `/login 200`. 로그에 `Ready`
- `git status --porcelain -- . ':!docs/work/issue-118-web-node24'` 빈 출력 (dod 증거 로그 미커밋분은 제외 대상)

**실패하면**: 1)에서 `set -e`로 멈춘 단계를 기록하고 멈춘다. test 실패가 `undici` 관련이면 #114와 같은 원인이므로 `node -v`부터 확인한다. `audit-gate exit=0` 줄이 없거나 `packages=`가 기준선과 다르면 advisory가 바뀐 것이다. dod `## 변경 요청`에 적는다. 13118 포트가 쓰이고 있으면 다른 빈 포트로 바꾸고 그 값을 기록한다(컨테이너 쪽 3000은 그대로).

dod `### AC4`·`### AC5`·`### AC6`·`### R1`에 `[GREEN] <ISO 시각> <HEAD_SHA>`로 붙인다.

---

## Task 5 — 문서 (AC7·AC8, R2·R3)

**파일**: `.github/dependabot.yml`, `docs/runbooks/deployment.md`, `README.md`

**계약 1 — `.github/dependabot.yml` 18행**. 이 한 줄만 바뀐다.

교체 전:
```yaml
      # Node 런타임(Dockerfile·CI)이 20이므로 타입 정의 major 는 런타임을 올릴 때 함께 올린다.
```
교체 후:
```yaml
      # Node 런타임(Dockerfile·CI·engines)이 24이므로 타입 정의 major 는 런타임을 올릴 때 함께 올린다.
```

**계약 2 — `docs/runbooks/deployment.md`** "apps/web 의존성 갱신 (Dependabot)" 절.

(a) 77행 한 줄 교체.

교체 전:
```markdown
  major는 Node 런타임(20)을 올릴 때 함께 올리므로 제외했다.
```
교체 후:
```markdown
  major는 Node 런타임(24)을 올릴 때 함께 올리므로 제외했다(아래 "Node 런타임 major 갱신").
```

(b) "next 버전 불일치" 항목(현재 82~84행, `--save-exact`로 맞춘다.`로 끝남) 바로 다음, "overrides 정책" 항목 앞에 새 항목을 넣는다 (전문).

```markdown
- **Node 런타임 major 갱신.** `apps/web`의 Node major는 다음 다섯 곳이 같아야 하며 한 PR에서 함께 바꾼다. CI 6번 잡
  `actions/setup-node`의 `node-version`, `apps/web/Dockerfile` builder·runtime 두 stage의 `node:<major>-alpine@sha256:<digest>`,
  `apps/web/package.json`의 `engines.node`와 `@types/node`, 루트 `README.md`의 사전 요구사항이다. 앞의 네 곳은
  `ci/quality-gate-contract-test.sh`가 major 일치와 digest 고정을 검사해 1번 잡을 실패시킨다(#118). digest는
  `docker buildx imagetools inspect node:<major>-alpine`의 `Digest`로 정하고, 그 이미지 안에서 `npm ci --include=optional`·lint·
  test·build를 다시 돌린다. `engines`는 `engine-strict`가 꺼져 있어 맞지 않는 Node에서 경고만 낸다. 현재는 Node 24
  (`^24.15.0`, 2028-04 EOL)다. Node 20에서 jsdom 30이 `undici` 오류로 실패한 #114가 전환 계기였다.
```

**계약 3 — `README.md` 101행**. 이 한 줄만 바뀐다.

교체 전: `- Node.js 20+`
교체 후: `- Node.js 24 (apps/web, ^24.15.0)`

**실행**
```bash
bash docs/check-documentation.sh                                                            # R3
git diff -- .github/dependabot.yml docs/runbooks/deployment.md README.md
grep -n 'Node.js 20+' README.md; grep -n 'Node 런타임(20)' docs/runbooks/deployment.md; grep -n '이 20이므로' .github/dependabot.yml   # AC7: 출력 없음
grep -c 'Node.js 24 (apps/web, ^24.15.0)' README.md                                         # 1
grep -c 'Node 런타임(24)' docs/runbooks/deployment.md                                        # 1
grep -c '\*\*Node 런타임 major 갱신.\*\*' docs/runbooks/deployment.md                          # 1
grep -c 'engines)이 24이므로' .github/dependabot.yml                                          # 1
bash ci/quality-gate-contract-test.sh                                                       # R2 (dependabot.yml 계약 줄 보존)
```

**예상 결과**
- R3 exit 0 (`documentation check passed (20 files, 15 required paths)`)
- diff가 계약 1~3과 같다
- 옛 문구 grep 3개 모두 출력 없음, 새 문구 grep 4개 모두 `1`
- R2 exit 0

dod `### AC7`과 `### R2·R3·R4`(R2·R3 줄)에 `[GREEN] <ISO 시각> <HEAD sha>(+문서 변경)`로 붙인다.

**커밋**: `docs: apps/web Node 24 전환 문서 갱신 (#118)`. Task 0·1·3·4의 dod 증거 로그도 이 커밋에 넣는다.

---

## Task 6 — 상태·인덱스 문서 (⑪ tech-docs)

**파일**: `.ai/PROJECT_STATUS.md`, `docs/work/README.md`, `docs/work/issue-118-web-node24/README.md`(신규), `docs/work/issue-118-web-node24/understanding.md`(신규, tech-docs 관례)

**계약 1 — `.ai/PROJECT_STATUS.md` 진행 현황**. `- apps/web overrides 재검토(#97, ...)` 항목(현재 131~133행) 바로 뒤에 추가한다. tech-docs가 상태 괄호 문구를 실제 진행에 맞게 다듬을 수 있다. 사실 내용은 바꾸지 않는다.

```markdown
- apps/web Node 24 전환(#118, 구현·로컬 검증 완료, PR CI 대기): CI 6번 잡과 `apps/web/Dockerfile` 두 stage를 Node 24
  (`node:24-alpine`, digest 고정)로 올리고 `engines.node` `^24.15.0`·`@types/node` `^24`·jsdom 30을 적용했다. 네 곳의 Node
  major가 갈리면 `ci/quality-gate-contract-test.sh`가 1번 잡을 실패시킨다. 갱신 절차는 [runbook](../docs/runbooks/deployment.md)을 따른다.
```

Known Issue 8은 바꾸지 않는다(design §5).

**계약 2 — `docs/work/README.md`**. 마지막 줄 `- [apps/web npm overrides 재검토](issue-97-web-npm-overrides/README.md)` 다음에 추가한다.

```markdown
- [apps/web Node 24 전환과 jsdom 30](issue-118-web-node24/README.md)
```

**계약 3 — `docs/work/issue-118-web-node24/README.md`** (요건만): 제목 `# apps/web Node 24 전환과 jsdom 30 (issue #118)`, 두 문장 요약, `## 문서` 아래 design·plan·dod·understanding 링크와 "이슈: #118 (계기 PR #114, 선행 #98·#97)". 형식은 `docs/work/issue-97-web-npm-overrides/README.md`를 따른다.

**실행**
```bash
bash docs/check-documentation.sh                                                            # R3
test -f docs/work/issue-118-web-node24/README.md && echo ok
git add .ai/PROJECT_STATUS.md docs/work/README.md docs/work/issue-118-web-node24/README.md docs/work/issue-118-web-node24/understanding.md
git commit
```

**예상 결과**: R3 exit 0. `.ai/PROJECT_STATUS.md`의 새 상대 링크가 해석된다. `ok`. 커밋에 위 4개 파일만 있다

**커밋 메시지**: `docs: #118 상태·인덱스 갱신`

---

## Task 7 — PR과 CI (AC9) — 오케스트레이터 단계

- 최종 커밋 SHA에서 Task 4의 1)을 다시 실행해 R1 집계를 실행 시각과 함께 dod에 남긴다(코드·lockfile이 Task 4 이후 바뀌지 않았으면 Task 4 결과 SHA와 "이후 문서만"을 적는다)
- PR 본문: 바꾼 네 곳의 값, 계약 검사 규칙 C1~C7, 변형 13종 결과, Node 24 컨테이너·이미지 스모크 결과, `engines`가 경고만 낸다는 점. `Closes #118`. PR #114는 이 PR이 jsdom 30.1.3을 포함하므로 다시 열지 않는다
- `gh pr checks` 11/11 success를 dod `### AC9`에 기록한다
