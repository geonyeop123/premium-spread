# REST Contract Baseline

> 기준: `origin/dev` `a0a59ee`, 2026-07-14

| Method | Path | 성공 | 성공 body |
|---|---|---:|---|
| POST | `/api/v1/members/register` | 201 | `MemberResponse.Detail` |
| POST | `/api/v1/members/login` | 200 | `accessToken`; refresh cookie |
| GET | `/api/v1/members/me` | 200 | `MemberResponse.Detail` |
| POST | `/api/v1/auth/refresh` | 200 | `accessToken`; rotated refresh cookie |
| POST | `/api/v1/auth/logout` | 200 | `{message}`; expired refresh cookie |
| POST | `/api/v1/tickers` | 201 | `TickerResponse.Detail` |
| POST | `/api/v1/premiums/calculate/{symbol}` | 200 | `PremiumResponse.Detail` |
| GET | `/api/v1/premiums/current/{symbol}` | 200 | `PremiumResponse.Current` |
| GET | `/api/v1/premiums/history/{symbol}` | 200 | `PremiumResponse.Detail[]` |
| GET | `/api/v1/premiums/aggregation/{symbol}` | 200 | `{data,hasMore}` |
| POST | `/api/v1/positions/auto` | 201 | `PositionResponse.Detail` |
| POST | `/api/v1/positions/manual` | 201 | `PositionResponse.Detail` |
| GET | `/api/v1/positions` | 200 | `PositionResponse.Detail[]` |
| GET | `/api/v1/positions/summary` | 200 | `PositionResponse.Summary` |
| GET | `/api/v1/positions/history` | 200 | `PositionResponse.Detail[]` |
| GET | `/api/v1/positions/{id}` | 200 | `PositionResponse.Detail` |
| GET | `/api/v1/positions/{id}/pnl` | 200 | `PositionResponse.Pnl` |
| POST | `/api/v1/positions/{id}/close` | 200 | `PositionResponse.Detail` |
| POST | `/api/v1/notifications/subscriptions` | 201 | subscription detail |
| GET | `/api/v1/notifications/subscriptions` | 200 | subscription detail array |
| GET | `/api/v1/notifications/subscriptions/{id}` | 200 | subscription detail |
| PATCH | `/api/v1/notifications/subscriptions/{id}` | 200 | subscription detail |
| DELETE | `/api/v1/notifications/subscriptions/{id}` | 204 | empty |

오류 envelope는 `{code,message}`다. 현재 integration contract가 확인하는 주요 상태는 400, 401, 404,
405, 409, 500이며 기준 실행은 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 bash gradlew
:apps:api:integrationTest --rerun-tasks --offline --no-daemon`이다. 실행 결과는 88 tests, failure/error 0,
skip 1이다. Controller 선언과 `apps/api/src/test`의 MockMvc/E2E 테스트가 실행 가능한 원본 fixture다.
