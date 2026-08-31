---
name: task-packet
description: "에이전트 간 전달 포맷 스킬. 작업 요청·리뷰 결과·재작업 요청을 주고받을 때 쓰는 Task Packet 표준 형식을 정의한다. 에이전트에게 작업을 넘길 때, 리뷰 피드백을 전달할 때, 재작업을 요청할 때 이 스킬을 사용할 것."
---

# Task Packet

에이전트는 서로의 세션을 보지 못한다. 넘기는 쪽이 목표·제약·맥락을 한 덩어리로 싸서 줘야 받는 쪽이
같은 것을 두 번 조사하지 않는다.

## 형식

```markdown
## Task Packet
- **From**: {보내는 에이전트}
- **To**: {받는 에이전트}
- **Type**: request | review | feedback | result

### Goal
{한 문장으로 된 목표}

### Constraints
{지켜야 할 규칙과 근거 문서 — 예: `.ai/rules/architecture.md` 모듈 경계, MarketPair 보존}

### Context
{선행 산출물 경로 — docs/work/{slug}/design.md, plan.md, 관련 소스 파일}

### Review Stage
{리뷰 라운드 n/2, 직전 라운드에서 남은 지적}

### Notes
{추가 참고 — 재현 명령, 실패 로그 위치, 판단이 갈린 지점}
```

## 예시 1 — 구현 요청 (architect → implementer)

```markdown
## Task Packet
- **From**: architect
- **To**: implementer
- **Type**: request

### Goal
notification 구독 해제 API를 plan.md Task 3 순서대로 TDD 구현한다.

### Constraints
Controller는 Facade 하나만 주입. 구독 revision과 MarketPair를 identity에서 떨어뜨리지 않는다.
migration이 필요하면 `infrastructure/common/.../db/migration/`에 다음 번호로 추가한다.

### Context
docs/work/notification-unsubscribe/design.md, plan.md Task 3

### Review Stage
—

### Notes
threshold 평가 경로는 건드리지 않는다. 이번 스코프는 구독 상태 전이까지다.
```

## 예시 2 — 리뷰 피드백 (code-reviewer → implementer)

```markdown
## Task Packet
- **From**: code-reviewer
- **To**: implementer
- **Type**: feedback

### Goal
claim 갱신 경로의 fencing 누락을 고친다.

### Constraints
모든 상태 전이에 owner + claim token fencing 조건을 유지한다
(`.ai/rules/batch.md` durable notification 절).

### Context
infrastructure/batch/.../NotificationDeliveryClaimAdapter.kt:88

### Review Stage
Round 1/2

### Notes
[Critical] UPDATE에 claim_token 조건이 빠져 stale owner가 남의 row를 덮어쓸 수 있다.
재현: 동시 claim 테스트에 owner를 두 개 띄우고 lease 만료 후 갱신.
```

## 규칙

- **Type을 정확히 쓴다.** `request`는 새 작업, `feedback`은 재작업, `result`는 완료 보고다.
- 리뷰 피드백에는 심각도(Critical/Major/Minor)와 `파일:라인`을 넣는다.
- 받는 쪽은 지적을 무비판 수용하지 않는다. 기술적으로 틀린 지적은 근거를 들어 반박하고, 반박이
  받아들여지면 그대로 둔다.
- 리뷰 순환은 최대 2회다. 초과하면 사용자에게 판단을 넘긴다.
