# #130 정리한 포지션의 확정 손익 — 체결 기준 (design)

- 이슈: https://github.com/geonyeop123/premium-spread/issues/130 (상위 #128 · 선행 #129)
- 트랙: feature-track **L** — 금액 계산, 같은 체결 중복 방지, 3개 이상 모듈(domain·infrastructure:batch·infrastructure:common·apps:batch)

## 1. 왜

모니터 순손익은 "지금 전량 청산하면"을 가정한 추정치입니다. 실제 정리 체결가와 수수료, 진입부터 정리까지 받은 펀딩비가 반영되지 않습니다. 그래서 정리한 포지션 건의 손익이 계좌와 맞지 않습니다. #129가 포지션 건과 사건(OPENED·INCREASED·REDUCED·ADJUSTED·CLOSED)을 남기므로, 이번에는 정리 사건마다 그 시점까지의 체결을 읽어 **확정 손익**을 건에 저장합니다.

## 2. 목표 · 비목표 · 반목표

**목표**
- 포지션 건의 정리 사건(REDUCED·ADJUSTED·CLOSED)이 생기면 건 구간의 체결을 읽어 실현 손익을 확정하고 건에 저장한다. 부분 정리를 거쳐 전량 정리될 때까지 누적된다.
- 체결 조회가 실패하거나 아직 반영되지 않았으면 미확정으로 두고 다음 회차에 다시 시도한다.
- 바이낸스 수동 모드에서는 바이낸스 쪽을 미확정(MANUAL)으로 표시한다.

**비목표**
- 확정 손익 알림 발송, 조회 API·화면. 값은 DB에서 확인한다.
- 모니터 순손익(추정치) 계산식과 기존 알림 변경.
- 90일 넘게 지난 건의 복원. 바이낸스는 userTrades를 6개월, 펀딩(income)을 3개월까지만 제공합니다.

**반목표** (기술적으로 돌아가도 실패로 보는 것)
- 같은 체결이 두 번 합산된다. 재시도, 재시작, 겹치는 조회 창 모두 포함한다.
- 체결 조회 실패가 모니터 회차(순손익·알림·상태 저장)를 늦추거나 실패시킨다.
- 주문·취소·출금처럼 GET 외의 거래소 호출이 생긴다.
- 수량이 맞지 않는(반영이 덜 된) 체결 묶음으로 값을 확정한다.

## 3. 결정

### D1. 별도 Job으로 정산한다

새 `AccountRealizedPnlJob`은 5분마다 실행합니다. lock은 `lock:account-pnl:settlement`이고 timeout은 120초입니다. 모니터 Job 안에 넣지 않습니다.

- **근거:** 모니터는 1분 주기에 40초 timeout이고, 호가 신선도 skew가 5초입니다. 체결 페이지를 넘기는 조회가 섞이면 회차 판정이 흔들립니다. 분리하면 "조회 실패가 순손익·알림에 영향 없음"이 구조로 보장됩니다.
- 배선 조건은 모니터와 같습니다(`AccountPnlMonitorEnabledCondition`). 추가로 `account-pnl-monitor.realized-pnl.enabled`(기본 true)가 켜져야 합니다.

### D2. 정산 대상과 시점

정산 대상은 정산 상태가 정리 사건을 따라잡지 못한 건입니다. 조건은 다음과 같습니다.
- `REDUCED`·`ADJUSTED`·`CLOSED` 사건 가운데 seq가 `settled_seq`(없으면 0)보다 크다.
- 그 사건의 `occurred_at`이 `now − settle-delay`(기본 2분) 이하다. 정리 직후에는 체결 내역 반영이 늦을 수 있어 바로 읽지 않습니다.

대상과 처리 순서는 이렇게 정합니다.
- 한 회차에 가장 오래된 대상 건 1개만 처리합니다.
- 그 건에서는 조건을 만족하는 사건 중 **가장 최근 사건 1개**(목표 사건)까지 정산합니다.
- 중간 사건을 하나씩 밟지 않습니다. 값은 매번 처음부터 다시 계산하므로(D4) 마지막 사건까지 계산하면 앞 사건분이 포함됩니다.

### D3. 조회 창과 수량 대조

조회 창은 `(lower, upper]`입니다.
- **upper:** 목표 사건의 `occurred_at`. 모니터가 그 잔고를 관측한 회차 시각입니다.
- **lower:** 바로 앞 건의 `closed_at`입니다. 앞 건이 없으면 `opened_at − first-episode-lookback`(기본 1시간)입니다.
  - 앞 건이 닫힌 뒤 이 건이 열리기 전까지의 체결은 이 건을 연 체결입니다.

