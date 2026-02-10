# Project Status

> Last updated: 2026-02-10

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | premium + ticker 아키텍처 리팩토링 완료, exchangerate 도메인 분리 |
| apps/batch | Active | Redis ZSet 기반 초당 저장 + 분/시간/일 집계 + DB 저장 운영 중 |
| modules/redis | Active | AggregationTimeUnit, TickerAggregationTimeUnit 및 TTL 정책 반영 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```text
bc0d844 refactor: premium 조회 캐시 전략을 infrastructure로 이동
88864df Merge branch 'refs/heads/refactor/premium' into feature/premium
0eed724 test: TickerCacheFacade, PremiumCacheFacade 테스트 추가
d6eef34 feat: Redis ZSet 기반 티커 집계 인프라 구축
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
- [ ] position 도메인: `PositionFacade`의 `PositionCacheWriter` infrastructure 참조 정리

### Pending

- [ ] E2E Tests (API + Batch 연동)
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
