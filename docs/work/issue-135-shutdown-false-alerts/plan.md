# #135 종료 오탐 정리 — 구현 계획 (결정 수준)

- Spec: `docs/work/issue-135-shutdown-false-alerts/design.md`, 완료 기준 `dod.md` (FROZEN 2026-11-12)
- Base: `dev` @ fa94fdd, branch `fix/issue-135-shutdown-false-alerts`

## Global Constraints

- Kotlin 2 / Spring Boot 3.5, Spring 6.2. ktlint 1.8.0, detekt 규칙: `ReturnCount` ≤ 4, `TooManyFunctions` 20.
- 종료 신호가 없을 때의 동작은 한 줄도 바뀌지 않아야 한다. 기존 `JobExecutorTest`와 `WebSocketConnectionManagerRegressionTest`는 **수정 없이** 통과해야 한다.
- 새 모듈 의존을 추가하지 않는다. `apps:batch`와 `infrastructure:batch`는 각자 `org.springframework.context.ApplicationListener<ContextClosedEvent>`와 `org.springframework.core.Ordered`만 쓴다.
- 커밋은 한국어 `<type>: <subject>` + bullet 본문, Co-Authored-By 없음.
- T1 기준은 테스트를 먼저 쓰고, 기대한 assertion 실패(RED)를 확인한 뒤 구현한다.

## Task 1 — `JobExecutor` 종료 분류 (AC1)

**파일**
- `apps/batch/src/main/kotlin/io/premiumspread/application/common/JobExecutor.kt` (수정)
- `apps/batch/src/test/kotlin/io/premiumspread/application/common/JobExecutorShutdownTest.kt` (신규)

**공개 시그니처**
- `class JobExecutor(...) : ApplicationListener<ContextClosedEvent>, Ordered`
- `override fun onApplicationEvent(event: ContextClosedEvent)` — `shuttingDown`(AtomicBoolean)을 true 로 바꾼다
- `override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE`
- `JobResult.Skipped`의 reason 문자열 상수 `"shutdown"`. `JobExecutor`의 companion 에 `const val SHUTDOWN = "shutdown"`으로 둔다

**결정**
- `execute()` 시작에서 `shuttingDown`이면 lock 획득 전에 `Skipped(SHUTDOWN)`을 기록하고 반환한다.
- 결과 분류는 lock 획득 예외 경로, `awaited.actionMayStillRun` 경로, `completeAfterAction` 경로에 각각 적용한다. 하나의 private 함수 `shutdownAware(config, result): JobResult`가 이 분류를 맡는다.
  - 규칙: `shuttingDown`이고 결과가 `Failure`면 원래 예외를 WARN 로그로 남기고 `Skipped(SHUTDOWN)`을 반환한다. 그 밖에는 결과를 그대로 반환한다.
  - 이 분류는 recordCompletion·alert 를 호출하기 **전에** 적용한다. 그래야 SKIPPED 로 기록되고 알림이 가지 않는다.
- lock 해제는 지금과 같다: action 종료 뒤 해제, `actionMayStillRun`이면 보류 해제.
- 호출 스레드 `InterruptedException` 경로가 interrupt 플래그를 되살리는 기존 동작을 유지한다.
- `release` 실패로 생긴 `Failure`도 종료 중이면 같은 규칙을 탄다.

**테스트 (`JobExecutorShutdownTest`)** — 각 테스트는 실제 `JobExecutor`를 쓰고, `JobLock`·`JobRunRecorder`·`OperatorAlert`는 mockk 를 쓴다(기존 `JobExecutorTest` 설정과 같다).
1. `종료 신호 뒤 새 실행은 lock과 action 없이 shutdown으로 건너뛴다`: `tryAcquire` 0회, action 0회, recorder `completed(..., SKIPPED, ..., "shutdown")`, `send` 0회.
2. `실행 중 종료 신호 뒤 action 예외는 shutdown 으로 기록하고 알리지 않는다`: action 은 latch 로 대기하다가 이벤트 뒤 `RuntimeException("LettuceConnectionFactory is STOPPING")`을 던진다. 결과 `Skipped("shutdown")`, SKIPPED 기록, `send` 0회, `release` 1회.
3. `실행 중 종료 신호 뒤 InterruptedException도 shutdown 으로 기록한다`: 2와 같고 예외만 `InterruptedException`.
4. `종료 중 timeout 은 shutdown 으로 기록한다`: executionTimeout 을 짧게 두고, 이벤트를 보낸 뒤 timeout 이 나게 한다. 결과 `Skipped`, `send` 0회.
5. `종료 중 대기하던 호출 스레드 interrupt 는 shutdown 으로 끝나고 플래그를 유지한다`: 별도 스레드에서 `execute`를 호출하고, 이벤트를 보낸 뒤 그 스레드를 interrupt 한다. 결과 `Skipped("shutdown")`, 그 스레드에서 관측한 `Thread.currentThread().isInterrupted == true`.
6. `종료 신호가 없으면 같은 예외는 FAILED 로 기록하고 알린다`: 대조군. `Failure`, FAILED, `send` 1회.
7. `컨텍스트 종료 시 lifecycle stop 보다 먼저 shutdown 으로 분류한다`: `GenericApplicationContext`에 `JobExecutor`(mock 의존 포함)와 phase `Integer.MAX_VALUE`인 `SmartLifecycle` 대역을 등록하고 `refresh()` → `close()`. 대역의 `stop()` 안에서 `execute`를 호출한 결과가 `Skipped("shutdown")`이다.

