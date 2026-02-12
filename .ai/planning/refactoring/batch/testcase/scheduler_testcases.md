# Scheduler 테스트케이스 목록 (경량/핵심 계약 중심)

## 원칙
- 스케줄러 테스트는 오케스트레이션 책임만 검증한다.
- 과도한 파라미터 전수 검증(`jobName/lockKey/leaseTime` 전체)은 지양한다.
- 클래스별로 핵심 계약 1개 + 실행 위임을 확인하는 스모크 수준으로 유지한다.

## PremiumScheduler
1. `calculatePremium()` 호출 시 `jobExecutor.execute()`가 1회 호출된다.
2. `execute`의 action 실행 시 `premiumRealtimeJob.run()`이 1회 호출된다.
3. 핵심 계약 스모크: 전달된 `JobConfig.jobName`이 `premium`이다.

## TickerScheduler
1. `fetchTickers()` 호출 시 `jobExecutor.execute()`가 1회 호출된다.
2. `execute`의 action 실행 시 `tickerIngestionJob.run()`이 1회 호출된다.
3. 핵심 계약 스모크: 전달된 `JobConfig.jobName`이 `ticker`이다.

## ExchangeRateScheduler
1. `fetchExchangeRate()` 호출 시 `jobExecutor.execute()`가 1회 호출된다.
2. `execute`의 action 실행 시 `fxIngestionJob.run()`이 1회 호출된다.
3. `fetchExchangeRateOnStartup()` 호출 시 내부적으로 `fetchExchangeRate()` 경로를 통해 `jobExecutor.execute()`가 호출된다.
4. 핵심 계약 스모크: 전달된 `JobConfig.jobName`이 `fx`이다.

## PremiumAggregationScheduler
1. `aggregateMinute()` 호출 시 `jobExecutor.execute()`가 호출된다.
2. minute action 실행 시 분 집계 파이프라인이 수행된다.
   - `aggregateSecondsData("btc", from, to)` 호출
   - 데이터 존재 시 `saveAggregation(MINUTES, ...)` + `saveMinute(...)` 호출
3. `aggregateHour()` 호출 시 `jobExecutor.execute()`가 호출된다.
4. hour action 실행 시 시 집계 파이프라인이 수행된다.
   - `aggregateData(MINUTES, ...)` 호출
   - 데이터 존재 시 `saveAggregation(HOURS, ...)` + `saveHour(...)` 호출
5. `aggregateDay()` 호출 시 `jobExecutor.execute()`가 호출된다.
6. day action 실행 시 일 집계 DB 적재가 수행된다.
   - `aggregateData(HOURS, ...)` 호출
   - 데이터 존재 시 `saveDay(...)` 호출
7. `updateSummaryCache()`는 각 구간(1m/10m/1h/1d)을 독립적으로 실행하며, 특정 구간 실패 시에도 나머지 구간은 계속 처리한다. (현재 구현 기준 Red 예상)
8. `updateSummaryCache()`에서 구간별 계산 결과가 있을 때만 해당 구간 `saveSummary(...)`를 호출하고, 실패한 구간은 건너뛴다. (현재 구현 기준 Red 예상)
9. 핵심 계약 스모크: day 실행의 `JobConfig.jobName`이 `aggregation:day`이다.

## TickerAggregationScheduler
1. `aggregateMinute()` 호출 시 `jobExecutor.execute()`가 호출된다.
2. minute action 실행 시 TARGETS에 대해 분 집계 파이프라인이 수행된다.
3. `aggregateHour()` 호출 시 `jobExecutor.execute()`가 호출된다.
4. hour action 실행 시 TARGETS에 대해 시 집계 파이프라인이 수행된다.
5. `aggregateDay()` 호출 시 `jobExecutor.execute()`가 호출된다.
6. day action 실행 시 TARGETS에 대해 일 집계 DB 적재가 수행된다.
7. `runForAllTargets` 결과 규칙을 검증한다.
   - 하나라도 `Success`면 최종 `Success`
   - 전부 no-data면 최종 `Skipped("no_data")`
   - 중간 `Failure` 발생 시 즉시 `Failure`
8. 핵심 계약 스모크: day 실행의 `JobConfig.jobName`이 `ticker:aggregation:day`이다.

## 공통 완료 조건
1. 스케줄 메서드 -> `jobExecutor.execute` 호출 검증
2. action 실행 시 하위 job/파이프라인 위임 검증
3. 클래스당 핵심 계약 1개(`jobName`)만 스모크 검증
