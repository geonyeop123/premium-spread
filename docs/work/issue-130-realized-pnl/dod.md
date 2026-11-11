---
feature: 정리한 포지션의 확정 손익 — 체결 기준
slug: issue-130-realized-pnl
status: DRAFT
frozen_at:
verdict_commit:
source: https://github.com/geonyeop123/premium-spread/issues/130 (상위 #128, 선행 #129) · design.md
---

## 범위

**포함**

- 정산 대상 선정: 정리 사건(REDUCED·ADJUSTED·CLOSED) 중 아직 확정되지 않았고, 2분이 지났고, `lower`가 90일 이내인 것. 가장 오래된 건 1개를 처리하고, 그 건에서는 가장 최근 사건까지 정산한다 (design D2)
- 조회 창 `(앞 건 closed_at 또는 opened_at − 1h, 사건 시각]`과 쪽별 수량 대조 (D3)
- 창 전체를 매번 처음부터 다시 계산한다: 빗썸은 이동평균 원가, 바이낸스는 realizedPnl − 수수료 + 펀딩, 합계는 KRW (D4)
- 환산 환율: 사건 시각의 관측 행 → 10분 이내 직전 관측 행 → FX 최신 값 순으로 쓴다 (D5)
- 쪽별 상태 PENDING·CONFIRMED·MANUAL, 실패 사유 범주, 시도 횟수, 확정이 뒤로 가지 않는 조건부 UPDATE (D6·D7)
- V26 nullable 열 추가 (D7)
- 조회 전용 adapter 2개(빗썸 주문 이력·상세, 바이낸스 userTrades·income), GET만 사용 (D8)
- 도메인 값 타입·`RealizedPnlPolicy`·포트 (D9)
- 새 Job `AccountRealizedPnlJob`(5분 주기), 스케줄러, 배선, env `account-pnl-monitor.realized-pnl.enabled` (D1)
- runbook `docs/runbooks/account-pnl-alert.md`에 확정 손익 확인 방법 추가

**제외** *(명시적으로 하지 않는 것 — scope creep 차단선)*

- 확정 손익 알림 발송, 조회 API·화면
- 모니터 순손익(추정치) 계산·기존 알림 규칙 변경
- 체결 행 단위 저장 테이블
- 90일 넘은 건, 입출금으로 수량이 어긋난 건의 강제 확정

