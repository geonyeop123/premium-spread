# 바이낸스 위험 지표 조회 — 레버리지·마진 모드·청산가·증거금 (issue #138)

실계정 바이낸스 스냅샷에 positionRisk 위험 필드와 symbolConfig(레버리지·마진 모드)를 nullable 로 싣는다. 부분 실패는 그 항목만 비운다.

## 문서

- [설계](design.md) — 값 타입 분리(D1), 부분 실패(D3·D4), 아키텍처 테스트(D6)
- [실행 계획](plan.md) — Task 1~3
- [완료 기준 계약서](dod.md) — AC1~AC9, VERIFIED
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- 이슈: #138 (상위 #137)
