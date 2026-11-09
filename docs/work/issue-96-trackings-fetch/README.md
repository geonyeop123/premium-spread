# apps/web 트래킹 조회 훅 분리와 set-state-in-effect override 제거 (issue #96)

트래킹 목록·상세 페이지의 조회 로직을 `apps/web/src/hooks/useTrackings.ts`(`useTrackingList`·`useTracking`·`useGrossPnl`)로 분리하고,
#82 D7에서 넣은 `react-hooks/set-state-in-effect` eslint override를 제거했다. 백엔드·API 계약 변경은 없다.

## 문서

- [설계](design.md) — 결정 D1~D6
- [실행 계획](plan.md) — 태스크 목록
- [완료 기준 계약서](dod.md) — 수용기준·증거 로그
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- [증거](evidence/) — AC7 화면 관찰 스크린샷
- 이슈: #96 (선행 #82 D7)
