# Project Status

> Last updated: 2026-02-24

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | 아키텍처 리팩토링 완료, 단위 171건 GREEN (infrastructure/cache 패키지 해소 + PositionFacade 위반 수정) |
| apps/batch | Active | 4-layer 정렬 완료, 단위 138건 + 통합 167건 GREEN |
| modules/redis | Active | AggregationTimeUnit, TickerAggregationTimeUnit 및 TTL 정책 반영 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```text
refactor: infrastructure/cache 패키지 해소 + PositionFacade 아키텍처 위반 수정
5d36ed4 test: api 모듈 테스트 커버리지 보강 (CacheReader + DomainException)
204f7a8 test: batch 모듈 테스트 커버리지 전면 보강
```

## TODO

### In Progress

- [x] Redis ZSet 기반 티커 집계 시스템 완성
  - [x] TickerCacheService에 ZSet 저장/조회/집계 메서드 추가
  - [x] TickerAggregationTimeUnit enum 추가
  - [x] TickerAggregationScheduler 구현 (분/시간/일 집계)
  - [x] DB 저장 스케줄러 구현

### Refactoring Backlog

- [x] ticker 도메인: `TickerCacheFacade` -> infrastructure 이동
- [x] exchangerate 도메인 분리 (`domain/exchangerate/`)
- [x] batch 4-layer 의존 방향 정렬 (premium/ticker/fx scheduler → thin entrypoint)
- [x] batch JobExecutor 공통화 (lock/metrics/last-run)
- [x] batch AggregationJob 통합 (premium/ticker minute/hour/day)
- [x] batch E2E 테스트 구축 (20건 GREEN: ExchangeRate 3 + Ticker 3 + Premium 3 + PremiumAgg 6 + TickerAgg 5)
- [x] position 도메인: `PositionFacade`의 `PositionCacheWriter` infrastructure 참조 정리
- [x] infrastructure/cache/ 패키지 해소 → 각 도메인 패키지로 분산

### Pending

- [ ] E2E Tests (API 서버)
- [ ] Production 설정 (application-prod.yml)
- [ ] Docker 설정 (Dockerfile, docker-compose)
- [ ] CI/CD 파이프라인

## Known Issues

- 없음

## Notes

- FX 캐시 TTL: 31분 (30분 스케줄 + 1분 버퍼)
- 배치 수집 주기: ticker/premium 1초, FX 30분
- 집계 주기: 분/시간/일
- 설계 문서: `.ai/architecture/ARCHITECTURE_DESIGN.md`
