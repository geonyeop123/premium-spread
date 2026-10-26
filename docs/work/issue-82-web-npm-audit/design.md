# apps/web npm audit 취약점 해소 — 설계

- 이슈: #82 `[fix] apps/web npm audit 취약점 해소 — quality-gate 6번 잡 복구`
- 브랜치: `fix/issue-82-web-npm-audit` (base `dev`)

## 1. 문제

CI `quality-gate.yml`의 `6. Dependency + security scan` 잡은 `bash ci/run-npm-audit.sh`
(`npm --prefix apps/web audit --audit-level=high`)를 실행한다. `apps/web/package.json`·`package-lock.json`이
바뀌지 않았는데도 그 사이 공개된 advisory 때문에 2026-09-16부터 dev와 모든 PR에서 이 잡이 실패한다.

2026-10-26 worktree에서 `npm ci` 후 측정한 결과는 23건(moderate 8 · high 11 · critical 4)이다.
이슈 작성 시점(27건)과 다른 것은 advisory가 계속 바뀌기 때문이다.

| 구분 | 패키지 | audit 제안 |
|---|---|---|
| critical | `next`(direct), `sharp`(next 경유) | `next@16.4.3` (고정 버전이라 범위 밖) |
| critical | `vitest`(direct), `tinypool`, `@vitest/mocker` | `vitest@4.1.12` (major) |
| critical | `proxy-addr` | 범위 내 lockfile 갱신 |
| high | `shadcn`(direct), `zod`(shadcn 경유) | `shadcn@4.22.1` (major) |
| high/moderate | `axios`, `body-parser`, `brace-expansion`, `follow-redirects`, `js-yaml`, `source-map-js`, `vite`, `@modelcontextprotocol/sdk`, `hono`, `@hono/node-server`, `@humanfs/node`, `fast-uri`, `ip-address`, `postcss-selector-parser`, `qs` | 범위 내 lockfile 갱신 (`npm audit fix`) |

## 2. 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | `next`와 `eslint-config-next`를 **같은 고정 버전 16.4.3**으로 올린다 | audit 제안 버전. 기존 관례대로 정확한 버전으로 고정하고, 두 패키지 버전을 맞춘다 |
| D2 | `vitest`를 `^4.1.12`로 올린다. `@vitejs/plugin-react`는 vitest 4가 끌어오는 vite와 peer 범위가 맞는지 확인하고 필요할 때만 올린다 | vitest 3 계열에는 수정판이 없다. 테스트 파일 1개, 설정은 `defineConfig` 단일 객체라 영향 범위가 작다 |
| D3 | `shadcn`을 **제거하지 않고 `^4.22.1`로 업그레이드**한다 (③ 합의) | `src/app/globals.css`가 `@import "shadcn/tailwind.css"`를 쓰므로 CLI 전용이 아니라 빌드 의존성이다. 4.22.1도 `./tailwind.css`를 export한다. 이슈 원문이 말한 1.0.0 다운그레이드는 지금 audit이 제안하지 않는다 |
| D4 | 나머지 전이 의존성은 `npm audit fix`(force 없이)로 lockfile을 갱신한다. 그래도 남으면 기존 `overrides` 항목의 하한을 올리거나 항목을 추가한다 | 저장소가 이미 `overrides`로 전이 의존성을 고정해 왔다 (BKF-04) |
| D5 | 변경은 `apps/web/package.json`·`apps/web/package-lock.json`에 한정한다. major 업그레이드로 깨진 설정·테스트만 같은 작업에서 고친다 | 이슈 요구사항 |
| D7 | `eslint-config-next` 16.4.3이 `eslint-plugin-react-hooks`를 7.1.1로 올리며 새 규칙 `react-hooks/set-state-in-effect`가 `src/app/trackings/page.tsx`·`src/app/trackings/[id]/page.tsx`의 effect 내 조회 호출 3곳을 오류로 잡는다. `eslint.config.mjs`에 **이 두 파일 한정** override로 규칙을 끈다 (⑧ 중 사용자 결정) | 소스를 바꾸지 않고 D5의 "깨진 설정 수정" 범위에서 해결한다. overrides로 7.0.1 고정은 `eslint-config-next`의 `^7.1.0` 요구를 어겨 기각했다. 조회 패턴 개선은 별도 이슈로 다룬다 |
| D6 | high 미만(low/moderate) 잔여 건이 있으면 PR 본문에 패키지·advisory·미해결 사유를 적는다 | 이슈 수용 기준. 게이트는 high 이상만 막는다 |

## 3. 제외

- `ci/run-npm-audit.sh`의 임계값 완화와 `ci/npm-audit-suppressions.json` suppression 도입 (스크립트가 금지)
- 웹 기능 변경, Node 20 → 24 런타임 전환, Dockerfile 베이스 이미지 변경
- Dependabot/Renovate 도입 (별도 검토)

## 4. 위험

| 위험 | 대응 |
|---|---|
| next 16.2 → 16.4 마이너 변경 | `next build`로 6개 라우트(`/`, `/login`, `/register`, `/trackings`, `/trackings/[id]`, `/_not-found`)가 빌드되는지 확인하고, `next dev`로 각 라우트의 HTTP 200을 확인한다. 화면 확인은 사용자가 한다 |
| vitest 4 major (설정/전역/환경 변경) | `npm run test`로 기존 10개 테스트가 통과하는지 확인한다. 실패하면 설정만 고친다 |
| shadcn 4 major로 `tailwind.css` 내용 변화 | 업그레이드 전후 `node_modules/shadcn/dist/tailwind.css` diff를 기록하고 `next build`로 확인한다 |
| Docker web 이미지(node:20-alpine, `output: 'standalone'`, `node server.js`)와 next 16.4의 Node 요구 버전 | `next@16.4.3`의 `engines.node`는 `>=20.9.0`이다. CI 7번 잡은 이미지 빌드만 하므로, 로컬에서 이미지를 빌드·기동해 `/`·`/login` 200을 확인한다(dod AC7) |
| advisory가 판정 이후 머지 전에 바뀜 | 최종 판정 SHA에서 audit을 다시 실행하고 JSON을 남긴다(dod AC1). 머지 전 재적색되면 dod `변경 요청`으로 재승인받는다. 머지 후의 상시 갱신(Dependabot/Renovate)은 별도 검토 |
