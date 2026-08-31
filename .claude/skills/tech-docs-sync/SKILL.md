---
name: tech-docs-sync
description: "기술 문서 동기화 스킬. 코드 변경 유형별로 갱신할 문서(.ai/PROJECT_STATUS·ARCHITECTURE_DESIGN·runbooks·http 샘플·AGENTS/CLAUDE 모듈 트리)를 매핑하고 최소 변경 원칙과 검사 명령을 가이드한다. 구현 후 문서 동기화, PR 전 문서 확인, 문서 갱신 요청 시 반드시 이 스킬을 사용할 것."
---

# 기술 문서 동기화

## 변경 → 문서 매핑

| 코드/설정 변경 | 갱신 대상 |
|---------------|----------|
| 모듈 구성·경계 변경 | `.ai/architecture/ARCHITECTURE_DESIGN.md`, `AGENTS.md`·`CLAUDE.md`의 모듈 트리 |
| 아키텍처 규칙 자체가 바뀜 | `.ai/rules/{architecture,batch,http,naming,testing,git}.md` (정본) |
| endpoint 추가·변경 | `http/api/{domain}.http` + contract/integration 테스트 |
| 공개 endpoint 목록 변경 | `docs/runbooks/auth-security.md` + management endpoint contract test |
| Redis key/TTL/payload 변경 | `modules:redis` + `docs/runbooks/redis-contract.md` |
| 알림 재시도·stale recovery·redrive·PII 처리 변경 | `docs/runbooks/durable-notification-delivery.md` |
| 관리 endpoint·readiness·메트릭 변경 | `docs/runbooks/management-endpoints.md`, `docs/runbooks/observability-readiness.md`, `docs/runbooks/metrics-alerting.md` |
| 배포·프로필·환경변수 변경 | `docs/runbooks/deployment.md`, `docs/runbooks/configuration-profiles.md` |
| 마이그레이션 추가 | `docs/runbooks/v12-migration.md`(해당 시) + `.ai/PROJECT_STATUS.md` |
| 진행 상황·TODO | `.ai/PROJECT_STATUS.md` |
| 비즈니스 도메인 개념 추가 | `.ai/context/project-overview.md` |
| 하네스(에이전트·스킬·단계) 변경 | `CLAUDE.md`의 `## 하네스` 변경 이력에 한 행 |

## 원칙

- **최소 변경.** 바뀐 부분만 고친다. 문서 전체를 다시 쓰지 않는다. 기존 구조와 형식을 유지한다.
- **사실 기반.** 코드에서 직접 읽은 것만 적는다. 추측한 동작을 서술하지 않는다.
- **없는 경로를 갱신했다고 보고하지 않는다.** 이 저장소에는 `.ai/diagrams/`가 없다. 다이어그램이
  필요하면 만들 것인지 먼저 확인하고, 만들지 않기로 하면 후속 항목으로 남긴다.
- 문서에 `TBD`·`TODO`·`FIXME`를 남기지 않는다 — 정본 문서에서는 검사에 걸린다.

## 갱신 후 검사

```bash
bash docs/check-documentation.sh
```

이 스크립트는 정본 문서(`AGENTS.md`, `.ai/*`, `docs/runbooks/*`)에 대해 **깨진 마크다운 링크, 미해결
placeholder, 존재하지 않는 필수 경로, 낡은 아키텍처 서술**(예: 앱 내부 `infrastructure`/`cache`/
`repository`/`client` 패키지 경로, 폐기된 이벤트 리스너 이름)을 검사하고 CI(`quality-gate.yml`)에서도
돌아간다. 문서를 고쳤으면 커밋 전에 한 번 돌린다.

> `.claude/` 하네스 파일은 이 검사 대상이 **아니다.** 하네스 문서가 가리키는 경로가 실재하는지는
> 사람이 확인해야 한다.

## 읽을 것

- `docs/check-documentation.sh` — 정본 문서 목록과 검사 규칙
- `.ai/rules/git.md` — 커밋·PR 규약
