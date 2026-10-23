> 주: 이 brief 는 입력 기록이라 원문을 보존한다. 실제 slug 는 `owner-alert-channel`(issue #88), 산출물 경로는 `docs/work/owner-alert-channel/` 이다.

# owner-alerts-discord — architect 입력 자료 (③ 합의 결과)

worktree: `/home/yeop/IdeaProjects/premium-spread/.worktrees/feat-owner-alerts-discord` (branch `feat/owner-alerts-discord`, base origin/dev `1bce3dd`)
산출물: `docs/work/owner-alerts-discord/design.md`, `dod.md`(status: DRAFT), `plan.md`

## 사용자 합의 (2026-10-23)

### 1. 채널
- owner 알림을 **메일·Discord 를 env 로 각각 켜고 끈다**. 둘 다 켜면 둘 다 간다.
  - `ACCOUNT_PNL_EMAIL_ENABLED`(기본 true) — 이름은 설계에서 owner 알림 전반에 맞게 조정 가능하나 env 이름 변경 시 기존 runbook·env 파일 호환을 고려
  - `ACCOUNT_PNL_DISCORD_ENABLED`(기본 false), `ACCOUNT_PNL_DISCORD_WEBHOOK_URL`(비밀값 — 로그·예외·toString·원인 체인 비노출)
  - 둘 다 꺼짐 / Discord 켰는데 URL 비어 있음 → 모니터 미기동 + env 이름만 담은 WARN (기존 누락 WARN 패턴)
- Discord: 채널 webhook POST, embed 사용. 봇 토큰·OAuth 없음. 메시지 예시(사용자가 본 형식 — 정시 보고 기준):
  ```
  📊 정시 보고 09:00                (embed color 파랑 / 수익 알림 초록 / 중단 빨강 / 복구 회색)
  순수익률 -1.40%      순손익 -404,088원
  체결가 기준 -1.38% (-400,067원)
  빗썸 청산가 108,594,978원 × 0.266 BTC (호가 VWAP)
  바이낸스 청산가 81,113.7 USDT × 0.266 BTC · 환율 1,325.04
  gross -317,577원 · 예상 청산 수수료 -86,510원
  진입 수수료 0원 · 펀딩 0원
  ⚠ 미반영: ...
  바이낸스 포지션: 수동 입력(env) · 2026-10-23 09:26 KST
  ```
  Discord 메시지 2000자/embed 제한 고려(잘라서 보냄), 429 rate limit·5xx 는 재시도 대상.
- **오케스트레이터 제안 구조(설계가 검증·확정할 것):** 실계정 알림 outbox `account_pnl_mail`(V18, claim·fencing·재시도·UNIQUE(event_key) 보유)을 owner 알림 공용 outbox 로 넓힌다. V19(append-only)로 `channel` 컬럼 추가, 한 알림을 켜진 채널 수만큼 행으로 넣고 행마다 채널 형식 payload(메일 subject/body, Discord embed JSON) 저장. 기존 메일 eventKey 는 그대로(이미 쌓인 데이터와 충돌 없음). 회원용 김프 구독 알림(`notification_delivery`)은 이번 범위 밖. 테이블 rename 여부·새 테이블 신설 대안은 설계가 trade-off 와 함께 결정.

### 2. 추가할 알림 (총 10종) — 임계값은 설계가 기본값 제안 + env 로 조정 가능
계정·포지션 (실계정 모니터가 켜져 있을 때):
- (A1) **순수익률 급변**: 직전 "보고"(설계가 정의 — 정시 보고/급변 알림 기준점) 대비 ±Xp 이상 변하면 알림. 상태 테이블에 기준 수익률 컬럼이 필요할 수 있음(V19 에 포함 가능).
- (A2) **포지션 변경 감지**: 저장된 `position_fingerprint` vs 이번 회차 — 빗썸 수량·평단, 바이낸스(env) 수량·진입가 변화. 빗썸만 바뀌고 바이낸스 env 를 안 고친 불일치 경고도.
- (A3) **미반영 항목 리마인더**: 진입 수수료·펀딩 미반영이거나 manual 펀딩을 N일 갱신 안 했으면 하루 1회.
시장(김프) — BTC 단일 마켓, 실계정 모니터와 독립적으로 켜고 끌 수 있어야 함:
- (M1) **김프 급변**: 최근 N분(예 5분) 동안 ±X%p(예 0.3%p) 이상 → 알림, 쿨다운.
- (M2) **김프 기준선 돌파**: owner 용 고정 기준(env, 예 상단 2.0% / 하단 0%)을 넘거나 내려가면 알림(재무장 히스테리시스).
- (M3) **일일 요약**: 매일 09:00 KST — 전일 김프 시가·고가·저가·종가·평균, 환율, 실계정 순수익률(모니터 켜져 있으면).
- (M4) **1h/1d 신고가·신저가**: 김프가 최근 1시간/1일 고가·저가 경신 시 알림(노이즈 억제 규칙 필요 — 쿨다운·최소 폭).
운영:
- (O1) **환율 수집 지연**: 마지막 FX 수집 성공 후 N시간 경과.
- (O2) **시세 수집 끊김 지속**: WebSocket 끊김·stale 이 N분 이상 지속 (현재는 재연결 순간에만 Slack).
- (O3) **Job 연속 실패**: batch Job 실패가 N회 연속.

## 데이터 재고 (dev @ 1bce3dd, Explore 조사 요약)
- 대상 마켓 1개: BTC, BITHUMB KRW ↔ BINANCE USDT(USD-M), FX USD/KRW. 모든 Job 은 `JobExecutor`(Redis lock·timeout·`batch:last_run:{job}`·실패 시 Slack `BATCH_JOB_FAILED`).
- 수집·계산:
  - WS ticker(빗썸 체결가, 바이낸스 bookTicker mid) → Redis `ticker:{ex}:{sym}`(5s) → flush 1s `ticker:seconds:*`(5m)
  - FX 30m (exchangerate-api) → MySQL `exchange_rate` 먼저, Redis `fx:{base}:{quote}`(31m)
  - Premium 1s → Redis `premium:{k}:{f}:{sym}`(5s), `:history`(1h ZSet), `:seconds`(5m) / summary 10s `:summary:{1m,10m,1h,1d}`(high/low/current) / 분·시·일 집계 cron → Redis `:minutes|hours|days` + MySQL `premium_minute/hour/day` (OHLC·avg·count·fx)
  - Ticker 분·시·일 집계 → `ticker_*`
  - 거래 준비 평가 1s / reconcile 1m(현재 항상 skip)
  - 김프 임계값 구독 알림: `notification_delivery` outbox + `NotificationDeliveryJob`(5s)
  - 실계정 PnL 모니터 60s: REST 직접(빗썸/바이낸스), 결과 `AccountNetPnl` 은 로그(`Account PnL observed: ...`)만, 상태 1행 `account_pnl_monitor_state`(in_episode, position_fingerprint, last_sent_at, last_success_at, monitoring_since, outage_notified, version), outbox `account_pnl_mail`(event_key UNIQUE, subject, body, status, claim). 정시 보고 eventKey `account-pnl-hourly-{bucket epoch}`.
- 운영 신호: metric `ws.connection.state`, `ws.last.message.age`, `ws.stale`, `batch.job.run{outcome}`, `batch.job.last.success.age`, `fx.fetch*`, `premium.calculation`; readiness `BatchIngestionReadinessHealth`(`{exchange}_disconnected/_stale`); Slack 은 `supports/monitoring/SlackAlertService` + `IB/alert/BoundedOperatorAlertAdapter`(bounded async queue).
- 관측: 밤사이(2026-10-22~23) PnL 실패 60건 중 `STALE_FX` 49, `INSUFFICIENT_DEPTH` 11.

## 제약
- `.ai/rules/architecture.md`·`batch.md`·`testing.md`·`naming.md` 준수: Scheduler→Job 단일 진입, Job 은 Domain port 만, 앱은 infrastructure import 금지, 외부 HTTP 는 infrastructure:batch, Redis key/TTL/payload 변경 시 `modules:redis` + `docs/runbooks/redis-contract.md` 동시 변경, Flyway append-only(V19~), 시간은 주입 `Clock`·zone 명시, 범위 `[from,to)`.
- 기존 동작 불변: 수익 1% 알림·재무장·60분, 중단/복구, 정시 보고, 회차 로그, 메일 본문 golden. 기존 동결 테스트 메서드 수정 금지(추가만).
- 실계정 key·실 webhook URL 은 테스트에 쓰지 않는다(MockWebServer).
- 직전 단위 산출물(`docs/work/live-account-pnl-*`, `account-pnl-round-log`, `bithumb-orders-query-hash`) 수정 금지.
- 범위가 크므로 **plan 은 PR 분할 가능한 단계(Phase)로 나눈다** — 예: Phase 1 채널(Discord+outbox channel) / Phase 2 계정 알림 / Phase 3 시장 알림 / Phase 4 운영 알림. 각 Phase 가 독립 머지 가능해야 한다.

## 3. 알림별 채널 라우팅 (오케스트레이터 제안 — 사용자에게 제시함, ⑦ 에서 확정)
Discord webhook 은 채널당 1개 → 용도별 3채널 + 메일은 중요 알림만(예비 수단).

| 알림 | Discord 채널 | 푸시 | 메일 |
|---|---|---|---|
| 수익 알림(1%·60분 재알림) | pnl | 소리 + @here | O |
| 포지션 변경 감지(불일치 포함) | pnl | 소리 + @here | O |
| 순수익률 급변 | pnl | 소리 | - |
| 정시 보고 | pnl | 무음 | - |
| 미반영 항목 리마인더 | pnl | 무음 | - |
| 김프 기준선 돌파 | market | 소리 | - |
| 김프 급변 | market | 소리 | - |
| 1h/1d 신고가·신저가 | market | 무음 | - |
| 일일 요약 09:00 | market | 무음 | O |
| 실계정 모니터링 중단·복구 | ops | 소리 | O(중단만) |
| 환율 수집 지연 | ops | 소리 | - |
| 시세 수집 끊김 지속 | ops | 소리 | - |
| Job 연속 실패 | ops | 소리 | O |

- 무음 = Discord message `flags: 4096`(SUPPRESS_NOTIFICATIONS). @here = `content: "@here"` + `allowed_mentions` 로 here 만 허용.
- env: webhook 3개(예 `OWNER_ALERT_DISCORD_WEBHOOK_PNL/_MARKET/_OPS`) — 하나만 설정되면 그 하나로 전부 보냄(fallback). 알림 종류별 on/off·메일 포함·무음 override 가능, 기본값은 위 표. 정확한 이름·기존 `ACCOUNT_PNL_*` 호환(특히 `ACCOUNT_PNL_HOURLY_REPORT_ENABLED`, 이전 대화에서 제안한 `ACCOUNT_PNL_EMAIL_ENABLED`/`ACCOUNT_PNL_DISCORD_*` 는 아직 구현 안 됨)은 설계가 확정.
- 라우팅 표는 코드 한 곳(Domain 의 알림 종류 enum ↔ 기본 채널/푸시/메일 정책)에 두고 설계 문서에 전문으로 적는다.
