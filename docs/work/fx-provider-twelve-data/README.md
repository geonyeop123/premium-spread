# 환율 제공자 Twelve Data 교체와 FX 캐시 TTL 여유 (issue #93)

USD/KRW 환율을 하루 1회 갱신하던 exchangerate-api 대신 Twelve Data 에서 5분마다 수집한다. 수집 주기 5분 ↔ 허용 공백 30분 ↔ 캐시 TTL 40분을
테스트와 기동 검증으로 묶어, 캐시가 freshness 판정보다 먼저 만료되던 #93 을 함께 해결한다.

## 문서

- [설계](design.md) — 시간 계약(§3), Twelve Data adapter(§4), 크레딧·timeout 예산(§5), 문서 갱신 대상(§9), 변경 이력(§13)
- [실행 계획](plan.md) — Task 0~6
- [완료 기준 계약서](dod.md) — 수용기준 8개 (`status: FROZEN`, ⑩ PASS_WITH_PENDING · AWAITING_HUMAN — AC7 문서 검토, AC8 owner 실기동)
- [개발자 이해문서](understanding.md) — 배경·결정·함정·후속
- [배포 runbook](../../runbooks/deployment.md) — 환율 제공자 절(크레딧 식·지울 override·timeout 관계)
