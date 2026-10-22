# 빗썸 실계정 조회 실 API 불일치 수정 (Bithumb Orders Query Hash)

실 key 로컬 실행에서 드러난 빗썸 adapter 의 두 불일치 — `/v1/orders` JWT `query_hash` 인코딩, orderbook 의 `bid_size: 0`
단계 거부 — 를 고친 소규모 버그픽스다. 원인이 실측으로 확정돼 설계·계획·DoD 단계를 건너뛰었다(orchestrator 소규모 버그픽스 경로).

## 문서

- [개발자 이해문서](understanding.md) — 증상·원인·결정·함정
- [운영 runbook](../../runbooks/account-pnl-alert.md) — AC13 검산 체크리스트 (b)
