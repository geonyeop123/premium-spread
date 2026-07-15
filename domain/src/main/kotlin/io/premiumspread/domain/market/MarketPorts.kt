package io.premiumspread.domain.market

import io.premiumspread.domain.exchangerate.ExchangeRateSnapshot
import io.premiumspread.domain.ticker.Currency
import io.premiumspread.domain.ticker.Exchange
import io.premiumspread.domain.ticker.Quote
import io.premiumspread.domain.ticker.Ticker
import io.premiumspread.domain.ticker.TickerSnapshot
import java.math.BigDecimal
import java.time.Instant

interface TickerReadPort {
    fun findLatest(exchange: Exchange, quote: Quote): TickerSnapshot?
}

interface TickerWritePort {
    fun save(ticker: Ticker): Ticker
}

interface FxRateReadPort {
    fun findLatest(base: Currency, quote: Currency): ExchangeRateSnapshot?
}

interface FxRateWritePort {
    fun save(snapshot: ExchangeRateSnapshot)
}

/** 외부 환율 제공자의 DTO/비동기 구현을 application 계층에서 숨기는 동기식 경계다. */
fun interface ExchangeRateProvider {
    fun fetch(base: Currency, quote: Currency): ExchangeRateSnapshot
}

/** DB 저장 성공 뒤 호출되는 환율 cache writer다. */
fun interface FxRateCacheWritePort {
    fun save(snapshot: ExchangeRateSnapshot)
}

data class MarketTick(val exchange: Exchange, val quote: Quote, val price: BigDecimal, val observedAt: Instant)

fun interface TickerSink {
    fun accept(tick: MarketTick)
}

interface MarketTickerStream {
    fun start(sink: TickerSink)

    fun stop()
}

/** WebSocket ingestion buffer에 수신된 최신 tick을 조회한다. */
fun interface LatestMarketTickReadPort {
    fun findLatest(exchange: Exchange, quote: Quote): MarketTick?
}

/** 최신 tick의 current/seconds 저장을 캡슐화하는 infrastructure 경계다. */
interface TickerTimeSeriesWritePort {
    fun saveCurrent(tick: MarketTick)

    fun saveSecond(tick: MarketTick, sampledAt: Instant)
}

/** Ticker flush의 운영 신호를 기술 metric 구현과 분리하는 관찰 경계다. */
interface TickerFlushObserver {
    fun stale(exchange: Exchange)

    fun succeeded(exchange: Exchange)

    fun failed(exchange: Exchange, exception: Exception)

    companion object {
        val NONE: TickerFlushObserver = object : TickerFlushObserver {
            override fun stale(exchange: Exchange) = Unit

            override fun succeeded(exchange: Exchange) = Unit

            override fun failed(exchange: Exchange, exception: Exception) = Unit
        }
    }
}