**귀속 전제:** #129 정의상 포지션 건은 계좌의 빗썸 KRW-BTC 보유와 바이낸스 BTCUSDT 포지션 **전체**입니다. 그래서 창 안의 이 두 상품 체결은 모두 이 건에 속합니다. 같은 계좌에서 전략과 무관한 BTC 거래를 따로 하지 않는다는 것이 운영 전제이고, runbook에 명시합니다. 체결이 1분 안에 0→x→0으로 왕복해 모니터가 보지 못한 경우에도 그 손익은 계좌 현금 흐름이므로 이 건에 넣습니다.

**수량 대조가 확정 조건입니다.** 창 안 체결의 순수량이 목표 사건 leg와 같아야 그 쪽(빗썸·바이낸스)을 확정합니다.
- 빗썸: `Σ매수 수량 − Σ매도 수량`과 사건 `korea_quantity`의 차이가 `KOREA_DUST`(0.0001) 미만이어야 합니다. 사건 leg는 dust 미만을 0으로 정규화하기 때문입니다.
- 바이낸스: `Σ부호 수량`이 사건 `foreign_signed_quantity`와 정확히 같아야 합니다(숏은 음수).

다르면 체결이 아직 반영되지 않았거나 창 밖 체결이 섞인 것입니다. 이때는 미확정으로 두고 다음 회차에 다시 봅니다. 이 대조가 "반영 덜 된 묶음으로 확정하지 않는다"를 지킵니다.

### D4. 계산 — 매번 창 전체를 다시 계산한다 (누적 증분 금지)

확정 값은 창 안 체결 전체를 순수 함수로 계산한 결과입니다. 앞선 확정 값에 증분을 더하지 않습니다.
- 그래서 같은 사건을 재시도·재시작으로 다시 정산해도 같은 체결 집합이면 같은 값이 나옵니다.
- 한 번의 조회 안에서 겹치는 창이나 페이지로 같은 체결이 다시 오면 체결 id로 한 번만 셉니다.
- 체결을 행 단위로 저장하는 테이블은 두지 않습니다(D7). 중복 방지의 근거는 "증분이 없다"는 구조입니다.

**빗썸 (KRW)** — 체결(trade) 단위로 셉니다.
- 체결 시각 순으로 이동평균 원가를 씁니다. 원가에는 매수 수수료가 포함됩니다.
- 매수: 원가 합 += 체결금액 + 수수료, 보유 수량 += 수량.
- 매도: 실현 += (체결금액 − 수수료) − 수량 × (원가 합 / 보유 수량). 원가 합과 보유 수량은 그 비율만큼 줄입니다.
- 전량 정리된 건이면 이 값은 `Σ매도(체결금액 − 수수료) − Σ매수(체결금액 + 수수료)`와 같습니다.
- 부분 정리 시점의 값은 이미 판 수량에 원가를 평균으로 배분한 결과입니다.

**바이낸스 (USDT)**
- 실현 손익 = Σ `realizedPnl`
- 수수료 = Σ |`commission`|. 거래소 응답의 부호와 무관하게 비용으로 보고 절댓값을 씁니다. BNB 수수료는 정산 시점 `BNBUSDT` 시세로 환산합니다(기존 adapter 규칙과 같음).
- 펀딩 = 창 안 `FUNDING_FEE` income 합
- 순 = 실현 손익 − 수수료 + 펀딩

**합계 (KRW)** = 빗썸 실현 + 바이낸스 순 × 환산 환율. 반올림은 원 단위 HALF_UP이며 저장할 때만 합니다.

### D5. 환산 환율 — 정리 회차의 실시간 환율

환산 환율은 목표 사건과 같은 시각(`observed_at = occurred_at`)에 모니터가 남긴 관측 행 `account_pnl_observation.fx_rate`입니다. #131에서 정한 "실시간 환율" 원칙과 같고, 정리를 판정한 바로 그 환율입니다.
- 그 행이 없으면 `occurred_at` 이전 10분 안의 가장 최근 관측 행을 씁니다.
- 그것도 없으면 정산 시점에 `FxRateReadPort` 최신 값을 쓰고, 신선도 검사는 모니터와 같습니다.
- `fx_observed_at`에 실제로 쓴 환율의 시각을 남깁니다.
- USDT 원금액(실현 손익·수수료·펀딩)은 그대로 저장해, 실제 환전 시점 환율로 나중에 다시 계산할 수 있게 합니다.

