package io.premiumspread.infrastructure.batch.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import io.premiumspread.domain.job.OperatorAlert
import io.premiumspread.infrastructure.batch.exchange.binance.BinanceWebSocketClient
import io.premiumspread.infrastructure.batch.ingestion.binance.BinanceTickerIngestion
import io.premiumspread.infrastructure.batch.websocket.WebSocketMetrics
import io.premiumspread.infrastructure.batch.websocket.WebSocketStreamProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.AnnotationConfigUtils
import org.springframework.context.support.GenericApplicationContext
import java.net.URI
import java.time.Clock
import java.util.function.Supplier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WebSocketShutdownOrderingTest {
    @Test
    fun `클라이언트는 최후 phase lifecycle 인 Redis 연결 정지보다 먼저 정지한다`() {
        val registry = SimpleMeterRegistry()
        val ingestion = mockk<BinanceTickerIngestion>(relaxed = true)
        val properties = WebSocketStreamProperties(
            binance = WebSocketStreamProperties.Stream(
                URI.create("ws://127.0.0.1:1/ws/btcusdt@bookTicker"),
                "BTC",
                "USDT",
            ),
        )
        val clientRef = AtomicReference<BinanceWebSocketClient>()
        val stopCalls = AtomicInteger()
        val connectionLike = LastPhaseLifecycle {
            stopCalls.incrementAndGet()
            clientRef.get().handlePayload(PAYLOAD)
        }

        val context = GenericApplicationContext()
        try {
            context.apply {
                AnnotationConfigUtils.registerAnnotationConfigProcessors(this)
                beanFactory.registerSingleton("ingestion", ingestion)
                beanFactory.registerSingleton("metrics", WebSocketMetrics(registry, Clock.systemUTC()))
                beanFactory.registerSingleton("operatorAlert", OperatorAlert { })
                beanFactory.registerSingleton("objectMapper", ObjectMapper())
                beanFactory.registerSingleton("meterRegistry", registry)
                beanFactory.registerSingleton("properties", properties)
                registerBean("binanceClient", BinanceWebSocketClient::class.java, *emptyArray<Any>())
                registerBean("connectionLike", LastPhaseLifecycle::class.java, Supplier { connectionLike })
                refresh()
                clientRef.set(getBean(BinanceWebSocketClient::class.java))
            }
            // 종료 전에는 같은 payload 가 ingestion 까지 전달된다 (payload 유효성 증명).
            clientRef.get().handlePayload(PAYLOAD)
            verify(exactly = 1) { ingestion.onMessage(any()) }
        } finally {
            context.close()
        }

        assertThat(stopCalls.get()).isEqualTo(1)
        // lifecycle stop 안에서의 handlePayload 는 추가 전달을 만들지 않는다.
        verify(exactly = 1) { ingestion.onMessage(any()) }
    }

    private class LastPhaseLifecycle(private val onStop: () -> Unit) : SmartLifecycle {
        private val running = AtomicBoolean(false)

        override fun start() = running.set(true)

        override fun stop() {
            running.set(false)
            onStop()
        }

        override fun isRunning(): Boolean = running.get()

        override fun getPhase(): Int = Int.MAX_VALUE
    }

    private companion object {
        const val PAYLOAD = """{"E":1715470800000,"s":"BTCUSDT","b":"100.0","a":"102.0"}"""
    }
}
