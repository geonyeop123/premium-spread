# Progress: Ticker 아키텍처 리팩토링

## 프로젝트
- 대상: `apps/api` ticker/FX Rate 조회 경로
- 목표: `application → domain ← infrastructure` 책임 정렬
- 참고: premium 아키텍처 리팩토링 (`.ai/planning/refactoring/architecture/`)

## 상태
- 현재 단계: 구현 완료

## 타임라인

| 날짜 | 작업 | 상태 |
|------|------|------|
| 2026-02-06 | 현재 아키텍처 분석 및 위반 식별 | 완료 |
| 2026-02-06 | premium 리팩토링 패턴 비교 분석 | 완료 |
| 2026-02-06 | 설계 판단 포인트 3건 정리 | 완료 |
| 2026-02-06 | TDD 기반 실행 계획 수립 | 완료 |
| 2026-02-06 | Phase 0: Baseline (infra import 9건, 테스트 통과) | 완료 |
| 2026-02-06 | Phase 1: Domain (TickerSnapshot, ExchangeRate 도메인) | 완료 |
| 2026-02-06 | Phase 2: Infrastructure (TickerRepositoryImpl, ExchangeRateRepositoryImpl) | 완료 |
| 2026-02-06 | Phase 3: Application (TickerCacheFacade 제거, ticker infra import 0건) | 완료 |
| 2026-02-06 | Phase 4: 테스트 (RepositoryImpl + Service 테스트, 전체 통과) | 완료 |
| 2026-02-06 | Phase 5: 문서 (instructions.md 재발 방지 규칙 추가) | 완료 |

## 변경 요약

### 신규 파일
- `domain/ticker/TickerSnapshot.kt` — read model
- `domain/exchangerate/ExchangeRateSnapshot.kt` — read model
- `domain/exchangerate/ExchangeRateRepository.kt` — Repository 인터페이스
- `domain/exchangerate/ExchangeRateService.kt` — Repository 위임 서비스
- `infrastructure/exchangerate/ExchangeRateRepositoryImpl.kt` — cache→DB fallback
- `test/.../TickerRepositoryImplTest.kt` — 5개 테스트 케이스
- `test/.../ExchangeRateRepositoryImplTest.kt` — 3개 테스트 케이스
- `test/.../ExchangeRateServiceTest.kt` — 2개 테스트 케이스

### 수정 파일
- `domain/ticker/TickerRepository.kt` — `findLatestSnapshotByExchangeAndSymbol()` 추가
- `domain/ticker/TickerService.kt` — `findLatestSnapshot()` 위임 추가
- `infrastructure/ticker/TickerRepositoryImpl.kt` — cache→aggregation→DB fallback
- `.ai/instructions.md` — 계층별 주입 규칙, 도메인 분리 규칙, 체크리스트 추가

### 삭제 파일
- `application/ticker/TickerCacheFacade.kt` — infrastructure 직접 참조 + dead code
- `test/.../TickerCacheFacadeTest.kt`

## 검증 결과
- ticker application → infrastructure import: **0건** (8건 → 0건)
- 남은 위반: PositionFacade 1건 (별도 scope)
- API 전체 테스트: **통과**