### D6. 쪽별 상태

`korea_settlement`과 `foreign_settlement`는 각각 `PENDING`·`CONFIRMED`·`MANUAL` 중 하나입니다.
- 목표 사건(또는 건)의 `position_source`가 `MANUAL_ENV`이면 바이낸스는 `MANUAL`입니다. 바이낸스 값과 합계는 NULL이고, 빗썸은 그대로 정산합니다.
- 두 쪽이 모두 `CONFIRMED`일 때만 `total_realized_krw`·`fx_rate`·`settled_seq`·`settled_at`을 채웁니다.
  - MANUAL이 끼면 빗썸이 확정되는 대로 `settled_seq`를 올리고 합계는 NULL로 둡니다. 계속 재조회하지 않기 위해서입니다.
- 한쪽만 확정되면 확정된 쪽 값은 저장하고 `settled_seq`는 올리지 않습니다. 다음 회차에 다시 시도하며, 두 쪽 모두 다시 계산합니다.
- 실패 사유는 `settlement_failure`에 범주 하나로 남깁니다: `KOREA_READ`·`FOREIGN_READ`·`KOREA_QUANTITY`·`FOREIGN_QUANTITY`·`FX`·`WINDOW_LIMIT`. 시도 횟수는 `settlement_attempts`에 남습니다. 금액·수량은 로그에 남기지 않습니다.
- 포기 규칙은 없습니다. 같은 사건이 계속 맞지 않으면 다음 정리 사건이 생길 때까지 5분마다 다시 봅니다.
  - `lower`가 90일보다 오래된 건은 대상에서 뺍니다. 바이낸스 펀딩 이력이 3개월만 보관되기 때문입니다. 그런 건의 상태 열은 NULL 또는 PENDING으로 남고, runbook에 그 뜻을 적습니다.

### D7. 저장 — V26, 건 테이블에 nullable 열 추가

`account_position_episode`에 아래 열을 추가합니다. 모두 NULL 허용이며 기존 행 변환은 없습니다.

| 열 | 타입 | 뜻 |
|---|---|---|
| `korea_settlement` / `foreign_settlement` | VARCHAR(16) | PENDING·CONFIRMED·MANUAL. NULL = 정리 사건 없음 |
| `korea_realized_krw` | DECIMAL(24,4) | 빗썸 실현 손익 |
| `foreign_realized_pnl_usdt` / `foreign_commission_usdt` / `foreign_funding_usdt` | DECIMAL(30,8) | 바이낸스 원금액 |
| `fx_rate` | DECIMAL(20,8) | 환산 환율 |
| `fx_observed_at` | DATETIME(6) | 그 환율의 관측 시각 |
| `total_realized_krw` | DECIMAL(24,0) | 합계 |
| `settled_seq` | INT | 확정이 따라잡은 사건 seq |
| `settled_at` | DATETIME(6) | 확정 시각 |
| `settlement_attempts` | INT | 시도 횟수 |
| `settlement_failure` | VARCHAR(32) | 마지막 실패 사유 범주 |

- 쓰기는 `UPDATE … WHERE id = ? AND (settled_seq IS NULL OR settled_seq < ?)` 한 문장입니다. 확정이 뒤로 가지 않게 막습니다.
- 모니터의 건 UPDATE와는 쓰는 열이 겹치지 않습니다. 그래서 행 잠금 외에 조율할 것이 없습니다.
- 정산 Job 동시 실행은 Redis lock이 1차로 막고, 이 조건이 2차로 막습니다.
- 금액은 DECIMAL입니다. 비교 대상이 아니라 표시·합산 값이라 #129 leg처럼 문자열로 저장할 이유가 없습니다.

### D8. 거래소 조회 — 새 조회 전용 adapter 2개, GET만 사용

공통 규칙은 셋입니다.
- 두 adapter의 파일명은 `*PrivateAccountAdapter.kt` 규칙을 따릅니다. 그래야 `PrivateAccountReadOnlyArchitectureTest` 대상이 되고, 그 테스트의 `REQUIRED_TARGETS`에 추가해 빠지지 않게 고정합니다.
- 실패는 `AccountReadException`으로만 던지고, 취소는 다시 던집니다.
- 서명 클라이언트는 기존 것(`BithumbJwtSigner`·`BinanceRequestSigner`·`BinanceFuturesReadClient`)을 재사용합니다.

