---
name: batch-job
description: "배치 Job 개발 스킬. 새로운 데이터 수집 Job, 집계 Job, 스케줄러를 추가하거나 기존 배치를 수정할 때 사용한다. '배치 추가', 'Job 만들어줘', '스케줄러 추가', '수집 배치', '집계 배치', 'cron 작업' 요청 시 반드시 이 스킬을 사용할 것."
---

# Batch Job — 배치 개발 가이드

premium-spread의 배치 아키텍처에 맞춰 Job/Scheduler를 구현하는 절차 가이드.

## 배치 아키텍처 개요

```
scheduler/ → @Scheduled + jobExecutor.execute(config) { job.run() }
application/common/ → JobConfig, JobExecutor (lock/metrics/last-run 공통)
application/job/{domain}/ → 실제 Job 로직
```

- Scheduler는 thin entrypoint (스케줄 트리거만 담당)
- JobExecutor가 분산 락, 메트릭, last-run 등 공통 관심사 처리
- 비즈니스 로직은 Job 클래스에 위치

## Job 유형

### 1. 수집 Job (Ingestion)
외부 API에서 데이터를 가져와 캐시/DB에 저장.

```
Client(외부 API) → Job(변환/계산) → CacheService(Redis) + Repository(MySQL)
```

**구현 순서:**
1. `client/{provider}/` — 외부 API 클라이언트 + Response DTO
2. `cache/` — Redis 캐시 서비스
3. `repository/` — MySQL Repository (필요 시)
4. `application/job/{domain}/` — Job 클래스
5. `scheduler/` — Scheduler 클래스

### 2. 집계 Job (Aggregation)
Redis time-series 데이터를 읽어 집계하여 MySQL에 저장.

```
CacheService(Redis 읽기) → AggregationJob(집계 로직) → Repository(MySQL 저장)
```

**구현 순서:**
1. `cache/` — 캐시에서 시계열 데이터 읽기
2. `repository/` — 집계 결과 저장 Repository
3. `application/job/aggregation/` — AggregationJob (reader/writer 패턴)
4. `scheduler/` — Scheduler

## Job 클래스 패턴

```kotlin
// application/job/{domain}/{Domain}Job.kt
@Component
class {Domain}Job(
    private val client: {Provider}Client,      // 수집 Job인 경우
    private val cacheService: {Domain}CacheService,
    private val repository: {Domain}Repository, // 필요 시
) {
    fun run(): JobResult {
        // 비즈니스 로직
        return JobResult.success("{domain}", processedCount)
    }
}
```

## Scheduler 패턴

```kotlin
// scheduler/{Domain}Scheduler.kt
@Component
class {Domain}Scheduler(
    private val jobExecutor: JobExecutor,
    private val {domain}Job: {Domain}Job,
) {
    companion object {
        private val CONFIG = JobConfig(
            jobName = "{domain}-ingestion",
            lockName = "lock:{domain}:ingestion",
            lockDuration = Duration.ofSeconds(5),
        )
    }

    @Scheduled(fixedRate = 1000)  // 또는 cron 표현식
    fun execute() {
        jobExecutor.execute(CONFIG) { {domain}Job.run() }
    }
}
```

## 외부 API 클라이언트 패턴

```kotlin
// client/{provider}/{Provider}Client.kt
@Component
class {Provider}Client(
    private val webClient: WebClient,
) {
    fun fetch{Data}(): {Provider}Response.{Data} {
        return webClient.get()
            .uri("{endpoint}")
            .retrieve()
            .bodyToMono({Provider}Response.{Data}::class.java)
            .block() ?: throw ExternalApiException("{provider} API 호출 실패")
    }
}
```

## 캐시 서비스 패턴

```kotlin
// cache/{Domain}CacheService.kt
@Component
class {Domain}CacheService(
    private val redisTemplate: RedisTemplate<String, String>,
) {
    fun save(data: {Data}) { ... }
    fun findLatest(): {Data}? { ... }
}
```

## 테스트

- **Job Unit**: Mock 의존성으로 Job.run() 로직 검증
- **Client Unit**: WireMock으로 외부 API 응답 모킹
- **Cache Unit**: Mock RedisTemplate으로 캐시 로직 검증
- **Scheduler E2E**: `@Tag("integration")`, TestContainers로 전체 흐름 검증

## 주의 사항

- Docker 컨테이너는 UTC, 로컬은 KST — 시간 관련 로직에 ZoneId 명시
- 분산 락 키는 `lock:{domain}:{job-type}` 형식
- fixedRate vs cron: 짧은 주기(초 단위)는 fixedRate, 긴 주기(분/시간/일)는 cron
