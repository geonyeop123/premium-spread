package io.premiumspread.infrastructure.websocket

import java.time.Duration

/**
 * 거래소별 하트비트 정책.
 *
 * - [None]: 별도 하트비트 송수신 없음 (사용 안 권장)
 * - [ServerPingResponse]: 서버가 ping frame을 보내면 클라이언트는 자동 pong (Netty 기본)
 * - [ClientPing]: 클라이언트가 일정 주기로 텍스트 ping 메시지를 송신 (빗썸 등)
 */
sealed interface HeartbeatPolicy {
    data object None : HeartbeatPolicy
    data object ServerPingResponse : HeartbeatPolicy
    data class ClientPing(val interval: Duration, val pingMessage: String) : HeartbeatPolicy
}
