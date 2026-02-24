# API E2E 테스트케이스 목록

## 원칙

- **No internal mocking**: 내부 컴포넌트(Service, Facade, Repository, Cache) 일절 mocking 금지
- **실제 인프라**: TestContainers MySQL + Redis로 실제 저장소 사용
- **진입점**: MockMvc HTTP 요청 → 응답 body/status 검증 + DB/Cache 부수효과 검증
- **사전 데이터 준비**: DB는 Repository 직접 사용, Redis는 RedisTemplate 직접 조작
- **격리**: `@BeforeEach`에서 DB truncate + Redis flush

---

## 1. TickerControllerE2ETest

### 1-1. POST /api/v1/tickers — 티커 저장 성공
```
Given: 빈 DB
When:  POST /api/v1/tickers (BITHUMB, BTC, KRW, 129555000)
Then:  HTTP 201
       응답 body에 id, exchange, symbol, price 포함
       DB ticker 테이블에 1건 저장됨
```

### 1-2. POST /api/v1/tickers — 잘못된 exchange → 400
```
Given: 빈 DB
When:  POST /api/v1/tickers (exchange = "INVALID_EXCHANGE")
Then:  HTTP 400
       code = "INVALID_ARGUMENT"
```

---

## 2. PremiumControllerE2ETest

### 2-1. POST /api/v1/premiums/calculate/{symbol} — 정상 계산 및 저장
```
Given: DB에 UPBIT/BTC/KRW 티커, BINANCE/BTC/USD 티커, FX_PROVIDER/USD/KRW 티커 준비
When:  POST /api/v1/premiums/calculate/BTC
Then:  HTTP 200
       응답 body에 id, symbol="BTC", premiumRate 포함
       DB premium 테이블에 1건 저장됨
       premiumRate = (korea_price / (foreign_price * fx_rate) - 1) * 100 과 일치
```

### 2-2. POST /api/v1/premiums/calculate/{symbol} — 한국 티커 없음 → 404
```
Given: DB에 BINANCE/BTC/USD, FX 티커만 존재 (UPBIT/BTC 없음)
When:  POST /api/v1/premiums/calculate/BTC
Then:  HTTP 404
       code = "TICKER_NOT_FOUND"
       message에 "Korea ticker" 포함
```

### 2-3. POST /api/v1/premiums/calculate/{symbol} — 해외 티커 없음 → 404
```
Given: DB에 UPBIT/BTC/KRW, FX 티커만 존재 (BINANCE/BTC 없음)
When:  POST /api/v1/premiums/calculate/BTC
Then:  HTTP 404
       code = "TICKER_NOT_FOUND"
       message에 "Foreign ticker" 포함
```

### 2-4. POST /api/v1/premiums/calculate/{symbol} — FX 티커 없음 → 404
```
Given: DB에 UPBIT/BTC/KRW, BINANCE/BTC/USD만 존재 (FX 없음)
When:  POST /api/v1/premiums/calculate/BTC
Then:  HTTP 404
       code = "TICKER_NOT_FOUND"
       message에 "FX ticker" 포함
```

### 2-5. GET /api/v1/premiums/current/{symbol} — 캐시 hit → 캐시 데이터 반환
```
Given: Redis에 premium:btc 해시 저장 (rate, korea_price, foreign_price, foreign_price_krw, fx_rate)
       DB에는 premium 없음
When:  GET /api/v1/premiums/current/BTC
Then:  HTTP 200
       응답 body의 premiumRate = Redis 캐시의 rate
       koreaPrice = Redis 캐시의 korea_price
```

### 2-6. GET /api/v1/premiums/current/{symbol} — 캐시 miss + DB hit → DB fallback 반환
```
Given: Redis 캐시 비어 있음
       DB에 premium 1건 + 연관 ticker 3건 준비
When:  GET /api/v1/premiums/current/BTC
Then:  HTTP 200
       응답 body의 premiumRate = DB premium의 premiumRate
       koreaPrice, foreignPrice, fxRate 값 검증
```

### 2-7. GET /api/v1/premiums/current/{symbol} — 캐시 miss + DB miss → 404
```
Given: Redis 비어 있음, DB 비어 있음
When:  GET /api/v1/premiums/current/BTC
Then:  HTTP 404
```

### 2-8. GET /api/v1/premiums/history/{symbol} — 기간 내 데이터 반환
```
Given: DB에 BTC premium 3건 (2024-01-01, 2024-01-02, 2024-02-01)
When:  GET /api/v1/premiums/history/BTC?from=2024-01-01T00:00:00Z&to=2024-01-31T23:59:59Z
Then:  HTTP 200
       응답 배열 size = 2 (2024-01 내 데이터만)
       observedAt 오름차순 정렬됨
```

