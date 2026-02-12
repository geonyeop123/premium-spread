# Findings: Batch 4-Layer 의존 방향 정렬

## 요약

현재 `apps/batch`는 scheduler가 interfaces 역할을 넘어서 유스케이스 조립, 분기/검증, 저장 전략, 메트릭/락 처리까지 수행하고 있다.
희망 구조(`interfaces -> application -> domain <- infrastructure`)와의
불일치는 `premium`, `ticker`, `fx`, `aggregation` 경로 전반에 존재한다.

## 확인된 사실

### 1) Scheduler 책임 과다

- `PremiumScheduler`가 데이터 검증, 저장 분기, 메트릭/락/last-run을 모두 수행
- `TickerScheduler`, `ExchangeRateScheduler`도 orchestration + cross-cutting 책임을 같이 가짐

### 2) Aggregation 로직

- premium/ticker 집계 minute/hour/day 로직이 거의 동일 패턴으로 반복
- 시간창 계산, no-data 처리, 저장/메트릭 코드 중복

### 3) Cross-cutting 책임 분산

- lock 획득/skip, success/error/skipped 메트릭, `batch:last_run:{job}` 갱신이 scheduler마다 분산

### 4) 테스트 공백

- batch 테스트는 client/calculator 중심
- scheduler/job orchestration 단위 테스트 부족

## 합의된 설계 방향

1. batch도 `interfaces -> application -> domain <- infrastructure`로 정렬
2. scheduler는 trigger 엔트리포인트만 담당
3. lock/metrics/last-run은 `application/common/JobExecutor`로 통합
4. Aggregation은 `AggregationJob` 하나로 로직 통합, 엔트리포인트(분/시간/일)는 분리 유지
5. rollout은 premium -> ticker -> fx 순서로 진행

## 구현 범위(이번 작업)

- 포함
    - batch 계층 정렬
    - 공통 실행기/공통 정책 도입
    - 도메인별 수직 리팩토링
    - aggregation 공통화
- 제외
    - DB schema 변경
    - Redis key/TTL 정책 변경
    - 신규 외부 API 연동

## 리스크

- 메트릭/락 키 회귀 가능성
- Aggregation config(window/source) 오설정 가능성
- scheduler 분해 중 기능 회귀 가능성

## 완료 기준

1. scheduler에서 비즈니스 분기/검증/저장 조립 제거
2. aggregation 중복 로직 제거 + 엔트리포인트 분리 유지
3. lock/metrics/last-run 공통화 완료
4. 기존 주기/키/TTL/저장 경로 회귀 없음
5. 도메인별 task plan만으로 구현 판단이 가능한 상태
