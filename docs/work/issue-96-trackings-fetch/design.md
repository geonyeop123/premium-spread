# Design — apps/web 트래킹 페이지 조회 훅 분리 (issue #96)

- 이슈: https://github.com/geonyeop123/premium-spread/issues/96
- 트랙: feature-track **M**
- 범위: `apps/web` 한정. 백엔드·API·화면 문구 변경 없음

## 1. 문제

#82(PR #95)에서 `eslint-plugin-react-hooks`가 7.1.1로 올라가면서 `react-hooks/set-state-in-effect`가 생겼다. 이 규칙이 두 페이지의 세 호출을 오류로 잡는다. 지금은 `apps/web/eslint.config.mjs` 8~12행이 두 파일에서만 규칙을 끈다.

규칙을 강제로 켜고 실행한 결과(2026-11-09, node:24-alpine):

```
src/app/trackings/[id]/page.tsx  62:5  fetchTracking();
src/app/trackings/[id]/page.tsx  69:5  fetchPnl();
src/app/trackings/page.tsx       45:7  fetchData();
✖ 3 problems (3 errors, 0 warnings)
```

## 2. 규칙이 무엇을 잡는가 — 실측

임시 파일로 후보 형태를 lint했다. 증거 파일은 job tmp `candidates.tsx`이고 저장소에는 넣지 않는다.

| 형태 | 결과 |
|---|---|
| B: `useCallback` 조회 함수(`await` 뒤 setState, `finally` setState)를 effect에서 호출 | 통과 |
| B2: B에서 `catch`가 setState | **걸림** |
| B3: B를 컴포넌트 본문에 둠 | 통과 |
| A: effect 안에 async 함수를 두고 취소 플래그를 씀, `reload`는 key state 증가 | 통과 |
| A2: A에 `Promise.all` + `catch` setState + `finally` setState | 통과 |
| C: effect 안 `.then` 체인 + `setInterval` 폴링 | 통과 |
| D: effect 본문에서 동기 setState | **걸림** (규칙의 본래 대상) |

결론은 다음과 같다.

- 규칙은 effect가 부르는 **외부 함수**가 동기 경로(예: `await` 전에 난 예외 → `catch`)로 setState할 수 있는지를 본다.
- 조회 함수를 effect **안**에 두면 같은 setState라도 통과한다.
- "useCallback을 effect에서 부른다"는 형태 자체가 문제는 아니다. 그래서 형태 규칙이 아니라 실제 lint 결과로 판정한다.

## 3. 결정

**D1. 조회 전용 훅 3개를 `apps/web/src/hooks/useTrackings.ts`에 둔다.**
- 근거: 두 페이지의 조회·폴링·취소 로직을 화면 코드에서 떼어 내야 단위 테스트를 할 수 있다. 지금은 조회 로직에 테스트가 없다. 세 훅은 같은 리소스(`/trackings*`)를 다루므로 한 파일에 둔다.
- 대안으로 페이지 안 effect만 고치는 방법이 있다. diff는 작지만 테스트하려면 페이지 전체를 렌더해야 하므로 버렸다.

**D2. 각 훅의 effect 안에 async 함수를 두고 지역 `cancelled` 플래그로 늦은 응답을 버린다.**
- §2의 A·A2·C 형태다. setState는 모두 `!cancelled`일 때만 한다. effect cleanup이 `cancelled = true`로 만든다.
- 효과: 언마운트 뒤나 `id`가 바뀐 뒤 도착한 응답이 상태를 덮어쓰지 않는다. 화면에서 보이는 동작은 같다.
- `AbortController`로 fetch 자체를 취소하는 방법도 있지만 `apiClient`에 `signal`을 넘기는 경로를 검증해야 한다. 이슈 범위가 아니라서 버렸다.

**D3. 훅은 `user` 대신 `userId: number | null`을 받는다.**
- 페이지에서 `user?.id ?? null`을 넘긴다. null이면 조회하지 않는다. 지금의 `if (!user) return`과 같다.
- 객체 identity 대신 원시값을 deps에 쓴다. 같은 사용자가 다시 로그인해 user 객체만 바뀐 경우에는 재조회하지 않는다. 지금 코드는 이때 재조회하지만, 같은 사용자의 데이터라서 화면 결과가 같다.

**D4. 목록 훅 `useTrackingList`는 `reload()`를 돌려주고, 이 함수는 내부 `reloadKey` state만 올린다.**
- effect deps에 `reloadKey`가 있어서 재조회가 effect 안에서 일어난다(형태 A).
- `RecordTrackingForm onSuccess={reload}`로 연결한다.
- 재조회 중에도 `loading`을 true로 되돌리지 않는다. 지금 `fetchData`처럼 목록을 유지한 채 교체한다.

**D5. 상세는 훅 두 개로 나눈다.**
- `useTracking(id, userId)`는 `{ tracking, loading, error }`를 돌려준다. 실패하면 `error`에 `'기록을 불러올 수 없습니다.'`를 넣는다.
- 종료 실패 메시지는 페이지의 `actionError` state로 분리한다. 화면 규칙은 다음과 같다.
  - `loadError && !tracking`이면 오류 카드를 보인다.
  - 하단 문구는 `actionError`를 보인다.
  - 지금은 `error` 하나를 공유하지만 같은 결과가 나온다. 조회가 실패하면 `tracking`이 null이라 오류 카드만 보이고, 하단 문구는 종료 실패 때만 보이기 때문이다.
- `useGrossPnl(id, status)`의 `status`는 `'ACTIVE' | 'ARCHIVED' | null`이다.
  - null(기록 미수신)이면 조회하지 않는다.
  - 그 밖에는 즉시 1회 조회한다. ACTIVE면 5000ms 간격으로 폴링한다.
  - 실패하면 이전 값을 유지한다. 첫 조회 실패(409 포함)면 null로 남는다. 지금 catch가 아무것도 하지 않는 것과 같다.
  - deps는 `[id, status]`다. 지금은 `tracking` 객체지만 이 객체는 한 번만 설정되므로 효과가 같다.

**D6. `eslint.config.mjs`에서 D7 override 블록(주석 포함 5줄)을 지운다.** 다른 규칙은 바꾸지 않는다.

## 4. 범위 밖 (발견했지만 하지 않음)

- 폴링 요청 겹침 방지. 응답이 5초보다 늦으면 요청이 겹친다. 바꾸면 갱신 주기가 달라지므로 화면 동작 변경에 해당한다.
- 목록 조회 실패 표시. 지금은 아무것도 표시하지 않는다. 표시하면 화면 동작이 바뀐다.
- SWR·React Query 도입.

## 5. 영향 파일

| 파일 | 변경 |
|---|---|
| `apps/web/src/hooks/useTrackings.ts` | 신규. 훅 3개 |
| `apps/web/src/hooks/useTrackings.test.tsx` | 신규. 훅 테스트 |
| `apps/web/src/app/trackings/page.tsx` | 조회 로직을 `useTrackingList`로 교체 |
| `apps/web/src/app/trackings/[id]/page.tsx` | 조회 로직을 `useTracking`·`useGrossPnl`로 교체, `actionError` 분리 |
| `apps/web/eslint.config.mjs` | override 블록 삭제 |
| `docs/work/issue-96-trackings-fetch/*`, `docs/work/README.md`, `.ai/PROJECT_STATUS.md` | 작업 문서 |
