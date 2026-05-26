package io.premiumspread.infrastructure.websocket

import java.time.Duration

/**
 * WebSocket 연결 설정.
 *
 * @param exchange 메트릭 태그용 거래소 식별자 (예: "binance", "bithumb")
 * @param url WebSocket endpoint URL
 * @param subscribeMessage 연결 직후 송신할 구독 메시지 (없으면 null)
 * @param heartbeat 하트비트 정책
 * @param firstMessageTimeout 연결 후 첫 메시지를 기다리는 시간. 초과 시 메트릭 + 알람 + 강제 재연결 (silent outage 회복).
 * @param idleTimeout 첫 메시지 수신 이후, 후속 inbound 메시지 침묵 허용 한계. 초과 시 watchdog가 현재 연결을 강제 종료하고 재연결을 트리거한다. 기본 60초 (이슈 #57).
 * @param onMessage 메시지 수신 콜백 (메시지 문자열)
 */
data class WebSocketConnectionConfig(
    val exchange: String,
    val url: String,
    val subscribeMessage: String? = null,
    val heartbeat: HeartbeatPolicy = HeartbeatPolicy.ServerPingResponse,
    val firstMessageTimeout: Duration = Duration.ofSeconds(5),
    val idleTimeout: Duration = Duration.ofSeconds(60),
    val onMessage: (String) -> Unit,
)
