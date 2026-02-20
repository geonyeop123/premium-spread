# Project Status

> Last updated: 2026-02-20

## Current State

| Module | Status | Notes |
|--------|--------|-------|
| apps/api | Active | premium + ticker 아키텍처 리팩토링 완료, exchangerate 도메인 분리 |
| apps/batch | Active | 4-layer 정렬 완료, 단위 138건 + 통합 167건 GREEN |
| modules/redis | Active | AggregationTimeUnit, TickerAggregationTimeUnit 및 TTL 정책 반영 |
| modules/jpa | Stable | - |
| supports/logging | Stable | - |
| supports/monitoring | Stable | - |

## Recent Changes

```text
551883c docs: E2E 테스트 플래닝 문서 작성
7af7c6b chore: 프로젝트 설정 및 루트 문서 최신화
c7c2f3d docs: AI 문서 구조 개편 및 batch 리팩토링 계획 문서 추가
dbaa08e refactor: batch ingestion jobs 분리 및 scheduler thin entrypoint 전환
0503ac7 refactor: batch 공통 실행 기반 도입 (JobExecutor, AggregationJob)
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
- [ ] position 도메인: `PositionFacade`의 `PositionCacheWriter` infrastructure 참조 정리

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
