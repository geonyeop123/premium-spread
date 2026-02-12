# Batch Architecture Rules

## 구조

- `application/common/`: `JobResult`, `JobConfig`, `JobExecutor` (lock/metrics/last-run 공통)
- `application/job/premium/`: `PremiumRealtimeJob`
- `application/job/ticker/`: `TickerIngestionJob`
- `application/job/fx/`: `FxIngestionJob`
- `application/job/aggregation/`: `AggregationJob<T>` (제네릭, reader/writer 패턴)

## 규칙

- scheduler는 `@Scheduled` + `jobExecutor.execute(config) { job.run() }` 패턴만 담당 (thin entrypoint)
- 비즈니스 로직은 Job 클래스에 위치
- JobExecutor가 lock/metrics/last-run 등 공통 관심사 처리
