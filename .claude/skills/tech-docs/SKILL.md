---
name: tech-docs
description: "기술문서 갱신 스킬. 코드 변경 후 PROJECT_STATUS.md, ARCHITECTURE_DESIGN.md, project-overview.md, http/*.http 등 프로젝트 문서를 동기화한다. 구현 완료 후 파이프라인에서 자동 실행된다. '문서 갱신', '문서 업데이트', 'STATUS 갱신', '문서 동기화' 요청 시 반드시 이 스킬을 사용할 것. 코드 변경 없이 문서만 갱신할 때도 사용."
---

# Tech Docs — 기술문서 갱신 가이드

코드 변경 사항을 프로젝트 문서에 반영하는 절차 가이드.

## 갱신 대상 문서

| 문서 | 경로 | 갱신 시점 |
|------|------|----------|
| 프로젝트 상태 | `.ai/PROJECT_STATUS.md` | 모든 변경 |
| 아키텍처 설계 | `.ai/architecture/ARCHITECTURE_DESIGN.md` | 아키텍처 수준 변경 |
| 비즈니스 도메인 | `.ai/context/project-overview.md` | 도메인 개념 변경 |
| API 샘플 | `http/api/*.http` | 엔드포인트 변경 |
| 작업 계획 | `.ai/planning/{task}/progress.md` | 계획된 작업 완료 시 |

## 갱신 절차

### 1. 변경 사항 수집

파이프라인 산출물을 읽는다:
- `_workspace/00_plan.md` — 작업 계획 (목표, 범위)
- `_workspace/02_implementation.md` — 백엔드 구현 내역
- `_workspace/02_frontend.md` — 프론트엔드 구현 내역 (있는 경우)
- `_workspace/02_migration.md` — DB 마이그레이션 내역 (있는 경우)
- `_workspace/04_code_review.md` — 코드 리뷰 결과

### 2. PROJECT_STATUS.md 갱신

항상 갱신한다. 기존 구조를 유지하면서:
- **마지막 갱신일** 업데이트
- **모듈 상태 테이블** 갱신 (새 모듈, 상태 변경)
- **완료 항목** 추가 (새 기능, 버그 수정, 리팩토링)
- **Pending TODO** 갱신 (완료된 TODO 제거, 새 TODO 추가)
- **Known Issues** 갱신 (해결된 이슈 제거, 새 이슈 추가)

### 3. ARCHITECTURE_DESIGN.md 갱신

아키텍처 수준 변경이 있을 때만:
- 새 도메인/모듈 추가
- 데이터 흐름 변경
- 보안/인증 구조 변경
- 캐시 전략 변경
- DB 스키마 주요 변경

### 4. project-overview.md 갱신

비즈니스 도메인 개념이 변경될 때만:
- 새 도메인 개념 추가
- 비즈니스 로직 변경
- 버전(V1/V2/V3) 진행 상태 갱신

### 5. http/*.http 갱신

API 엔드포인트가 추가/변경될 때:
- `http/README.md`의 작성 규칙을 따른다
- 환경 변수: `{{host}}`, `{{token}}`
- 인증 필요 API: `Authorization: Bearer {{token}}`
- 요청/응답 예시 포함

### 6. planning 진행 상황 갱신

`.ai/planning/` 하위에 관련 계획이 있으면:
- `progress.md`의 체크리스트 업데이트
- 완료된 항목에 날짜 표시

## 갱신 원칙

- **기존 구조 유지**: 문서의 섹션 구조, 마크다운 스타일, 테이블 형식을 변경하지 않는다
- **사실 기반**: 코드에서 확인 가능한 사실만 기록한다
- **날짜 명시**: 갱신일을 반드시 포함한다 (예: `> 마지막 갱신: 2026-04-04`)
- **최소 변경**: 변경된 부분만 수정한다. 불필요한 재작성 금지
- **누락 기록**: 갱신하지 않은 문서와 그 사유를 보고서에 기록한다
