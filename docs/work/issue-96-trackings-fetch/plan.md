# Plan — issue #96 트래킹 조회 훅 분리

- design: `design.md` · DoD: `dod.md` (FROZEN 2026-11-09)
- 실행 환경: Node 24. 로컬에서는 아래 래퍼로 `apps/web`에서 실행한다(Node 20에서는 jsdom 31 테스트가 실패한다).

  ```bash
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e NEXT_TELEMETRY_DISABLED=1 \
    -v "$PWD/apps/web":/app -w /app \
    node:24-alpine@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8 sh -c '<명령>'
  ```

## Task 1 — 조회 훅과 테스트 (AC3·AC4·AC5·AC6)

**파일**
- 신규: `apps/web/src/hooks/useTrackings.ts`
- 신규: `apps/web/src/hooks/useTrackings.test.tsx`

**공개 시그니처**

```ts
export interface TrackingSummary {
  totalTrackings: number;
  activeTrackings: number;
  archivedTrackings: number;
}

export function useTrackingList(userId: number | null): {
  activeTrackings: Tracking[];          // 초기값 []
  history: Tracking[];                  // 초기값 []
  summary: TrackingSummary | null;      // 초기값 null
  loading: boolean;                     // 초기값 true
  reload: () => void;                   // identity 고정 (useCallback, deps [])
};

export function useTracking(id: string, userId: number | null): {
  tracking: Tracking | null;            // 초기값 null
  loading: boolean;                     // 초기값 true
  error: string;                        // 초기값 '', 실패 시 '기록을 불러올 수 없습니다.'
};

export function useGrossPnl(
  id: string,
  status: Tracking['status'] | null,
): GrossPnlData | null;                 // 초기값 null

export const GROSS_PNL_POLL_INTERVAL_MS = 5000;
```

- `Tracking`·`GrossPnlData`는 `@/components/TrackingList`에서 import한다. `TrackingSummary`는 지금 `app/trackings/page.tsx`에 있는 지역 interface를 이 파일로 옮긴다.
- API 호출은 `apiClient`(`@/lib/api`)로만 한다. 경로는 지금과 같다.
  - `/trackings`, `/trackings/archived`, `/trackings/summary`: `Promise.all`로 동시 호출
  - `/trackings/${id}`
  - `/trackings/${id}/gross-pnl`

**결정 (design D2~D5)**
- 세 훅 모두 effect 안에 조회 함수를 정의하고 effect 안에서 호출한다. 지역 `let cancelled = false`를 두고, cleanup이 `cancelled = true`로 만든다. 모든 setState는 `!cancelled`일 때만 한다.
- effect 본문에서 동기 setState를 하지 않는다. 시작할 때 loading을 true로 되돌리지 않는다.
- `useTrackingList`
  - deps는 `[userId, reloadKey]`다. `reload`는 `setReloadKey(k => k + 1)`이다.
  - 실패하면 데이터는 그대로 두고 `loading`만 false로 만든다.
- `useTracking`의 deps는 `[id, userId]`다.
- `useGrossPnl`
  - deps는 `[id, status]`다. `status`가 null이면 아무것도 하지 않는다.
  - 그 밖에는 즉시 1회 조회한다. `'ACTIVE'`이면 `setInterval(load, GROSS_PNL_POLL_INTERVAL_MS)`을 걸고 cleanup에서 해제한다.
  - 실패하면 아무것도 하지 않는다. 409를 포함하며, 이전 값이 유지되고 첫 실패면 null로 남는다.
  - 409 처리 근거는 지금 `[id]/page.tsx` 55~56행 주석이다. 같은 취지의 주석을 이 훅으로 옮긴다.

**테스트** (`vi.mock('@/lib/api')`로 `apiClient`를 대체하고 `renderHook`·`waitFor`·`act`, 가짜 타이머 `vi.useFakeTimers()`를 쓴다)

| describe / it | 단언 | DoD |
|---|---|---|
| `useTrackingList` › userId가 null이면 조회하지 않는다 | `apiClient` 호출 0회, `loading` true | AC3 |
| `useTrackingList` › 세 API 응답을 상태에 넣는다 | 호출 경로 3개, `activeTrackings`·`history`·`summary` 값, `loading` false | AC3 |
| `useTrackingList` › 조회가 실패해도 loading이 끝난다 | reject 후 `loading` false, 목록 `[]`, `summary` null | AC3 |
| `useTrackingList` › reload는 세 API를 다시 부른다 | `reload()` 후 호출 총 6회, 새 응답 반영 | AC3 |
| `useTracking` › userId가 null이면 조회하지 않는다 | 호출 0회 | AC4 |
| `useTracking` › 응답을 tracking에 넣는다 | 경로 `/trackings/7`, `tracking` 값, `loading` false, `error` '' | AC4 |
| `useTracking` › 실패하면 오류 문구를 낸다 | `error` === `'기록을 불러올 수 없습니다.'`, `loading` false, `tracking` null | AC4 |
| `useGrossPnl` › ACTIVE는 5초마다 다시 조회한다 | 0·5000·10000ms에 호출 1·2·3회 | AC5 |
| `useGrossPnl` › ARCHIVED는 한 번만 조회한다 | 10000ms 진행 후에도 1회 | AC5 |
| `useGrossPnl` › status가 null이면 조회하지 않는다 | 10000ms 진행 후 0회 | AC5 |
| `useGrossPnl` › 언마운트하면 폴링이 멈춘다 | unmount 후 10000ms 진행해도 호출 수가 늘지 않는다 | AC5 |
| `useGrossPnl` › 409면 pnl이 null이다 | `ApiError(409)` reject 후 `pnl` null | AC5 |
| `늦은 응답` › id가 바뀌면 이전 id의 tracking 응답은 버린다 | id 1 요청을 지연한 채 id 2로 rerender한다. id 2가 먼저 resolve하고 그다음 id 1이 resolve해도 `tracking.id`는 2다 | AC6 |
| `늦은 응답` › id가 바뀌면 이전 id의 pnl 응답은 버린다 | 같은 방식으로 `useGrossPnl`을 잰다. 최종 `pnl`은 id 2의 값이다 | AC6 |

