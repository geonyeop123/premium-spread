# orchestrator ①② 선택 지식 그래프 경로 (Orchestrator Knowledge Graph)

`orchestrator` ①·② 가 저장소 밖 지식 그래프(`graphify`, `~/graphs/<레포>/`)를 있으면 판정·최신화해 영향 범위 첫 패스에
쓰고, 없으면 기존 `Explore` 탐색 그대로 돈다. 자매 저장소 aic-api 와 같은 변경이며 base 인자만 `origin/{base}` 다.

## 문서

- [설계](design.md) — 요구 U1–U4, 판단 A1–A7, §5 최종 문면
- [계획](plan.md)
- [완료 기준](dod.md) — AWAITING_HUMAN (AC9 문면 확인 대기)
- [개발자 이해문서](understanding.md)
- [PR 본문](pr-body.md)
