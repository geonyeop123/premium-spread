# Scheduler E2E 테스트케이스 목록 (Happy Case, No Mocking)

## 원칙

- mocking 없이 실제 컴포넌트 조합으로 검증한다.
- 스케줄러 엔트리포인트 호출 -> 캐시/DB 결과를 검증한다.
- 외부 API는 mocking을 허용한다.

## 1. PremiumScheduler.calculatePremium

1. 빗썸/바이낸스 티커와 환율 데이터를 캐시(또는 선행 배치)로 준비한다.
2. `calculatePremium()` 실행 후 현재 프리미엄 캐시가 저장된다.
3. `calculatePremium()` 실행 후 프리미엄 초단위 시계열(ZSet)이 저장된다.

## 2. TickerScheduler.fetchTickers

1. 테스트용 거래소 API 응답(빗썸/바이낸스)을 준비한다.
2. `fetchTickers()` 실행 후 양쪽 티커 캐시가 저장된다.
3. `fetchTickers()` 실행 후 양쪽 초단위 시계열(ZSet)이 저장된다.

## 3. ExchangeRateScheduler.fetchExchangeRate

1. 테스트용 환율 API 응답을 준비한다.
2. `fetchExchangeRate()` 실행 후 환율 캐시가 저장된다.
3. `fetchExchangeRate()` 실행 후 환율 DB 레코드가 저장된다.

## 4. ExchangeRateScheduler.fetchExchangeRateOnStartup

1. 테스트용 환율 API 응답을 준비한다.
2. `fetchExchangeRateOnStartup()` 실행 시 초기 실행 경로로 환율이 저장된다.
3. 캐시/DB 결과가 `fetchExchangeRate()`와 동일하게 생성된다.

## 5. PremiumAggregationScheduler.aggregateMinute

1. 프리미엄 초단위 데이터를 시간 윈도우에 맞게 준비한다.
2. `aggregateMinute()` 실행 후 분 집계 캐시가 저장된다.
3. `aggregateMinute()` 실행 후 `premium_minute` DB에 저장된다.

## 6. PremiumAggregationScheduler.aggregateHour

1. 프리미엄 분 집계 데이터를 시간 윈도우에 맞게 준비한다.
2. `aggregateHour()` 실행 후 시 집계 캐시가 저장된다.
3. `aggregateHour()` 실행 후 `premium_hour` DB에 저장된다.

## 7. PremiumAggregationScheduler.aggregateDay

1. 프리미엄 시 집계 데이터를 시간 윈도우에 맞게 준비한다.
2. `aggregateDay()` 실행 후 `premium_day` DB에 저장된다.

## 8. PremiumAggregationScheduler.updateSummaryCache

1. 초/분/시 집계 데이터를 준비한다.
2. `updateSummaryCache()` 실행 후 summary 캐시 `1m/10m/1h/1d`가 모두 저장된다.

## 9. TickerAggregationScheduler.aggregateMinute

1. TARGETS(`bithumb:btc`, `binance:btc`) 초단위 데이터를 준비한다.
2. `aggregateMinute()` 실행 후 타겟별 분 집계 캐시가 저장된다.
3. `aggregateMinute()` 실행 후 타겟별 `ticker_minute` DB에 저장된다.

## 10. TickerAggregationScheduler.aggregateHour

1. TARGETS 분 집계 데이터를 준비한다.
2. `aggregateHour()` 실행 후 타겟별 시 집계 캐시가 저장된다.
3. `aggregateHour()` 실행 후 타겟별 `ticker_hour` DB에 저장된다.

## 11. TickerAggregationScheduler.aggregateDay

1. TARGETS 시 집계 데이터를 준비한다.
2. `aggregateDay()` 실행 후 타겟별 `ticker_day` DB에 저장된다.

## 실행 순서 권장

1. `ExchangeRateScheduler` (3, 4)
2. `TickerScheduler` (2)
3. `PremiumScheduler` (1)
4. `PremiumAggregationScheduler` (5, 6, 7, 8)
5. `TickerAggregationScheduler` (9, 10, 11)