**검증 명령**: `./gradlew :apps:batch:test --tests io.premiumspread.application.common.JobExecutorShutdownTest --tests io.premiumspread.application.common.JobExecutorTest --offline --no-daemon`

## Task 2 — WebSocket 클라이언트 조기 정지 (AC2·AC3)

**파일**
- `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/binance/BinanceWebSocketClient.kt` (수정)
- `infrastructure/batch/src/main/kotlin/io/premiumspread/infrastructure/batch/exchange/bithumb/BithumbWebSocketClient.kt` (수정)
- `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/WebSocketClientShutdownTest.kt` (신규)
- `infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange/WebSocketShutdownOrderingTest.kt` (신규)

**공개 시그니처** (두 클라이언트 동일)
- `: MarketTickerStream, ApplicationListener<ContextClosedEvent>, Ordered`
- `override fun onApplicationEvent(event: ContextClosedEvent)` → `stop()`
- `override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE`

**결정**
- 클라이언트에 `stopped`(AtomicBoolean)를 둔다. `stop()`이 먼저 true 로 바꾸고 `manager.stop()`을 부른다. `stop()`은 멱등이다. 이벤트와 `@PreDestroy`가 둘 다 호출해도 정지 로그는 1회만 남긴다.
- `handlePayload`는 맨 앞에서 `stopped`이면 아무것도 하지 않고 반환한다. parse 도, 메트릭도 하지 않는다.
- `start(sink)`나 `startDefault()`로 다시 시작하는 경로는 이번 범위가 아니다. `stopped`를 되돌리지 않는다. 종료는 프로세스 끝이기 때문이다.
- `WebSocketConnectionManager`는 바꾸지 않는다. 재연결 차단은 기존 `manager.stop()`의 `running=false`가 맡고, 기존 회귀 테스트가 이를 고정한다.

**테스트**
- `WebSocketClientShutdownTest` (두 클라이언트 모두, 기존 `BinanceWebSocketClientTest` 생성 방식을 따른다. 엔드포인트는 연결을 시도하지 않게 `@PostConstruct`를 호출하지 않는다)
  1. `바이낸스 클라이언트는 종료 신호 전 payload 를 ingestion 에 넘긴다`: `onMessage` 1회
  2. `바이낸스 클라이언트는 종료 신호 뒤 payload 를 넘기지 않는다`: `onMessage` 0회
  3. `바이낸스 클라이언트는 종료 신호 뒤 외부 sink 에도 넘기지 않는다`: `start(sink)` 대신 `externalSink`를 쓰는 경로. 연결 시도를 피하려면 sink 주입 방법이 필요하다. `start(sink)`가 연결을 시도하면 테스트 대상 범위에서 빼고, 정지 플래그가 sink 분기보다 앞에 있다는 것을 1·2로 고정한다. **판정은 구현자가 하고 보고서에 적는다**
  4~5. 빗썸 클라이언트 같은 쌍. 빗썸 payload 형식은 기존 `BithumbWebSocketClientTest`를 따른다
- `WebSocketShutdownOrderingTest`
  - `GenericApplicationContext`에 `BinanceWebSocketClient`를 등록한다. 의존은 mock 이고, 엔드포인트는 `ws://127.0.0.1:1/…`처럼 즉시 실패하는 로컬 주소다. 여기에 phase `Integer.MAX_VALUE` `SmartLifecycle` 대역을 함께 등록하고 `refresh()` → `close()`.
  - 대역의 `stop()` 안에서 `client.handlePayload(valid)`를 호출하고, 그 시점까지 ingestion `onMessage`가 0회임을 단언한다.
  - `@PostConstruct` 연결 시도가 실패·재연결 로그를 내도 테스트는 결정적이어야 한다. 단언은 handlePayload 전달 여부만 본다.

**검증 명령**: `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.*' --tests 'io.premiumspread.infrastructure.batch.websocket.*' --tests 'io.premiumspread.infrastructure.batch.ingestion.*' --offline --no-daemon`

## Task 3 — 문서와 AC4 (메인 세션)

- runbook `docs/runbooks/account-pnl-alert.md`: 운영 주의에 D4(TOCTOU를 고치지 않는 근거)를 넣는다.
- `docs/runbooks/owner-ops-alert.md`: 종료 중 Job 결과가 `SKIPPED(shutdown)`이고 streak 이 바뀌지 않는다는 점을 넣는다.
- `.ai/PROJECT_STATUS.md` 등은 tech-docs 서브에이전트가 맡는다.
- AC4: 로컬 batch 를 기동하고 1분 뒤 SIGTERM 으로 종료한다. 로그는 `$CLAUDE_JOB_DIR/tmp/batch-135-shutdown.log`. `Commencing graceful shutdown` 이후 `[ALERT][CRITICAL]` 건수를 센다.
