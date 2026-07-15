# REST Contract Baseline

> 기준: `origin/dev` `a0a59ee`, 2026-07-14

| Method | Path | 성공 | 성공 body |
|---|---|---:|---|
| POST | `/api/v1/members/register` | 201 | `MemberResponse.Detail` |
| POST | `/api/v1/members/login` | 200 | `accessToken`; refresh cookie |
| GET | `/api/v1/members/me` | 200 | `MemberResponse.Detail` |
| POST | `/api/v1/auth/refresh` | 200 | `accessToken`; rotated refresh cookie |
| POST | `/api/v1/auth/logout` | 204 | empty; expired refresh cookie |
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

알림 구독의 create/update/detail은 `koreaExchange`와 `foreignExchange`를 canonical pair로 보존한다. 기존 create
요청이 두 필드를 생략하면 BITHUMB/BINANCE를 사용하며, update에서는 두 필드를 함께 제공해야 한다. enum 또는
거래소 지역 조합이 잘못되면 422 Domain error를 반환한다.

오류 envelope는 `{code,message}`다. Phase 5 계약은 transport 400/405, 인증 401, 미발견 404, 충돌 409,
semantic 422, 미분류 500을 구분한다. 실행 명령은 `bash gradlew :apps:api:integrationTest --rerun-tasks
--offline --no-daemon`이며 116 tests, failure/error/skip 0이다. Controller 선언과 `apps/api/src/test`의
MockMvc/E2E 테스트가 실행 가능한 원본 fixture다.
