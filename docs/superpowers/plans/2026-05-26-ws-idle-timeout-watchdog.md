# Plan — WebSocket Idle-Timeout Watchdog (#57)

- 스펙: `docs/superpowers/specs/2026-05-26-ws-idle-timeout-watchdog-design.md`
- 베이스 브랜치: `dev`
- 피처 브랜치: `fix/issue-57-ws-idle-timeout-watchdog`

## Task 1 — `WebSocketConnectionConfig.idleTimeout` 필드 추가

**파일:** `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionConfig.kt`

**변경:** `idleTimeout: Duration = Duration.ofSeconds(60)` 필드 추가 + KDoc 갱신.

```kotlin
data class WebSocketConnectionConfig(
    val exchange: String,
    val url: String,
    val subscribeMessage: String? = null,
    val heartbeat: HeartbeatPolicy = HeartbeatPolicy.ServerPingResponse,
    val firstMessageTimeout: Duration = Duration.ofSeconds(5),
    /**
     * 첫 메시지 수신 이후, 후속 메시지 침묵 허용 한계.
     * 초과 시 watchdog이 현재 연결을 강제 종료하고 재연결을 트리거한다.
     * 기본 60초 — Bithumb ClientPing(30s) 대비 2x 안전 여유.
     */
    val idleTimeout: Duration = Duration.ofSeconds(60),
    val onMessage: (String) -> Unit,
)
```

- [ ] `idleTimeout` 필드 추가
- [ ] KDoc 업데이트 (전체 파일)

## Task 2 — `WebSocketConnectionManager` watchdog 구현

**파일:** `apps/batch/src/main/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManager.kt`

**변경 요약:**

1. 새 필드 추가:
   - `lastMessageAt = AtomicLong(0L)`
   - `watchdogDisposable = AtomicReference<Disposable?>()`
   - `forceReconnectArmed = AtomicBoolean(false)`
   - 생성자 파라미터 `watchdogCheckInterval: Duration = Duration.ofSeconds(5)` (테스트에서 짧게 override)

2. `connectWithRetry()` 내 핸드셰이크 직후(`client.execute` 람다 진입 시점)에:
   - `lastMessageAt.set(System.currentTimeMillis())` — 초기값
   - watchdog 시작 (Flux.interval). 매 체크에서 `now - lastMessageAt > idleTimeout`이면:
     - `alertService.sendCriticalAlert("[exchange] WebSocket idle ${idleSec}s — forcing reconnect")`
     - `forceReconnectArmed.set(true)` 후 `subscription.getAndSet(null)?.dispose()`

3. `session.receive().doOnNext`에서 메시지 수신 시 `lastMessageAt.set(System.currentTimeMillis())` 갱신 (기존 첫 메시지 처리 포함).

4. `firstMessageTimeout` 발동 콜백을 강제 재연결 트리거로 확장:
   - 기존: alert만 호출.
   - 변경: alert + `forceReconnectArmed.set(true)` + `subscription.getAndSet(null)?.dispose()` (idle watchdog과 동일 경로).

5. `doFinally`의 CANCEL 가드 수정:
   - 기존: `if (signalType == SignalType.CANCEL) return@doFinally`
   - 변경: `if (signalType == SignalType.CANCEL && !forceReconnectArmed.compareAndSet(true, false)) return@doFinally`
   - force-reconnect armed한 경우만 CANCEL을 reconnect 트리거로 인식.

6. `stop()`에서 `watchdogDisposable.getAndSet(null)?.dispose()` 추가.

7. `doFinally` cleanup 영역에 `watchdogDisposable` cleanup 추가 (CANCEL 신호에서도 dispose).

- [ ] 새 필드/AtomicReference 추가
- [ ] 핸드셰이크 직후 `lastMessageAt = now`로 초기화하고 watchdog 시작 (첫 메시지 도착 전에도 watchdog가 idle 감지)
- [ ] 첫 메시지/후속 메시지마다 `lastMessageAt` 갱신
- [ ] watchdog check 로직 (idle 초과 시 alert + forceReconnectArmed + dispose)
- [ ] `firstMessageTimeout` 발동 시에도 강제 재연결 트리거 (handshake-success/zero-message 케이스 회복)
- [ ] `doFinally` CANCEL 가드를 `forceReconnectArmed` CAS로 확장
- [ ] `stop()` / `doFinally`에서 watchdog cleanup

## Task 3 — `WebSocketConnectionManagerTest` 케이스 추가

**파일:** `apps/batch/src/test/kotlin/io/premiumspread/infrastructure/websocket/WebSocketConnectionManagerTest.kt`

**케이스 추가 (AssertJ + Awaitility):**

1. `첫 메시지 후 idleTimeout 경과 시 강제 재연결을 시도하고 ws_reconnect_attempt 메트릭과 critical alert가 트리거된다`
   - MockWebServer: 1차 핸드셰이크 후 즉시 메시지 1건 push, 이후 침묵 → 2차 핸드셰이크는 메시지 push.
   - `idleTimeout = Duration.ofMillis(500)`, `watchdogCheckInterval = Duration.ofMillis(100)`로 짧게 설정.
   - 검증:
     - `ws.reconnect.attempt{exchange=test}` >= 1.0
     - `capturedAlerts`가 idle 메시지 포함
     - 두 번째 연결에서 새 메시지 수신

2. `메시지가 idleTimeout 내 지속 도착하면 watchdog이 발동하지 않는다`
   - MockWebServer: 핸드셰이크 후 200ms 간격으로 메시지 5회 push.
   - `idleTimeout = Duration.ofMillis(500)`, `watchdogCheckInterval = Duration.ofMillis(100)`.
   - 검증:
     - reconnect 메트릭 0 (또는 시작 직후 값 그대로)
     - alert 비어있음

3. `stop()이 watchdog disposable을 정리한다 (간접 검증)`
   - 메시지 1회 수신 후 stop → 잠시 대기 → reconnect 메트릭 증가 없음 + capturedAlerts에 idle 알람 없음.

- [ ] 케이스 1 — silent outage 시뮬레이션 (첫 메시지 후 침묵)
- [ ] 케이스 1b — 핸드셰이크 후 zero-message 침묵 → idleTimeout 또는 firstMessageTimeout 발동으로 강제 재연결
- [ ] 케이스 2 — 정상 메시지 지속 시 발동 안 함
- [ ] 케이스 3 — stop() 후 watchdog 잔여 없음 (alert/reconnect 미발생)

## Task 4 — 빌드/테스트 검증

```bash
./gradlew :apps:batch:compileKotlin
./gradlew :apps:batch:test
```

- [ ] compileKotlin 성공
- [ ] test 통과 (신규 케이스 + 기존 케이스)