**`BithumbFillHistoryPrivateAccountAdapter`** : `KoreaSpotFillReadPort`
- `/v1/orders`(state done·cancel, 최신순, 100건 페이지)를 읽습니다. 주문 생성 시각이 `lower − 7일`보다 오래되면 멈추고, 최대 20페이지입니다.
  - 7일보다 오래 걸려 있던 지정가 주문이 창 안에서 체결되면 그 체결은 빠집니다. 그러면 순수량이 맞지 않아 PENDING으로 남습니다. 틀린 값으로 확정하지는 않습니다.
- 체결 수량이 있는 주문마다 `/v1/order?uuid=` 상세의 `trades[]`를 읽습니다. **체결 하나하나**의 `created_at`이 창 안인 것만 돌려줍니다(id = `주문 uuid:체결 uuid`).
- 수수료는 주문의 `paid_fee`를 그 주문 체결들의 `funds` 비율로 나눕니다. 나머지는 마지막 체결에 넣어, 주문 전체 체결 수수료의 합이 `paid_fee`와 같게 합니다.
- 페이지 한도에 걸리면 `WINDOW_LIMIT`로 실패시킵니다.

**`BinanceFillHistoryPrivateAccountAdapter`** : `ForeignPerpFillReadPort`
- `/fapi/v1/userTrades`를 `lower`부터 7일 창으로 **앞으로** 걸어 `upper`까지 읽습니다. 창 하나가 1000건이면 `WINDOW_LIMIT`로 실패시킵니다.
- `/fapi/v1/income?incomeType=FUNDING_FEE&symbol=BTCUSDT`는 `startTime`/`endTime`, 1000건 페이지로 읽습니다. 응답 행의 `symbol`이 BTCUSDT가 아니면 버리고, `asset`이 USDT가 아니면 실패시킵니다.
- 체결 id와 `tranId`로 중복을 없앱니다. BNB 수수료 환산은 `/fapi/v1/ticker/price` 공개 시세입니다.
- 수동 모드에서는 이 adapter를 만들지 않고, 정산은 바이낸스 쪽을 `MANUAL`로 둡니다.

### D9. 도메인

- **값 타입:** `KoreaSpotFill`(id(문자열), side, quantity, fundsKrw, feeKrw, filledAt), `ForeignPerpFill`(id, signedQuantity, realizedPnlUsdt, commissionUsdt, filledAt), `ForeignFunding`(id, incomeUsdt, at). toString은 타입명과 시각만 냅니다(실계정 값 금지).
- **`RealizedPnlPolicy`(순수 함수):**
  - `korea(fills, expectedQuantity): KoreaRealized?`
  - `foreign(fills, funding, expectedSignedQuantity): ForeignRealized?`
  - `total(korea, foreign, fx): BigDecimal`
  - 수량이 다르면 null을 돌려줍니다.
- **포트:**
  - `KoreaSpotFillReadPort`, `ForeignPerpFillReadPort`
  - `AccountRealizedPnlStore`: 대상 조회 `findTarget(now, delay, maxAge): SettlementTarget?`, `record(result)`, 사건 시각 환율 조회 `findFxAt(at, tolerance)`
- `SettlementTarget`은 건 id, 목표 사건 seq·시각·leg·source, `lower`를 담습니다.

## 4. 남은 것 (구현자 판단)

- Job 클래스 내부 분해와 detekt 한도(ReturnCount ≤ 4, TooManyFunctions 20) 대응
- mock 서버 fixture의 구체 숫자
- 로그 문구. 범주·건 id·seq만 남기고 금액·수량은 남기지 않습니다.

## 5. 위험

- **수량 대조가 계속 맞지 않는 경우:** 입출금으로 BTC가 들어오고 나가거나, 창 밖 지정가 체결이 섞이면 건이 PENDING으로 남습니다. 틀린 값으로 확정하지 않는 쪽을 택했고, 이 경우 사유 범주가 남습니다.
- **첫 건의 lower:** `opened_at − 1h`에 모니터가 꺼져 있었다면 연 체결이 창 밖일 수 있습니다. 결과는 같은 방식으로 PENDING입니다.
- **환율 대체:** 사건 시각 관측 행이 없으면 대체 환율을 씁니다. 어떤 환율을 썼는지는 `fx_observed_at`으로 드러납니다.
- **사람 확인 대상:** seed한 2026-10-28~11-08 건은 실계정 체결이 실제로 있는 구간입니다. 정산이 이 건을 대상으로 잡으므로, owner 대조(사람 확인)를 이 건으로 할 수 있습니다.
