---
name: premium-orchestrator
description: "premium-spread 프로젝트의 개발 워크플로우를 조율하는 오케스트레이터. 새 기능 구현, 배치 개발, 리팩토링, 버그 수정 등 코드 변경이 수반되는 모든 개발 작업에서 계획→분석→구현→검증→문서화 파이프라인을 조율한다. '기능 추가해줘', '구현해줘', '만들어줘', '수정해줘', '리팩토링해줘' 등 개발 작업 요청 시 반드시 이 스킬을 사용할 것."
---

# Premium Orchestrator

premium-spread 프로젝트의 개발 워크플로우를 계획→분석→구현→검증→문서화 파이프라인으로 조율한다.

## 실행 모드: 서브 에이전트

## 에이전트 구성

| 순서 | 에이전트 | subagent_type | 역할 | 스킬 | 출력 |
|------|---------|--------------|------|------|------|
| 1 | planner | planner | 작업 분해, 단계별 계획 수립 | planning | `_workspace/00_plan.md` |
| 2 | analyzer | analyzer | 코드베이스 분석, 영향 범위 파악 | — | `_workspace/01_analysis.md` |
| 3 | implementer | implementer | 백엔드 코드 구현 | api-feature / batch-job | 소스 코드 + `_workspace/02_implementation.md` |
| 3-a | db-migrator | db-migrator | Flyway 마이그레이션 SQL 생성 | db-migration | SQL 파일 + `_workspace/02_migration.md` |
| 3-b | frontend-dev | frontend-dev | 프론트엔드 구현 | frontend | 소스 코드 + `_workspace/02_frontend.md` |
| 4 | qa-validator | qa-validator | 규칙 기반 품질 검증 | qa-validator | `_workspace/03_qa_report.md` |
| 5 | code-reviewer | code-reviewer | 심층 코드 리뷰 | code-review | `_workspace/04_code_review.md` |
| 6 | tech-writer | tech-writer | 프로젝트 문서 갱신 | tech-docs | 문서 파일 + `_workspace/05_docs_update.md` |

## 워크플로우

### Phase 1: 계획

```
Agent(
  description: "작업 계획 수립",
  subagent_type: "planner",
  model: "opus",
  prompt: """
    사용자 요청: [작업 설명]
    
    premium-spread 프로젝트의 작업 계획을 수립하라.
    planning 스킬을 Skill 도구로 호출하여 따르라.
    
    계획을 _workspace/00_plan.md에 작성하라.
  """
)
```

계획서를 Read로 확인. 영향 범위에 따라 이후 Phase에서 어떤 에이전트를 활성화할지 결정한다:
- DB 변경 있음 → db-migrator 활성화
- 프론트엔드 변경 있음 → frontend-dev 활성화
- 둘 다 없으면 implementer만 실행

### Phase 2: 분석

```
Agent(
  description: "코드베이스 분석",
  subagent_type: "analyzer",
  model: "opus",
  prompt: """
    _workspace/00_plan.md의 계획을 바탕으로 premium-spread 코드베이스를 분석하라.
    
    수행할 작업:
    1. 영향받는 레이어와 파일 식별
    2. 기존 유사 도메인의 구현 패턴 분석
    3. 아키텍처 규칙 위반 가능성 사전 확인
    
    분석 결과를 _workspace/01_analysis.md에 작성하라.
  """
)
```

### Phase 3: 구현

**실행 방식:** implementer 먼저, 이후 db-migrator / frontend-dev 병렬 (필요 시)

**Step 3-1: 백엔드 구현**
```
Agent(
  description: "백엔드 구현",
  subagent_type: "implementer",
  model: "opus",
  prompt: """
    _workspace/00_plan.md와 _workspace/01_analysis.md를 읽고 [작업 설명]을 구현하라.
    
    작업 유형에 맞는 스킬을 Skill 도구로 호출하여 따르라:
    - API 기능 → api-feature
    - 배치 Job → batch-job
    
    구현 내역을 _workspace/02_implementation.md에 기록하라.
  """
)
```

**Step 3-2: DB 마이그레이션 + 프론트엔드 (병렬, 필요 시)**

DB 변경이 있는 경우:
```
Agent(
  description: "DB 마이그레이션 생성",
  subagent_type: "db-migrator",
  model: "opus",
  run_in_background: true,
  prompt: """
    _workspace/02_implementation.md를 읽고, Entity 변경에 맞는 Flyway 마이그레이션을 생성하라.
    db-migration 스킬을 Skill 도구로 호출하여 따르라.
    
    내역을 _workspace/02_migration.md에 기록하라.
  """
)
```

프론트엔드 변경이 있는 경우:
```
Agent(
  description: "프론트엔드 구현",
  subagent_type: "frontend-dev",
  model: "opus",
  run_in_background: true,
  prompt: """
    _workspace/00_plan.md와 _workspace/02_implementation.md를 읽고 프론트엔드를 구현하라.
    frontend 스킬을 Skill 도구로 호출하여 따르라.
    
    내역을 _workspace/02_frontend.md에 기록하라.
  """
)
```

### Phase 4: QA 검증

```
Agent(
  description: "품질 검증",
  subagent_type: "qa-validator",
  model: "opus",
  prompt: """
    _workspace/ 하위의 모든 산출물을 읽고, 구현된 코드의 품질을 검증하라.
    qa-validator 스킬을 Skill 도구로 호출하여 검증 절차를 따르라.
    
    검증 보고서를 _workspace/03_qa_report.md에 작성하라.
  """
)
```

