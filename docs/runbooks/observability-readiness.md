# 관측성과 readiness 운영 정책

## Correlation ID와 로그

- API는 `X-Request-Id`를 수신하며 `[A-Za-z0-9._-]{1,64}`만 신뢰한다. 없거나 형식이 잘못되면 32자리 ID를 생성한다.
- 같은 ID를 응답 header와 MDC `requestId`에 기록한다. URI/method/client IP도 요청 종료 시 반드시 제거한다.
- Spring async task와 bounded operator-alert executor는 제출 시점 MDC snapshot을 worker에 복사하고 작업 종료 후 이전 MDC를 복원한다.
- text/JSON 로그 모두 email, token, password, cookie, Authorization/Bearer, API/secret key를 `***MASKED***`로 치환한다.

## Metric 이름과 허용 tag

Metric 이름은 runtime 값으로 조합하지 않는다. 주요 운영 계약은 다음과 같다.

| 영역 | Metric | 허용된 bounded tag |
|---|---|---|
| HTTP | `http.server.requests` | `method`, `status`, `outcome`, `uri`, `exception` |
| Cache | `cache.read.total` | `cache`, `outcome=hit/miss/corrupt/legacy_hit/error` |
| Job | `batch.job.run`, `batch.job.duration`, `batch.job.last.success.age` | `job`, `outcome=succeeded/skipped/failed` |
| Lock | `batch.job.lock` | `outcome=acquired/not_acquired/renewed/ownership_lost/released/error` |
| WebSocket | `ws.connection.state`, `ws.reconnect.attempt`, `ws.stale`, `ws.last.message.age` | `exchange` |
| Premium | `premium.calculation` | `outcome=success/skipped/invalid/failure` |
| Notification | `premiumspread.notification.delivery.transitions` | `outcome=pending/processing/sent/retry/failed/duplicate/recovered/scrubbed/stale_ownership` |

그 밖의 현재 허용 key는 `api`, `base`, `cache`, `error`, `error_type`, `exception`, `exchange`, `job`,
`market`, `method`, `outcome`, `provider`, `quote`, `status`, `symbol`, `uri`, `zone`이다. `email`, `memberId`,
`token`, `password`, `cookie`, `message`, `exceptionMessage`, delivery/run/owner 식별자는 tag로 사용할 수 없다.
등록 시 filter와 architecture test가 이 정책을 강제하며 HTTP URI는 최대 100개 조합까지만 허용한다.

## Readiness 정책

- liveness는 JVM process 생존만 판정한다. 외부 dependency 장애를 liveness에 넣어 restart loop를 만들지 않는다.
- API readiness는 application availability, MySQL, 인증 refresh-session에 필요한 Redis가 모두 정상일 때 `UP`이다.
- Batch readiness는 application availability, MySQL, Redis와 필수 Binance/Bithumb ingestion이 모두 연결되고
  `market-streams.connection.idle-timeout` 이내 메시지를 받은 경우만 `UP`이다.
- `batch.scheduling.enabled=false`인 점검/test 실행은 ingestion을 readiness에서 제외하되 DB/Redis는 계속 확인한다.
- dependency 예외의 message는 health detail에 노출하지 않고 bounded exception type만 기록한다.

장애 시 API는 DB/Redis 연결을 먼저 확인한다. Batch는 DB/Redis 이후 `ws.connection.state`,
`ws.last.message.age`, reconnect/stale metric 순서로 확인한다. readiness를 임의로 우회하지 말고 필수 stream 복구 후
트래픽/스케줄 실행 대상으로 되돌린다.
