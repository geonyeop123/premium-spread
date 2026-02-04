# Task Plan: Redis ZSet 기반 쓰기연산 부하 줄이기

## Goal
초당 데이터(Ticker, Fx, Premium)를 Redis ZSet에만 적재하고, 배치로 분/시간/일 데이터를 DB에 적재하여 쓰기 연산 부하를 줄인다.

## Current Phase
Phase 6 (마무리 - 커밋 대기)

## Phases

### Phase 1: 현재 구조 분석 및 요구사항 정의
- [x] 현재 Redis/DB 저장 방식 분석
- [x] 문제점 파악 (1초마다 DB INSERT)
- [x] 요구사항 상세화 (사용자 결정: 5분 보관, 캐시 서머리, 분+시간+일 DB)
- **Status:** complete

### Phase 2: ZSet 키/TTL/데이터 전략 설계
- [x] Key 전략 설계 (*:seconds:*, *:minutes:*, *:hours:*, summary:*)
- [x] TTL 전략 설계 (5분/2시간/25시간)
- [x] 데이터 구조 설계 (ZSet member: "rate:price:...")
- [x] 서머리 데이터 구조 설계 (Hash: high/low/current)
- **Status:** complete

### Phase 3: 배치 적재 로직 설계
- [x] 분 단위 집계 배치 설계 (1분 스케줄러, 정각 실행)
- [x] 시간 단위 집계 배치 설계 (1시간 스케줄러, 정시 실행)
- [x] 일 단위 집계 배치 설계 (1일 스케줄러, 자정 실행)
- [x] DB 테이블 구조 설계 (premium_minute, premium_hour, premium_day)
- [x] 서머리 갱신 배치 설계 (10초 스케줄러)
- **Status:** complete

### Phase 4: 구현
- [x] RedisKeyGenerator 확장 (초당/분/시간/서머리 키)
- [x] RedisTtl 확장 (5분/2시간/25시간 + 서머리 TTL)
- [x] ZSet CacheService 구현 (saveToSeconds, 집계 메서드)
- [x] 집계 배치 스케줄러 구현 (PremiumAggregationScheduler)
- [x] DB 마이그레이션 추가 (V5__create_premium_aggregation_tables.sql)
- [x] PremiumScheduler 수정 (DB INSERT → ZSet 저장)
- **Status:** complete

### Phase 5: 테스트 및 검증
- [x] 기존 테스트 수정 (PremiumControllerTest)
- [x] 전체 테스트 통과 확인
- [ ] 성능 비교 테스트 (배포 후)
- **Status:** complete

### Phase 6: 마무리
- [ ] 코드 리뷰
- [ ] 커밋 및 PR 업데이트
- [ ] 배포 준비
- **Status:** in_progress

## Key Questions
1. ~~분/시간/일 집계 시점은 언제인가?~~ → 정각 기준 배치
2. ~~과거 초당 데이터는 얼마나 유지할 것인가?~~ → **5분**
3. ~~서머리 데이터의 기준 시간대는?~~ → 1분, 10분, 1시간, 1일
4. ~~차트용 데이터는 실시간 조회 vs 캐시?~~ → **캐시된 서머리**
5. DB 저장 단위는? → **분 + 시간 + 일**

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| ZSet 사용 | 타임스탬프 기반 range query 지원, 오래된 데이터 자동 삭제 용이 |
| 초당 5분 보관 | 10분 서머리까지 실시간 계산 가능 |
| 캐시된 서머리 | 조회 성능 최적화, 별도 Hash에 저장 |
| 분+시간+일 DB 저장 | 세분화된 히스토리, 98.3% 쓰기 부하 감소 |
| 10초마다 서머리 갱신 | 실시간성과 부하 균형 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
|       | 1       |            |

## Notes
- 현재 PremiumScheduler가 1초마다 DB INSERT 수행 중 → 이것이 부하의 원인
- 기존 premium:btc:history (ZSet) 패턴 이미 존재 → 확장 가능