### Phase 5: 코드 리뷰

QA 보고서에 FAIL이 없거나 수정 완료 후:

```
Agent(
  description: "코드 리뷰",
  subagent_type: "code-reviewer",
  model: "opus",
  prompt: """
    _workspace/ 하위의 모든 산출물을 읽고, 심층 코드 리뷰를 수행하라.
    code-review 스킬을 Skill 도구로 호출하여 리뷰 절차를 따르라.
    
    리뷰 보고서를 _workspace/04_code_review.md에 작성하라.
  """
)
```

### Phase 6: 수정 루프 (필요 시)

QA 또는 코드 리뷰에서 FAIL/BLOCKER/MAJOR 항목이 있으면:

1. 수정 권장 사항을 해당 에이전트(implementer/db-migrator/frontend-dev)에게 전달
2. 해당 에이전트가 수정 수행
3. qa-validator 또는 code-reviewer가 재검증
4. 최대 2회 반복. 2회 후에도 미해결 이슈는 사용자에게 보고

### Phase 7: 문서 갱신

```
Agent(
  description: "기술문서 갱신",
  subagent_type: "tech-writer",
  model: "opus",
  prompt: """
    _workspace/ 하위의 모든 산출물을 읽고, 프로젝트 문서를 갱신하라.
    tech-docs 스킬을 Skill 도구로 호출하여 따르라.
    
    갱신 내역을 _workspace/05_docs_update.md에 기록하라.
  """
)
```

### Phase 8: 정리

1. `_workspace/` 디렉토리 보존 (사후 검증용)
2. 사용자에게 결과 요약:
   - 구현된 기능 목록
   - 변경 파일 목록
   - DB 마이그레이션 내역 (있는 경우)
   - 프론트엔드 변경 내역 (있는 경우)
   - QA + 코드 리뷰 결과 요약
   - 갱신된 문서 목록
   - 미해결 이슈 (있는 경우)

## 데이터 흐름

```
사용자 요청
    ↓
[planner] → _workspace/00_plan.md
    ↓
[analyzer] → _workspace/01_analysis.md
    ↓
[implementer] → 소스 코드 + _workspace/02_implementation.md
    ├→ [db-migrator] → SQL + _workspace/02_migration.md (DB 변경 시, 병렬)
    └→ [frontend-dev] → 소스 코드 + _workspace/02_frontend.md (프론트 변경 시, 병렬)
    ↓
[qa-validator] → _workspace/03_qa_report.md
    ↓
[code-reviewer] → _workspace/04_code_review.md
    ↓ (FAIL/BLOCKER/MAJOR 시)
[수정 대상 에이전트] → 수정 → [qa-validator/code-reviewer] → 재검증
    ↓
[tech-writer] → 문서 갱신 + _workspace/05_docs_update.md
    ↓
사용자에게 결과 요약
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| planner 실패 | 1회 재시도. 재실패 시 메인이 간략 계획 수립 후 진행 |
| analyzer 실패 | 1회 재시도. 재실패 시 메인이 직접 기본 분석 후 구현 진행 |
| implementer 실패 | 에러 로그 포함하여 1회 재시도. 컴파일 에러면 에러 메시지 전달 |
| db-migrator 실패 | 1회 재시도. 재실패 시 implementer에게 마이그레이션도 요청 |
| frontend-dev 실패 | 1회 재시도. 재실패 시 프론트엔드 작업만 사용자에게 별도 보고 |
| qa-validator 실패 | 1회 재시도. 재실패 시 테스트 결과만 수동 확인 후 진행 |
| code-reviewer 실패 | 1회 재시도. 재실패 시 QA 결과만으로 진행 |
| tech-writer 실패 | 1회 재시도. 재실패 시 문서 갱신 목록만 사용자에게 보고 |
| 수정 루프 2회 초과 | 미해결 이슈를 사용자에게 보고하고 판단 요청 |

## 테스트 시나리오

### 정상 흐름: 새 도메인 API + 프론트엔드
1. 사용자: "알림(notification) 도메인을 추가해줘. CRUD API + 프론트 페이지 필요"
2. Phase 1: planner가 작업 분해 (백엔드 5단계 + DB 1단계 + 프론트 2단계)
3. Phase 2: analyzer가 기존 position/premium 도메인 패턴 분석
4. Phase 3: implementer → 백엔드 구현, 이후 db-migrator + frontend-dev 병렬 실행
5. Phase 4: qa-validator → 모두 PASS
6. Phase 5: code-reviewer → 승인
7. Phase 7: tech-writer → PROJECT_STATUS, http/api/notifications.http 갱신
8. Phase 8: 결과 요약 보고

### 에러 흐름: 코드 리뷰에서 BLOCKER 발견
1. Phase 5에서 code-reviewer가 SecurityConfig 누락 [BLOCKER] 발견
2. Phase 6: implementer가 SecurityConfig 갱신
3. code-reviewer 재검증 → 승인
4. Phase 7~8 정상 진행

### 경량 흐름: 단순 버그 수정
작업 규모가 작은 경우 (단일 파일 수정) 오케스트레이터 없이 직접 수정해도 된다.
오케스트레이터는 **여러 레이어/모듈에 걸친 변경**이 필요한 작업에 가치를 발휘한다.