- 언마운트 뒤 응답은 `renderHook` 결과로 관찰할 수 없다(React 18+는 경고도 내지 않는다). 그래서 AC6은 같은 cleanup(`cancelled = true`) 경로를 타는 id 변경 케이스로 잰다.
- `ApiError`는 실제 클래스를 쓴다. `vi.mock`에서 `importOriginal`로 `ApiError`를 유지하고 `apiClient`만 `vi.fn()`으로 바꾼다.
- `-t` 필터는 describe 이름으로 건다. `useTracking` describe 이름은 `useTracking `(뒤 공백)으로 시작하는 테스트명이 되도록 `useTracking`으로 짓고, `useTrackingList`와 구분된다(DoD AC4 명령 `-t "useTracking "`).

**RED → GREEN**
1. 시그니처와 초기값만 돌려주는 스텁 훅으로 테스트를 먼저 돌린다. 기대한 assertion이 실패하는 것(호출 0회 기대 테스트를 제외한 나머지)을 확인하고 로그를 남긴다.
2. 구현한다.

**검증 명령**
- `npm test -- src/hooks/useTrackings.test.tsx` → 14 passed
- `npx eslint src/hooks` → exit 0

## Task 2 — 페이지 교체와 override 제거 (AC1·AC2·R1·R2)

**파일**
- `apps/web/src/app/trackings/page.tsx`
  - `useState`·`useEffect`·`useCallback`·`apiClient`·지역 `TrackingSummary`를 지우고 `useTrackingList(user?.id ?? null)`을 쓴다.
  - `<RecordTrackingForm onSuccess={reload} />`로 연결한다.
  - JSX는 바꾸지 않는다.
- `apps/web/src/app/trackings/[id]/page.tsx`
  - `fetchTracking`·`fetchPnl`과 effect 두 개를 지운다.
  - `const { tracking, loading, error: loadError } = useTracking(id, user?.id ?? null)`
  - `const pnl = useGrossPnl(id, tracking?.status ?? null)`
  - 종료 실패 메시지는 `const [actionError, setActionError] = useState('')`로 둔다. `handleClose`가 쓰던 `setError`를 `setActionError`로 바꾼다.
  - 렌더 분기를 바꾼다.
    - `if (error && !tracking)` → `if (loadError && !tracking)`
    - 하단 `{error && …}` → `{actionError && …}`
  - 그 밖의 JSX와 문구는 그대로 둔다.
- `apps/web/eslint.config.mjs`: 8~12행(주석 1줄 + override 객체 4줄)을 삭제한다.

**검증 명령** (Node 24 래퍼)
- `grep -c set-state-in-effect apps/web/eslint.config.mjs` → `0` (AC1)
- `npm run lint` → exit 0 (AC2)
- `npm run test` → `Test Files 2 passed`, `Tests 24 passed (24)` (R1: 기존 10 + 신규 14)
- `npm run build` → 라우트 6개 그대로 (R2)

## Task 3 — 로컬 기동 화면 관찰 (AC7, 메인 세션)

- 기존 `premium-spread-mysql`·`redis-*` 컨테이너를 쓴다. 다른 세션의 testcontainers 컨테이너는 건드리지 않는다.
- `./gradlew :apps:api:bootRun`을 실행하고 web `next dev`를 띄운다.
  - 시세가 필요하면 `:apps:batch:bootRun`도 실행한다.
  - 로컬 설정 값은 출력하지 않는다.
- 가입·로그인 후 기록을 하나 추가하고 하나를 종료한다.
- Playwright chromium으로 아래 스크린샷을 `docs/work/issue-96-trackings-fetch/evidence/`에 저장한다.
  - 목록 화면
  - ACTIVE 상세 2장: 5초 이상 간격, 계산 시각이 다른 것
  - ARCHIVED 상세
- 관찰 내용을 dod 증거 로그 AC7에 적는다.
- 기동할 수 없으면 그 사유를 dod에 기록하고 판정을 `BLOCKED`(해당 항목)로 보고한다. 기준을 낮추지 않는다.