### 2-9. GET /api/v1/premiums/history/{symbol} — 기간 내 데이터 없음 → 빈 배열
```
Given: DB에 BTC premium 1건 (2024-01-01)
When:  GET /api/v1/premiums/history/BTC?from=2024-03-01T00:00:00Z&to=2024-03-31T23:59:59Z
Then:  HTTP 200
       응답 배열 size = 0
```

---

## 3. PositionControllerE2ETest

### 3-1. POST /api/v1/positions — 포지션 오픈: DB 저장 + Redis 캐시 갱신
```
Given: 빈 DB, 빈 Redis
When:  POST /api/v1/positions
       { symbol: "BTC", exchange: "UPBIT", quantity: 0.5,
         entryPrice: 129555000, entryFxRate: 1432.6,
         entryPremiumRate: 1.28, entryObservedAt: "2024-01-01T00:00:00Z" }
Then:  HTTP 201
       응답 body에 id, symbol="BTC", status="OPEN" 포함
       DB position 테이블에 1건 저장, status=OPEN
       Redis position:open:exists = "true"
       Redis position:open:count = "1"
```

### 3-2. POST /api/v1/positions — 잘못된 exchange → 400
```
Given: 빈 DB
When:  POST /api/v1/positions (exchange = "INVALID_EXCHANGE")
Then:  HTTP 400
       code = "INVALID_ARGUMENT"
```

### 3-3. GET /api/v1/positions/{id} — 존재하는 포지션 조회
```
Given: DB에 포지션 1건 (id=N, status=OPEN)
When:  GET /api/v1/positions/{N}
Then:  HTTP 200
       응답 body의 id = N, status = "OPEN"
```

### 3-4. GET /api/v1/positions/{id} — 없는 포지션 → 404
```
Given: 빈 DB
When:  GET /api/v1/positions/999
Then:  HTTP 404
```

### 3-5. GET /api/v1/positions — 열린 포지션 목록 조회
```
Given: DB에 OPEN 포지션 2건, CLOSED 포지션 1건
When:  GET /api/v1/positions
Then:  HTTP 200
       응답 배열 size = 2 (OPEN 포지션만)
       모든 item의 status = "OPEN"
```

### 3-6. GET /api/v1/positions — 열린 포지션 없음 → 빈 배열
```
Given: 빈 DB
When:  GET /api/v1/positions
Then:  HTTP 200
       응답 배열 size = 0
```

### 3-7. GET /api/v1/positions/{id}/pnl — 캐시 hit으로 PnL 계산
```
Given: DB에 OPEN 포지션 (entryPremiumRate=3.00)
       Redis에 premium:btc 캐시 (rate=1.00)
When:  GET /api/v1/positions/{id}/pnl
Then:  HTTP 200
       premiumDiff = -2.00 (1.00 - 3.00)
       isProfit = true
```

### 3-8. GET /api/v1/positions/{id}/pnl — DB fallback으로 PnL 계산
```
Given: DB에 OPEN 포지션 (entryPremiumRate=1.00) + premium 및 연관 ticker 3건
       Redis 비어 있음
When:  GET /api/v1/positions/{id}/pnl
Then:  HTTP 200
       currentPremiumRate = DB premium의 premiumRate
       premiumDiff = currentPremiumRate - 1.00
```

### 3-9. GET /api/v1/positions/{id}/pnl — 프리미엄 없음 → 404
```
Given: DB에 OPEN 포지션, Redis 비어 있음, DB premium 없음
When:  GET /api/v1/positions/{id}/pnl
Then:  HTTP 404
       code = "PREMIUM_NOT_FOUND"
```

### 3-10. POST /api/v1/positions/{id}/close — 포지션 청산: DB 상태 변경 + Redis 캐시 갱신
```
Given: DB에 OPEN 포지션 1건
       Redis position:open:exists = "true", count = "1"
When:  POST /api/v1/positions/{id}/close
Then:  HTTP 200
       응답 body의 status = "CLOSED"
       DB position의 status = CLOSED
       Redis position:open:exists = "false"
       Redis position:open:count = "0"
```

### 3-11. POST /api/v1/positions/{id}/close — 없는 포지션 → 404
```
Given: 빈 DB
When:  POST /api/v1/positions/999/close
Then:  HTTP 404
       code = "POSITION_NOT_FOUND"
```

---

## 테스트 실행 순서 (의존성 없음, 격리 보장)

각 테스트 클래스는 독립적. `@BeforeEach`에서 DB truncate + Redis flushAll.

## 총 테스트 케이스 수

| 클래스 | 케이스 수 |
|--------|---------|
| TickerControllerE2ETest | 2 |
| PremiumControllerE2ETest | 7 |
| PositionControllerE2ETest | 11 |
| **합계** | **20** |
