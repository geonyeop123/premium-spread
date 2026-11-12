# batch 종료 시 운영 알림 오탐 정리 — 설계

- 이슈: [#135](https://github.com/geonyeop123/premium-spread/issues/135)
- 트랙: M (feature-track). 결과 분류와 종료 순서만 바꾸고 lock·트랜잭션 경계·스키마는 바꾸지 않는다.
- 범위 결정 (2026-11-12, owner): 원래 후보였던 "Job 취소 정합성(L)"에서 실측 기반의 "종료 오탐 정리"로 범위를 좁혔다.

## 1. 왜

batch 를 종료(재배포)하면 운영 알림이 실패가 아닌 일을 CRITICAL 로 보고한다. 재배포 때마다 알림이 쏟아지면 진짜 장애 알림이 묻힌다.

**실측 (2026-11-12 09:25:31, 로컬 batch 종료)**
- 종료 2ms 동안 `[binance] WebSocket ingestion hash 저장 5회 연속 실패: LettuceConnectionFactory is STOPPING` CRITICAL 알림이 12건 났다.
- Netty 종료로 연결이 끊기자 `Reconnecting in 1000 ms` 재연결도 시도했다.

**원인 — 종료 순서.** `AbstractApplicationContext.close()`는 세 단계를 순서대로 밟는다.
1. `ContextClosedEvent` 발행
2. lifecycle stop: `LettuceConnectionFactory`, 웹서버 graceful shutdown 등
3. 빈 파괴: `@PreDestroy`

WebSocket 클라이언트는 3단계(`@PreDestroy`)에서야 멈춘다. Redis 는 2단계에서 먼저 닫히므로, 그 사이 도착한 시세가 저장에 실패하고 연속 실패로 집계된다.

**`JobExecutor`도 같은 구간에 노출돼 있다.** 종료 중에 실행되던 Job이 Redis·DB 종료나 interrupt로 끝나면 `JobResult.Failure`가 된다. 이 경우 `BATCH_JOB_FAILED` CRITICAL 알림이 나가고 연속 실패 streak이 올라간다(#130 codex 지적). 이번 로그에서는 실행 중인 Job이 없어 관측되지 않았지만, 코드 경로상 가능하다.

## 2. 목표 / 비목표

**목표**
- 종료 중에 생긴 결과를 FAILED·CRITICAL 로 보내지 않는다.
- 종료가 아닐 때의 실패 분류는 그대로 둔다: timeout, lock 상실, 일반 예외는 FAILED + CRITICAL.

**비목표**
- 명시적 취소 결과 타입(`JobResult.Cancelled`)이나 메트릭 outcome(`batch.job.run` tag) 추가.
- 모니터 Job 저장 직전 취소 검사 TOCTOU 의 commit fencing (D4).
- `NotificationDeliveryJob`·`OwnerAlertDeliveryJob` 내부 executor 의 종료 처리, 운영 알림 큐(`BoundedOperatorAlertAdapter`)의 종료 시 유실.

## 3. 결정

### D1. 종료 신호 = `ContextClosedEvent`, 최우선 순위 listener
- 종료 신호로 `ContextClosedEvent`를 쓴다. lifecycle stop(Redis 종료)과 빈 파괴보다 먼저 발행되는 것이 Spring 계약이다.
- listener 는 `Ordered.HIGHEST_PRECEDENCE`다. 그래서 `ScheduledAnnotationBeanPostProcessor`의 예약 작업 취소(같은 이벤트, 최하위 순위)보다도 먼저 실행된다.
- 공유 컴포넌트는 만들지 않는다. `JobExecutor`와 WebSocket 클라이언트 2개가 각자 `ApplicationListener<ContextClosedEvent>`를 구현한다. 모듈 경계(`apps:batch` ↔ `infrastructure:batch`)를 넘는 새 타입이 필요 없다.
- `@PreDestroy stop()`은 멱등 백스톱으로 남긴다. 테스트처럼 이벤트 없이 빈이 파괴되는 경우를 위해서다.

### D2. WebSocket 클라이언트: 종료 신호에서 멈추고, 멈춘 뒤 메시지는 버린다
- 대상은 `BinanceWebSocketClient`와 `BithumbWebSocketClient`다.
- `ContextClosedEvent`를 받으면 `stop()`을 호출한다. `WebSocketConnectionManager.stop()`이 `running=false`로 바꾸므로 재연결 타이머도 멈춘다.
- `handlePayload`는 클라이언트가 멈춘 뒤 들어온 payload 를 ingestion·외부 sink 로 넘기지 않는다. 구독 해제와 동시에 처리 중이던 프레임이 있는 경우를 대비한 것이다. 이 판정에 쓰는 정지 여부는 클라이언트 자신의 플래그다. manager 내부 상태를 공개하지 않는다.
- ingestion 의 연속 실패 계수(`consecutiveSaveFailures`)는 바꾸지 않는다. 종료 후 저장 시도 자체가 없어지므로 계수할 일이 없다.

### D3. `JobExecutor`: 종료 중 결과는 `Skipped("shutdown")`
- `ContextClosedEvent`를 받으면 `shuttingDown=true`로 바꾼다.
- **종료 신호 뒤 새 실행:** lock 을 잡지 않고 action 도 실행하지 않는다. `Skipped("shutdown")`을 돌려주고 SKIPPED 로 기록한다.
- **실행 중에 종료 신호가 오고 결과가 `Failure`인 경우:** 원인을 가리지 않는다. interrupt, Redis·DB 종료 예외, 호출 스레드 interrupt 모두 같다. `Skipped("shutdown")`로 바꾸고, CRITICAL 알림을 보내지 않으며, 원래 예외는 WARN 로그로 남긴다. lock 해제와 보류 규칙(`actionMayStillRun`)은 지금과 같다.
  - 근거: 종료 중의 실패는 대부분 종료 자체가 만든 부산물이다. 진짜 실패가 우연히 겹쳐도 프로세스는 곧 내려가고, 다음 기동이 같은 Job 을 다시 실행해 신호를 낸다.
  - 원인을 interrupt·cancel 로만 좁히면 `LettuceConnectionFactory is STOPPING` 같은 종료 부산물이 계속 CRITICAL 로 나간다.
- 호출 스레드 interrupt 로 끝난 경우 interrupt 플래그를 되살린다. 기존 동작과 같다.
- 종료 신호가 없으면 분류는 한 줄도 바뀌지 않는다. 기존 `JobExecutorTest` 전체가 회귀 방어선이다.
- SKIPPED 는 streak 을 바꾸지 않는다. 기존 `RedisJobRunRecorderAdapter` 규칙이다.

### D4. 모니터 Job TOCTOU(#131 codex 지적)는 문서화만 한다
- `AccountPnlMonitorJob`은 저장 직전 interrupt 를 한 번 검사한다. 검사 직후 취소되면 그 회차의 상태와 outbox 가 커밋될 수 있다.
- 그래도 저장은 `loaded.version` 조건부라 이중 커밋과 중복 발송이 없다. 남는 일은 "실패로 기록된 회차의 판정이 다음 회차에 발송되는 것"이다. 그 판정 자체는 완결된 관측으로 만든 정상 값이다.
- commit 직전까지 fencing 하려면 DB fencing token 이 필요해 비용 대비 효과가 낮다. 이 근거를 runbook `account-pnl-alert.md`의 운영 주의에 남긴다.

## 4. 영향 범위

| 파일 | 변경 |
|---|---|
| `apps/batch/.../application/common/JobExecutor.kt` | `ApplicationListener<ContextClosedEvent>`, `Ordered`, 종료 분류 |
| `infrastructure/batch/.../exchange/binance/BinanceWebSocketClient.kt` | 종료 listener, 정지 후 payload 폐기 |
| `infrastructure/batch/.../exchange/bithumb/BithumbWebSocketClient.kt` | 같음 |
| `docs/runbooks/account-pnl-alert.md` | D4 근거 |
| `docs/runbooks/owner-ops-alert.md` 또는 batch 운영 runbook | 종료 중 결과는 SKIPPED 로 기록된다는 점 |

## 5. 위험

- **종료 중 진짜 실패를 숨길 수 있다.** 프로세스가 곧 내려가므로 다음 기동의 실행이 신호를 낸다. WARN 로그에는 원래 예외가 남는다.
- **이벤트 없이 컨텍스트 밖에서 쓰는 경우:** 테스트처럼 직접 생성한 `JobExecutor`는 `shuttingDown`이 계속 false 라 기존 동작과 같다.
