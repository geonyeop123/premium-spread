# Progress: Premium 아키텍처 리팩토링

## 프로젝트
- 대상: `apps/api` premium 조회/저장 경로
- 목표: `application -> domain <- infrastructure` 책임 정렬

## 상태
- 현재 단계: 구현 완료
- 기준 문서: `task_plan.md`, `findings.md`

## 타임라인

| 날짜 | 작업 | 산출물 | 상태 |
|------|------|--------|------|
| 2026-02-06 | 현재 아키텍처 분석 및 직접 의존 식별 | 분석 결과(대화) | 완료 |
| 2026-02-06 | 설계 방향 합의 (`PremiumService` 중심) | 합의 사항(대화) | 완료 |
| 2026-02-06 | TDD 기반 실행 계획 수립 | `task_plan.md` | 완료 |
| 2026-02-06 | 진단/결정사항 문서화 | `findings.md` | 완료 |
| 2026-02-06 | 진행 현황 문서화 | `progress.md` | 완료 |
| 2026-02-06 | Phase 0: Baseline Capture | 테스트 통과, infra import 3건 확인 | 완료 |
| 2026-02-06 | Phase 1+2: RED→GREEN 구현 | PremiumSnapshot, Repository fallback | 완료 |
| 2026-02-06 | Phase 3: REFACTOR | infra import 0건, PremiumCacheFacade 제거 | 완료 |
| 2026-02-06 | Phase 4: Verification Gate | 전체 테스트 통과 | 완료 |

## 체크포인트
- [x] premium 관련 baseline 테스트 기록
- [x] `application`의 infra import 제거 전/후 비교 (3건 → 0건)
- [x] API 계약 회귀 검증 (`source` 필드 제거, 나머지 유지)
- [x] 테스트 통과 확인 (BUILD SUCCESSFUL)

## 변경 요약

### 신규 파일
- `domain/premium/PremiumSnapshot.kt` — read model (가격 데이터 포함)
- `test/.../PremiumRepositoryImplTest.kt` — 단위 테스트 (cache/DB fallback)

### 수정 파일
- `domain/premium/PremiumRepository.kt` — `findLatestSnapshotBySymbol` 추가
- `domain/premium/PremiumService.kt` — `findLatestSnapshotBySymbol` 위임
- `infrastructure/premium/PremiumRepositoryImpl.kt` — cache→DB+ticker enrichment 구현
- `application/premium/PremiumFacade.kt` — `findLatestSnapshot` 추가
- `interfaces/api/premium/PremiumController.kt` — `PremiumFacade`만 의존
- `interfaces/api/premium/PremiumDtos.kt` — `source` 제거, `PremiumSnapshot` 기반

### 삭제 파일
- `application/premium/PremiumCacheFacade.kt` — 전체 삭제
- `test/.../PremiumCacheFacadeTest.kt` — 전체 삭제

## 메모
- 이번 범위는 premium 한정
- 고급 정책(장애/동시성/알람)은 제외
