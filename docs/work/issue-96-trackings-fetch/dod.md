---
feature: apps/web 트래킹 페이지 조회 훅 분리 — set-state-in-effect override 제거
slug: issue-96-trackings-fetch
status: DRAFT
frozen_at:
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/96
---

## 범위

**포함**

- `apps/web/src/hooks/useTrackings.ts`(훅 `useTrackingList`·`useTracking`·`useGrossPnl`)와 그 테스트 신규 (design D1~D5)
- 트래킹 목록·상세 페이지가 위 훅을 쓰도록 교체 (design D4·D5)
- `apps/web/eslint.config.mjs`의 #82 D7 override 블록 제거 (design D6)
- 작업 문서: `docs/work/issue-96-trackings-fetch/*`(AC7 스크린샷 `evidence/` 포함), `docs/work/README.md`, `.ai/PROJECT_STATUS.md`

**제외** *(scope creep 차단선)*

- SWR·React Query 등 조회 라이브러리 도입
- 화면 문구·레이아웃·API 계약 변경
- 폴링 요청 겹침 방지, 목록 조회 실패 표시 (design §4)
- `apiClient`에 `AbortSignal` 전달
- 다른 페이지·컴포넌트(`TrackingList.tsx` 등)의 조회 코드

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` | 아니오. JVM 모듈 대상이다 |
| lint 규칙 | 있음 — `npm --prefix apps/web run lint`(eslint-config-next 16.4.5, `react-hooks/set-state-in-effect` 포함) | 예. AC2 |
| custom npm task | 있음 — `npm run test`(vitest + jsdom 31, `src/**/*.test.{ts,tsx}`), `npm run build`(타입 검사 포함) | 예. AC3~AC6, R1·R2 |
| CI job | 있음 — `.github/workflows/quality-gate.yml` 6번 잡(Node 24: `npm ci` → lint → test → audit), 7a·7번 잡(web 이미지 빌드) | 예. AC2·R1을 PR에서 다시 잰다 |

실행 환경: 아래 명령은 모두 Node 24에서 실행한다(CI 6번 잡, 또는 로컬에서는 `node:24-alpine@sha256:a34ab6d7…67c8` 컨테이너). jsdom 31이 Node 20을 지원하지 않기 때문이다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | `apps/web/eslint.config.mjs`에 `set-state-in-effect` 문자열이 0건이다 | 이슈 AC 1 | `요구` | T2 | `기존` `grep` | `grep -c set-state-in-effect apps/web/eslint.config.mjs` | 출력 `0`. RED(기준선): `2` |
| AC2 | override가 없는 설정에서 lint가 오류 0건으로 끝난다 | 이슈 요구사항 2, AC 2 | `요구` | T1 | `기존` `npm --prefix apps/web run lint` | `npm --prefix apps/web run lint` | exit 0. RED: 규칙을 켠 dev 코드에서 3 errors(`[id]/page.tsx` 62·69, `page.tsx` 45 — design §1) |
| AC3 | `useTrackingList`는 `userId`가 null이면 API를 부르지 않는다. 값이 있으면 `/trackings`·`/trackings/archived`·`/trackings/summary` 응답을 `activeTrackings`·`history`·`summary`에 넣고 `loading`을 false로 만든다. 조회가 실패해도 `loading`은 false가 된다. `reload()`를 부르면 세 API를 한 번씩 더 부른다 | 이슈 "기존 동작 유지: 로그인 후 조회", `page.tsx:148` onSuccess 재조회 | `요구` | T1 | `신규테스트` `src/hooks/useTrackings.test.tsx` › `useTrackingList` | `npm --prefix apps/web test -- src/hooks/useTrackings.test.tsx -t useTrackingList` | exit 0, 해당 describe의 테스트가 모두 passed(skipped 외 실패 0) |
| AC4 | `useTracking`은 `userId`가 null이면 부르지 않는다. 값이 있으면 `/trackings/{id}` 응답을 `tracking`에 넣는다. 실패하면 `error`가 `기록을 불러올 수 없습니다.`이다. 두 경우 모두 `loading`은 false가 된다 | 상세 페이지 기존 동작 | `요구` | T1 | `신규테스트` `src/hooks/useTrackings.test.tsx` › `useTracking` | `npm --prefix apps/web test -- src/hooks/useTrackings.test.tsx -t "useTracking "` | exit 0, 해당 describe 실패 0 |
| AC5 | `useGrossPnl`의 `/trackings/{id}/gross-pnl` 호출 조건은 다음과 같다(가짜 타이머로 잰다). `status`가 ACTIVE면 0ms·5000ms·10000ms에 한 번씩 모두 3회 부른다. ARCHIVED면 0ms에 1회만 부르고 10000ms까지 추가 호출이 없다. null이면 0회다. 언마운트 뒤에는 추가 호출이 0회다. 첫 호출이 실패(409)하면 `pnl`은 null이다 | 이슈 "ACTIVE 5초 폴링, ARCHIVED 1회, 409 확정 불가" | `요구` | T1 | `신규테스트` `src/hooks/useTrackings.test.tsx` › `useGrossPnl` | `npm --prefix apps/web test -- src/hooks/useTrackings.test.tsx -t useGrossPnl` | exit 0, 해당 describe 실패 0 |
| AC6 | 응답이 오기 전에 훅이 언마운트되거나 `id`가 바뀌면, 늦게 온 이전 응답은 `tracking`·`pnl`에 반영되지 않는다 | design D2 (규칙을 통과하는 effect 내 조회 형태에 딸린 취소) | `추론` | T1 | `신규테스트` `src/hooks/useTrackings.test.tsx` › `늦은 응답` | `npm --prefix apps/web test -- src/hooks/useTrackings.test.tsx -t "늦은 응답"` | exit 0, 해당 describe 실패 0 |
| AC7 | 백엔드를 띄운 화면에서 다음이 확인된다. 목록 화면은 요약 3개·추적 중·종료 탭을 표시하고, 기록 추가 뒤 목록이 갱신된다. ACTIVE 상세는 gross 손익이 5초마다 갱신된다(계산 시각이 바뀐다). ARCHIVED 상세는 확정 손익 또는 "확정 불가" 안내를 표시한다 | 이슈 AC 3 | `요구` | T3 | `관찰` 로컬 기동(infra-compose + `:apps:api:bootRun` + `next dev`, 필요하면 `:apps:batch:bootRun`) 후 Playwright chromium 스크린샷 | — | `docs/work/issue-96-trackings-fetch/evidence/`에 목록·ACTIVE 상세(5초 간격 두 장, 계산 시각이 다름)·ARCHIVED 상세 스크린샷이 있고, 증거 로그에 관찰 내용이 있다 |

**기준 개수**: 7개

**분할하지 않는 이유**: 해당 없음(8개 이하).

**티어 강등 사유**

- AC1 → T2: "설정 파일에 문자열이 없다"를 재는 이름 붙은 산출물이 없다. 이 한 줄 검사를 위해 스크립트를 커밋하는 것은 과하다. 같은 효과는 AC2(lint)가 행동으로 잰다.
- AC7 → T3: 이슈가 화면 수동 확인을 요구한다. 실제 백엔드 응답과 폴링을 브라우저에서 봐야 하고, 페이지와 훅의 연결(`onSuccess={reload}`, 상태 전달)을 재는 자동 테스트는 이번 범위에 없다. 계약 승인(2026-11-09)에서 사용자가 "Claude가 앱을 띄워 확인"을 골라 T4 대신 기록된 관찰(T3)로 정했다.

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 기존 테스트(`TrackingNotices.test.tsx` 10개)를 포함한 전체 vitest가 통과한다 | `npm --prefix apps/web run test` (기준선 2026-11-09: `10 passed (10)`) |
| R2 | 타입 검사를 포함한 production build가 통과하고 라우트 6개가 그대로다 | `npm --prefix apps/web run build` (기준선: `/`, `/_not-found`, `/login`, `/register`, `/trackings`, `/trackings/[id]`) |
| R3 | 변경 파일이 design §5 목록 밖으로 나가지 않는다 | `git diff --stat origin/dev...HEAD` |

## 증거 로그

> 구현 중 append only.

### AC1

```
[RED 기준선] 2026-11-09 d1ba7bd
$ grep -n set-state-in-effect apps/web/eslint.config.mjs
8:  // react-hooks 7.1 의 set-state-in-effect 가 ...
11:    rules: { "react-hooks/set-state-in-effect": "off" },
```

### AC2

```
[RED] 2026-11-09 d1ba7bd
$ npx eslint --rule '{"react-hooks/set-state-in-effect":"error"}' src/app/trackings   (node:24-alpine, apps/web)
src/app/trackings/[id]/page.tsx 62:5 fetchTracking(); / 69:5 fetchPnl();
src/app/trackings/page.tsx 45:7 fetchData();
✖ 3 problems (3 errors, 0 warnings)
```

### AC3 · AC4 · AC5 · AC6

### R1 · R2 · R3

```
[기준선] 2026-11-09 d1ba7bd (node:24-alpine)
$ npm run test   → Test Files 1 passed (1) · Tests 10 passed (10)
$ npm run build  → Compiled successfully · 라우트 6개 (/, /_not-found, /login, /register, /trackings, /trackings/[id])
```

## 사람 확인 (T4)

해당 없음(T4 기준 0개).

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: issue-96-trackings-fetch @ <commit SHA>
  수용기준 표:     7개  (T1 5 · T2 1 · T3 1 · T4 0)
  T1/T2 자동:      6개 중 <p>개 PASS
  T3 기록 제출:    1개 중 <q>건
  T4 사람 확인:    0개 중 0건 완료, 0건 대기
  변경 요청:       <k>건
  =>
```
