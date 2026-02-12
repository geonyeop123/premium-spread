# Batch 의존 방향 정렬 TODO Plan (도메인 분할 실행)

## Goal
도메인별(`premium`, `ticker`, `fx`) task plan을 순차 수행하여 batch 전체를 `interfaces -> application -> domain <- infrastructure`로 정렬한다.  
루트 TODO는 실행 순서/검증 게이트를 정의하고, 상세 구현은 각 도메인 `task_plan.md`에서 수행한다.

## Scope
- 포함
  - 공통 기반 작업(`JobExecutor`, 공통 타입, 검증 게이트)
  - 도메인별 수직 리팩토링 (`premium` -> `ticker` -> `fx`)
  - Aggregation 공통화(`AggregationJob`) 및 엔트리포인트 분리 유지
  - 문서 동기화
- 제외
  - DB/Redis 정책 변경
  - 신규 기능 추가

## Current Findings
- 루트 분석: `.ai/planning/refactoring/batch/findings.md`
- 상세 계획:
  - premium: `.ai/planning/refactoring/batch/premium/task_plan.md`
  - ticker: `.ai/planning/refactoring/batch/ticker/task_plan.md`
  - fx: `.ai/planning/refactoring/batch/fx/task_plan.md`

## Target Design
1. 공통 실행 정책은 `application/common`으로 단일화
2. 도메인별 job orchestration은 `application/job/{domain}`
3. scheduler는 `interfaces/scheduler`에서 thin entrypoint 유지
4. domain은 프레임워크 무의존
5. infrastructure가 외부 I/O를 담당

## TDD Workflow

### Phase 0: Baseline & Guardrail
- [x] 현재 스케줄 주기/lock key/last-run key/metrics 목록 캡처
- [x] 리그레션 기준 테스트 목록 확정
- [x] `domain`의 프레임워크 의존 import 기준선 기록

### Phase 1: Common Foundation
- [x] `JobResult` 타입 정의
- [x] `JobExecutor` 구현 및 단위 테스트 작성 (6개 테스트)
- [x] `AggregationJob` 제네릭 공통 집계 클래스 설계 (TimeWindowPolicy 역할 내장)

### Phase 2: Premium Domain
- [x] `PremiumRealtimeJob` 구현 및 테스트 (8개 테스트)
- [x] `PremiumScheduler` thin entrypoint 전환
- [x] Premium 경로 검증 게이트 통과

### Phase 3: Ticker Domain
- [x] `TickerIngestionJob` 구현 및 테스트 (3개 테스트)
- [x] `TickerScheduler` thin entrypoint 전환
- [x] Ticker 경로 검증 게이트 통과

### Phase 4: FX Domain
- [x] `FxIngestionJob` 구현 및 테스트 (2개 테스트)
- [x] `ExchangeRateScheduler` thin entrypoint 전환
- [x] FX 경로 검증 게이트 통과

### Phase 5: Aggregation Unification
- [x] `AggregationJob` 1개로 premium/ticker minute/hour/day 로직 통합 (6개 테스트)
- [x] scheduler 엔트리포인트(분/시간/일)는 분리 유지
- [x] 공통화 후 회귀 테스트 통과

### Phase 6: Verification Gate
- [x] scheduler에 비즈니스 분기/검증/저장 조립 로직 잔존 여부 점검 (0건)
- [x] 메트릭/락/last-run 회귀 점검 (동일)
- [x] 전체 테스트 재실행 (BUILD SUCCESSFUL)
- [x] 문서 동기화(architecture/status/readme)

## File Change Plan
- 루트 계획
  - `.ai/planning/refactoring/batch/findings.md`
  - `.ai/planning/refactoring/batch/todo.md`
- 도메인 계획
  - `.ai/planning/refactoring/batch/premium/task_plan.md`
  - `.ai/planning/refactoring/batch/ticker/task_plan.md`
  - `.ai/planning/refactoring/batch/fx/task_plan.md`

## Acceptance Criteria
1. 도메인별 task plan 기준으로 순차 수행 가능하다.
2. Aggregation은 로직 단일화 + 엔트리포인트 분리 전략이 반영되어 있다.
3. Claude Code가 파일만 읽고 구현 결정을 내릴 수 있을 정도로 결정이 완료되어 있다.

## Assumptions
1. 구현 순서는 premium -> ticker -> fx로 고정한다.
2. 공통 기반(`JobExecutor`, `TimeWindowPolicy`)은 도메인 작업 전에 선행한다.
3. 운영 정책(주기/TTL/키)은 유지한다.
