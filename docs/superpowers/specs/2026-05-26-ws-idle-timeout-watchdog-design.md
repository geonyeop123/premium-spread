# WebSocket Idle-Timeout Watchdog — Design Spec

- 이슈: [#57](https://github.com/geonyeop123/premium-spread/issues/57)
- 베이스 브랜치: `dev`
- 피처 브랜치: `fix/issue-57-ws-idle-timeout-watchdog`
- 작성일: 2026-05-26

## 1. 배경 (Why)

4일 누적 컨테이너 검증 중 Binance WebSocket 수집이 **30시간째 silent dead** 상태로 발견됨.

- `ws_connection_state{binance}=1.0` (살아있다고 주장)
- `ws_last_message_age{binance}=111,250초(≈30.9h)`
- `ws_reconnect_attempt_total{binance}` 미존재 — 재시도 0회

원인은 `BinanceWebSocketClient`의 heartbeat 정책이 `HeartbeatPolicy.ServerPingResponse`(수동형 — 서버 ping에만 응답)인 점. TCP가 silently zombie 상태로 빠지거나 peer가 FIN 없이 종료하거나 서버가 ping을 중단하면 **클라이언트가 끊김을 감지하지 못해 영구 침묵**한다.

대조: `BithumbWebSocketClient`는 `HeartbeatPolicy.ClientPing(30s)`(능동형) 덕분에 4일간 2회 정상 재연결.

현재 `.ai/rules/batch.md §6`는 `ws.last.message.age` Gauge 노출 + 외부 알람만 제공하고, **인앱 자동 회복 경로가 없다**.

## 2. 목표 (What)

`WebSocketConnectionManager`에 **idle-timeout watchdog**을 도입해 silent outage 발생 시 자동 강제 재연결한다.

- 마지막 메시지 수신 시각(`lastMessageAt`) 추적
- 주기적(5초) 점검 → `now - lastMessageAt > idleTimeout`(기본 60초) 시 현재 연결을 강제 종료
- 강제 종료 후 기존 `doFinally → connectWithRetry()` 재연결 경로가 자동 실행
- 강제 종료 시점에 `AlertService.sendCriticalAlert` 호출 (`ws.reconnect.attempt`는 기존 reconnect 경로에서 자동 증가)
- `WebSocketConnectionConfig`에 `idleTimeout: Duration` 필드 추가 (기본 `Duration.ofSeconds(60)`)

## 3. 비목표 (What NOT)

- heartbeat 정책 자체 변경 (passive ↔ active). watchdog만으로 silent outage 회복.
- 로깅/알람 채널 변경. 기존 `AlertService` 그대로 사용.
- 메트릭 스키마 변경. 기존 `ws.reconnect.attempt`, `ws.last.message.age` 활용.

## 4. 설계

### 4.1 새로운 필드

`WebSocketConnectionConfig`:

```kotlin
data class WebSocketConnectionConfig(
    val exchange: String,
    val url: String,
    val subscribeMessage: String? = null,
    val heartbeat: HeartbeatPolicy = HeartbeatPolicy.ServerPingResponse,
    val firstMessageTimeout: Duration = Duration.ofSeconds(5),
    val idleTimeout: Duration = Duration.ofSeconds(60),  // NEW
    val onMessage: (String) -> Unit,
)
```

`WebSocketConnectionManager`:

```kotlin
private val lastMessageAt = AtomicLong(0L)              // epoch ms; 0 = pre-first-message
private val watchdogDisposable = AtomicReference<Disposable?>()
private val forceReconnectArmed = AtomicBoolean(false)  // distinguishes watchdog-triggered cancel from stop()-triggered cancel
private val watchdogCheckInterval: Duration = Duration.ofSeconds(5)
```

### 4.2 동작 흐름

#### 정상 운영 시
1. `connectWithRetry()` 호출 → 핸드셰이크 성공.
2. 핸드셰이크 성공 시점에:
   - `lastMessageAt.set(System.currentTimeMillis())` — 초기값 부여 (handshake-success/zero-message 케이스 대응)
   - watchdog 시작: `Flux.interval(watchdogCheckInterval).subscribe { checkIdle() }`
3. `session.receive().doOnNext { ... }`에서 메시지 수신 시:
   - `firstReceived` 플래그 설정 (기존 로직, 첫 메시지 한정)
   - `lastMessageAt.set(System.currentTimeMillis())` — 갱신
4. watchdog는 매 5초마다 `now - lastMessageAt`을 체크.

#### Silent outage 발생 시
1. 메시지가 멈춰서 `now - lastMessageAt > idleTimeout`(60초) 충족.
2. `watchdog.checkIdle()`이 다음을 수행:
   - `forceReconnectArmed.set(true)` — 다음 CANCEL은 stop()이 아닌 watchdog 강제 재연결임을 표시
   - `alertService.sendCriticalAlert("[exchange] WebSocket idle ${idleSec}s — forcing reconnect")`
   - `subscription.getAndSet(null)?.dispose()` — 현재 연결 강제 종료
3. `doFinally`가 트리거되며 `SignalType.CANCEL`을 받음:
   - `forceReconnectArmed.compareAndSet(true, false)`이 `true` → CANCEL 가드를 우회하고 reconnect 경로 진입
   - `forceReconnectArmed`가 `false` → 기존대로 `stop()`에 의한 취소로 간주, 종료
4. 기존 backoff 로직이 `ws.reconnect.attempt`를 증가시키고 `connectWithRetry()` 재호출.

#### stop() 시
- `running.set(false)` + 모든 disposable 정리.
- watchdog disposable도 함께 dispose.
- `forceReconnectArmed`는 `false`이므로 doFinally가 CANCEL을 정상 stop으로 인식.

### 4.3 firstMessageTimeout과의 관계

- `firstMessageTimeout`(5s): 핸드셰이크 후 첫 메시지가 오지 않으면 발동 (1회성). **본 이슈로 강제 재연결 트리거를 함께 수행**한다.
- `idleTimeout`(60s): 핸드셰이크 직후부터 watchdog가 동작하며, `now - lastMessageAt > idleTimeout`이면 강제 재연결 트리거.
- 두 메커니즘은 시간 스케일이 다르다 — firstMessageTimeout(5s)이 항상 먼저 발동, idleTimeout(60s)은 그 이후 정상 메시지 수신 중 침묵이 발생했을 때 발동.
- firstMessageTimeout 또한 idleTimeout과 동일한 force-reconnect 경로를 공유한다 (`forceReconnectArmed` CAS + `subscription.dispose()`). 따라서 zero-message 핸드셰이크 케이스도 재연결로 회복된다.

### 4.4 거래소별 override

- watchdog는 **inbound 메시지 도착**으로만 `lastMessageAt`을 갱신한다 (outbound ClientPing은 무관).
- Bithumb 24H ticker 채널은 healthy 상태에서 초당 1건 이상의 ticker 프레임을 수신하므로 60초 inbound 침묵은 명백한 이상 신호 (실증: 기존 `recordStale` 임계가 10초인데 4일간 false-positive 없이 운영).
- Binance bookTicker는 초당 수십~수백 건 push이므로 60초 침묵은 명백한 이상.
- 거래소별 override가 필요하면 `WebSocketConnectionConfig` 인스턴스화 시 `idleTimeout = Duration.ofSeconds(N)` 설정 (현재는 기본값만으로 충분).

## 5. 수용 기준 (Acceptance)

- [ ] `WebSocketConnectionConfig.idleTimeout: Duration` 필드 추가, 기본값 `Duration.ofSeconds(60)`.
- [ ] `WebSocketConnectionManager`에 watchdog 구현: 핸드셰이크 직후부터 동작, `idleTimeout` 초과 시 강제 재연결.
- [ ] `firstMessageTimeout` 발동 시에도 동일한 force-reconnect 경로 진입 (handshake-success/zero-message 케이스 회복).
- [ ] 강제 재연결 시:
  - `AlertService.sendCriticalAlert` 호출.
  - `ws.reconnect.attempt{exchange}` 증가 (기존 경로 활용).
  - 새 연결로 재진입 후 정상 메시지 수신 시 watchdog 재시작.
- [ ] `stop()` 호출 시 watchdog disposable 정리, reconnect 메트릭 미증가.
- [ ] 통합 테스트: 핸드셰이크 후 메시지 0건 시뮬레이션 → firstMessageTimeout/idleTimeout 경과 후 재연결 시도 검증.
- [ ] 통합 테스트: 첫 메시지 후 후속 메시지 침묵 시뮬레이션 → idleTimeout 경과 후 재연결 시도 검증.
- [ ] 기존 통합 테스트 회귀 없음 (Binance/Bithumb 클라이언트 호환).
- [ ] `./gradlew :apps:batch:compileKotlin :apps:batch:test` 통과.

## 6. 위험 및 완화

| 위험 | 완화 |
|------|------|
| watchdog의 dispose가 stop()과 구분 안 됨 | `forceReconnectArmed` AtomicBoolean으로 CAS 가드 구분 |
| 첫 메시지 후 즉시 stop() 호출 시 watchdog 잔여 | `stop()`에서 `watchdogDisposable` 명시적 dispose |
| 60초 inbound 침묵이 빗썸의 정상 idle 윈도우와 겹칠 위험 | watchdog는 inbound 메시지만 추적. Bithumb 24H ticker 채널은 healthy 상태에서 초당 1건 이상 ticker 프레임 수신 (기존 `recordStale` 10초 임계가 4일간 false-positive 0건) → 60초 inbound 침묵은 명백한 이상 |
| 재연결 직후 다시 idle 발생 | 다음 cycle에서 watchdog이 재발동, alert 반복 — 운영 알람 노이즈는 다음 단계에서 조정 (이번 이슈 범위 외) |

## 7. 관련 문서

- `.ai/rules/batch.md §6` — silent ingestion outage 방어 정책 (현재는 알람만, 본 이슈로 자동 회복 추가)
- 이슈 #57 — 실증 컨텍스트 (Binance 30h silent outage)