## 기존 gate 조사

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | 있음 — `./gradlew architectureTest` (모듈 의존, 앱의 infrastructure import 금지, `PrivateAccountReadOnlyArchitectureTest`의 GET 외 호출·주문 경로 금지와 `REQUIRED_TARGETS` 자기검증) | 예 — AC7. 새 adapter 파일명을 `*PrivateAccountAdapter.kt`로 지어 대상에 넣고 `REQUIRED_TARGETS`에 추가한다 |
| lint 규칙 | 로컬 `lint` task 없음. ktlint 1.8.0·detekt는 CI `5. ktlint + detekt` | CI에서만. 로컬 ktlint jar로 사전 확인 |
| custom gradle task | `:infrastructure:common:verifyMigrations`, `:infrastructure:common:integrationTest`, `:apps:batch:integrationTest`(MySQL + MockWebServer base `BatchIntegrationTestBase`), `:domain:test`, `:infrastructure:batch:test` | 예 — AC1~AC9 실행 수단 |
| CI job | `.github/workflows/quality-gate.yml` | 최종 SHA 회귀 확인 |
| migration 테스트 관례 | `V24AccountPositionHistoryMigrationIntegrationTest`, `V25ReferenceStepStateMigrationIntegrationTest` | 예 — AC8의 V26 판 |
| batch fixture schema 계약 | `AccountPnlFixtureSchemaIntegrationTest`(batch `batch-schema.sql`과 Flyway 최신 스키마 대조) | 예 — R3. V26 열을 fixture에 반영해야 통과 |

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 빗썸 체결 목록(매수 2건 → 매도 1건 → 매수 1건 → 매도 2건)을 넣으면, 사건마다의 실현 손익이 수수료를 포함한 이동평균 원가로 계산한 값과 같다. 전량 정리 후의 값은 `Σ매도(금액 − 수수료) − Σ매수(금액 + 수수료)`와 같다. 바이낸스 실현 손익은 `Σ realizedPnl − Σ |수수료| + Σ 펀딩`이고(수수료가 음수로 와도 비용으로 뺀다), 합계는 `빗썸 + 바이낸스 순 × 환율`을 원 단위 HALF_UP으로 반올림한 값이다. 빗썸 순수량이 기대 수량과 dust 이상 다르거나, 바이낸스 부호 수량이 기대 값과 하나라도 다르면 결과는 null이다 | 이슈 요구 1·수용 1·2, design D3·D4 | `요구` · `추론`(이동평균 원가, dust 허용) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.RealizedPnlPolicyTest` | `./gradlew :domain:test --tests io.premiumspread.domain.accountpnl.RealizedPnlPolicyTest --offline --no-daemon` | exit 0 |
| AC2 | 실제 adapter 2개를 MockWebServer fixture에, 실제 store를 MySQL에 연결하고 Job을 실행한다. 세 경우를 본다. (a) 전량 정리된 건이면 건의 `korea_realized_krw`·바이낸스 3값·`total_realized_krw`가 fixture 체결을 손으로 합한 값과 같고, 두 쪽 상태가 CONFIRMED, `settled_seq`가 CLOSED 사건 seq다. (b) REDUCED 뒤 실행하면 부분 정리분이 저장되고, 이어서 CLOSED 뒤 실행하면 부분분을 포함한 전체 합으로 바뀐다. (c) 같은 회차를 다시 실행하거나 새 Job 인스턴스로 실행해도, 그리고 겹치는 7일 창이 같은 체결 id를 두 번 돌려줘도 저장 값이 바뀌지 않는다 | 이슈 수용 1·2·3 ("mock 서버 fixture", "누적", "두 번 세지 않음 — 재시도·재시작 포함") | `요구` | T1 | `신규테스트` `io.premiumspread.application.job.AccountRealizedPnlSettlementIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests io.premiumspread.application.job.AccountRealizedPnlSettlementIntegrationTest --offline --no-daemon` | exit 0 |
| AC3 | `findTarget`은 정리 사건 seq가 `settled_seq`보다 크고, 사건이 2분 이상 지난 건 가운데 가장 오래된 건을 돌려준다. `lower`가 90일보다 오래된 건(89일·91일 경계 대조)은 돌려주지 않는다. 그 건 안에서 조건을 만족하는 가장 최근 사건을 목표로 삼고, `lower`는 앞 건의 `closed_at`이며 앞 건이 없으면 `opened_at − 1h`다. INCREASED만 있는 건, 2분이 안 된 사건, 이미 따라잡은 건은 돌려주지 않는다. 기록은 같은 건에 더 작은 seq로 오면 행을 바꾸지 않는다. `findFxAt`은 사건 시각의 관측 행, 10분 이내 직전 행 순으로 찾고 없으면 null이다 | design D2·D3·D5·D7 | `추론`(선정 규칙·단조 갱신·환율 순서) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountRealizedPnlStoreIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.persistence.jdbc.accountpnl.JdbcAccountRealizedPnlStoreIntegrationTest --offline --no-daemon` | exit 0 |
| AC4 | Job 단위 테스트(가짜 port)에서 다섯 경우를 본다. (a) 빗썸이나 바이낸스 조회가 `AccountReadException`을 던지면 해당 쪽은 PENDING, `settlement_failure`는 `KOREA_READ`·`FOREIGN_READ`, 시도 횟수는 +1, `settled_seq`는 그대로이고 `JobResult`는 실패가 아니다. 다음 실행에서 조회가 성공하면 CONFIRMED로 바뀐다. (b) 수량이 맞지 않으면 `*_QUANTITY` 사유로 PENDING이고, 다음 실행에 맞으면 확정된다. (c) 목표 사건이 MANUAL_ENV이면 바이낸스는 MANUAL이고 바이낸스 값·합계는 NULL이며, 빗썸이 확정되면 `settled_seq`가 오른다. (d) 사건 시각 환율이 없으면 FX port 최신 값을 쓰고, 그것도 신선도를 넘으면 `FX` 사유로 PENDING이다. (e) 취소 예외는 삼키지 않고 interrupt를 되살린다 | 이슈 요구 3·4 ("조회 실패 시 미확정, 다음 회차 재시도", "수동 모드는 바이낸스 미확정"), 위험 2 (반영 지연) | `요구` · `추론`(환율 대체, 취소 전파) | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest` | `./gradlew :apps:batch:test --tests io.premiumspread.application.job.accountpnl.AccountRealizedPnlJobTest --offline --no-daemon` | exit 0 |
| AC5 | 빗썸 adapter는 MockWebServer에 GET `/v1/orders`(페이지)와 GET `/v1/order?uuid=`만 보낸다. 창을 걸친 주문은 `created_at`이 창 안인 체결만 돌려준다. 금액은 그 체결의 `funds`이고, 수수료는 주문의 `paid_fee`를 체결 `funds` 비율로 나눈 값이다(나머지는 마지막 체결, 한 주문의 체결 수수료 합 = `paid_fee`). 주문 생성 시각이 `lower − 7일`보다 오래되면 다음 페이지를 읽지 않는다. 20페이지를 넘으면 `WINDOW_LIMIT` 범주로 실패하고, 401은 `BITHUMB_AUTH`다 | design D8 | `추론`(페이지 종료·한도) · `요구`(GET만) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbFillHistoryPrivateAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbFillHistoryPrivateAccountAdapterTest --offline --no-daemon` | exit 0 |
| AC6 | 바이낸스 adapter는 `lower`부터 `upper`까지 7일 이하 창으로 GET `/fapi/v1/userTrades`를 보내고, GET `/fapi/v1/income`은 `incomeType=FUNDING_FEE`와 `symbol=BTCUSDT`를 붙여 보낸다. 응답에 섞인 다른 심볼 펀딩 행은 버리고, 창들이 같은 id를 돌려주면 한 번만 담는다. USDT·BNB 수수료가 음수로 와도 절댓값으로 돌려준다. 창 하나가 1000건이면 `WINDOW_LIMIT`로 실패한다. BNB 수수료는 `BNBUSDT` 시세로 USDT 환산하고, 숏 체결은 음수 부호 수량이다 | design D8 | `요구`(GET만, 7일 창 위험) · `추론`(id 중복 제거) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.binance.BinanceFillHistoryPrivateAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests io.premiumspread.infrastructure.batch.exchange.binance.BinanceFillHistoryPrivateAccountAdapterTest --offline --no-daemon` | exit 0 |
| AC7 | 조회 전용 아키텍처 테스트가 새 adapter 2개를 대상에 넣은 상태(`REQUIRED_TARGETS` 추가)로 통과하고, 모듈 경계 테스트도 통과한다 | 이슈 수용 4 (`PrivateAccountReadOnlyArchitectureTest`) | `요구` | T1 | `기존` `./gradlew architectureTest` | `./gradlew architectureTest --offline --no-daemon` | exit 0 |
| AC8 | V26을 V25 상태의 기존 건 행에 적용하면 새 열이 모두 NULL이고 기존 열 값은 그대로다. 상태 열에는 PENDING·CONFIRMED·MANUAL 밖의 값을 넣을 수 없다 | design D7 | `추론`(이행 안전) | T1 | `신규테스트` `io.premiumspread.infrastructure.common.migration.V26RealizedPnlMigrationIntegrationTest` | `./gradlew :infrastructure:common:integrationTest --tests io.premiumspread.infrastructure.common.migration.V26RealizedPnlMigrationIntegrationTest --offline --no-daemon` | exit 0 |
| AC9 | 모니터 활성 조건을 만족하면 `AccountRealizedPnlJob` bean이 생긴다. `account-pnl-monitor.realized-pnl.enabled=false`이면 생기지 않는다. 바이낸스 manual 모드에서는 바이낸스 체결 port 없이 생기고, Job이 바이낸스 쪽을 MANUAL로 다룬다 | design D1·D8 | `추론`(배선) | T1 | `신규테스트` `io.premiumspread.config.AccountRealizedPnlWiringTest` | `./gradlew :apps:batch:test --tests io.premiumspread.config.AccountRealizedPnlWiringTest --offline --no-daemon` | exit 0 |
| AC10 | owner가 실계정 로컬 batch에서 확정된 건 1건(seed 건 2026-10-28~11-08 또는 실제 정리 건)의 `korea_realized_krw`·바이낸스 3값을 빗썸·바이낸스 거래 내역과 대조해 일치를 확인한다 | 이슈 수용 5 | `요구` | T4 | 사람 육안 — runbook 확인 절차 | — | owner가 PR 코멘트로 확인 |

**기준 개수**: 10개

**분할하지 않는 이유**: AC1~AC9는 확정 손익 하나를 계층별로 나눠 잰 것입니다(계산·끝 단 통합·대상 선정 저장·Job 재시도·adapter 2개·조회 전용·migration·배선). 이슈를 나누면 어느 쪽도 혼자서는 값을 확정하지 못해 배포 단위가 되지 않습니다.

**티어 강등 사유**

- AC10 → T4: 실계정 credential과 거래 내역은 owner 로컬에만 있습니다. 테스트·CI에 실제 주소를 넣지 않는 것이 저장소 규칙입니다(`.ai/rules/testing.md`). AC1~AC9가 같은 동작을 fixture로 잽니다.

## 검사 산출물

해당 없음(신규스크립트 없음).

## 회귀 방어선

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모니터 Job·기존 adapter 동작이 바뀌지 않는다. 기존 테스트가 수정 없이 통과한다 | `git diff --exit-code dev -- apps/batch/src/test/kotlin/io/premiumspread/application/job/accountpnl infrastructure/batch/src/test/kotlin/io/premiumspread/infrastructure/batch/exchange` · `./gradlew :apps:batch:test :infrastructure:batch:test --offline --no-daemon` |
| R2 | 모듈 경계 | `./gradlew architectureTest --offline --no-daemon` |
| R3 | 기존 저장소·통합 동작, batch fixture schema 일치 | `./gradlew :infrastructure:common:integrationTest :apps:batch:integrationTest --offline --no-daemon` |
| R4 | 문서 검사 | `bash docs/check-documentation.sh` |
| R5 | migration 버전 유일성·파괴 SQL gate·빈 DB/이전→최신 경로 | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC10 | | | | |

## 변경 요청

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|

## 최종 판정

```
DoD VERDICT: issue-130-realized-pnl @ <commit SHA>
  수용기준 표:     10개  (T1 9 · T2 0 · T3 0 · T4 1)
  T1/T2 자동:      9개 중 <p>개 PASS
  T3 기록 제출:    0개 중 0건
  T4 사람 확인:    1개 중 <r>건 완료, <1-r>건 대기
  변경 요청:       <k>건
  =>
```
