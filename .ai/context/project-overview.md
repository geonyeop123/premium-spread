# Premium Spread 비즈니스 개요

## 1. 목적

Premium Spread는 동일 암호화폐의 한국 현물 가격과 해외 헤지 거래소 가격 차이를 USD/KRW 환율로
정규화해 김치 프리미엄을 관측한다. 사용자는 한국 현물 Long과 해외 선물 Short를 함께 보유해 가격
방향 노출을 줄이고 프리미엄 변화에 따른 손익을 관리한다. 서비스는 거래를 직접 체결하지 않으며 현재는
관측, 포지션 기록, 손익 계산, 임계값 알림을 제공한다.

## 2. 핵심 식별자: MarketPair

프리미엄과 알림 조건을 symbol 하나로 식별하지 않는다.

```text
MarketPair = Symbol + KoreaExchange + ForeignExchange
canonicalKey = {symbol}:{KOREA_EXCHANGE}:{FOREIGN_EXCHANGE}
```

- `KoreaExchange`는 한국 현물 거래소여야 한다.
- `ForeignExchange`는 거래 가능한 해외 거래소여야 하며 환율 공급자는 사용할 수 없다.
- 현재 기본 pair는 `BTC:BITHUMB:BINANCE`다.
- API의 기존 symbol-only 요청은 기본 pair로 해석한다.
- 저장/조회/알림 dedupe는 pair를 보존한다. Batch 수집은 현재 설정된 한 pair만 실행한다.

이 구분이 없으면 같은 BTC라도 거래소 조합이 다른 가격, 프리미엄, 임계값 알림이 섞이므로 모든 신규
premium/position/notification 기능은 `MarketPair`를 identity에 포함해야 한다.

## 3. 김치 프리미엄

```text
foreignPriceInKrw = foreignPriceUsd × exchangeRate
premiumRate = ((koreaPrice - foreignPriceInKrw) / foreignPriceInKrw) × 100
```

- `koreaPrice`: 한국 거래소 현물 가격(KRW)
- `foreignPriceUsd`: 해외 거래소 헤지 가격(USD로 정규화)
- `exchangeRate`: USD/KRW
- 계산 입력은 모두 양수여야 한다.
- Domain `PremiumPolicy`가 계산 정밀도와 반올림의 단일 정본이다. 저장 정밀도와 API 표시 정밀도는 분리한다.
- 음수 프리미엄도 정상적인 시장 값이며 오류나 0으로 보정하지 않는다.

## 4. Position과 손익

Position 한 행은 같은 symbol의 한 MarketPair를 나타낸다.

| 측 | 방향 | 필수 값 |
|---|---|---|
| 한국 거래소 | 현물 Long | exchange, quantity, entry price(KRW) |
| 해외 거래소 | 헤지 Short | exchange, quantity, entry price(USD), leverage |
| 공통 | 진입 기준 | entry FX rate, entry premium rate, observed time |

AUTO 오픈은 해당 pair의 60초 이내 최신 Premium snapshot에서 진입 가격/환율/관측 시각을 채운다.
MANUAL 오픈은 사용자가 이를 제공하되 서버가 양수·거래소 지역·레버리지 범위를 검증한다. 진입 프리미엄은
클라이언트 값을 신뢰하지 않고 서버가 계산한다.

```text
koreaPnl = (currentKoreaPrice - koreaEntryPrice) × koreaQuantity
foreignPnlKrw = (foreignEntryPrice - currentForeignPrice) × foreignQuantity × currentFxRate
totalPnlKrw = koreaPnl + foreignPnlKrw
totalPnlPercent = totalPnlKrw / koreaEntryValue × 100
```

`isProfit`은 프리미엄 증감 부호가 아니라 `totalPnlKrw > 0`을 의미한다. 수수료, 슬리피지, 펀딩비,
세금은 현재 계산에 포함하지 않으므로 실제 거래 손익과 다를 수 있다.

## 5. 시세, 환율, 집계

- Binance/Bithumb 시세는 WebSocket으로 받고 1초 단위로 down-sample한다.
- USD/KRW는 30분마다 수집하며 DB 저장 성공 뒤 Redis를 갱신한다.
- Premium은 1초마다 계산한다.
- seconds 데이터는 minute, hour, day bucket으로 집계한다.
- 저장 시각은 UTC이며 일 단위 업무 경계/cron은 기본 `Asia/Seoul`이다.
- 시간 범위는 모두 `[from, to)`이고 손상된 cache row가 있으면 부분 값을 반환하지 않는다.

## 6. 회원 인증

- 로그인은 Access Token을 응답 body로, Refresh Token을 HttpOnly cookie로 반환한다.
- Access Token은 Bearer header로 사용하며 브라우저의 memory/sessionStorage에만 보관한다.
- Refresh Token은 회전하고 서버에는 원문 대신 HMAC hash와 session family/generation을 저장한다.
- 한 회원의 새 로그인은 기존 active refresh session을 교체한다.
- logout은 refresh session과 cookie를 폐기하지만 이미 발급된 Access Token은 만료까지 유효하다.

## 7. 프리미엄 임계값 알림

회원은 MarketPair, 방향(`ABOVE`/`BELOW`), 임계값을 가진 구독을 등록한다. 조건이 맞으면 Batch가
MySQL `notification_delivery`에 먼저 저장한 후 별도 worker가 이메일을 전송한다.

보장 수준은 **at-least-once**다.

- 같은 구독 revision/조건/pair/cooldown window의 event는 DB unique key로 한 번만 enqueue한다.
- row claim은 `FOR UPDATE SKIP LOCKED`와 owner/claim token으로 fencing한다.
- 전송 실패는 기본 1분, 5분, 30분, 2시간 계열에 jitter를 적용해 재시도하며 최대 5회 후 `FAILED`가 된다.
- stale `PROCESSING` claim은 회수하고, 원인 해소 뒤 운영자가 actor/reason을 남겨 `FAILED`를 redrive할 수 있다.
- SMTP가 수락한 뒤 DB `SENT` 반영이 실패하면 같은 메일이 다시 발송될 수 있다. 이메일 exactly-once는
  보장하지 않는다.
- SENT payload의 이메일/제목/본문은 기본 30일 후 scrub한다. event/dedupe/audit identity는 보존한다.

운영 상세는 [`docs/runbooks/durable-notification-delivery.md`](../../docs/runbooks/durable-notification-delivery.md)를
따른다.

## 8. 제품 범위와 리스크

- 실제 거래 주문, 거래소 계정 연동, 자동 청산 방지는 현재 범위가 아니다.
- 레버리지는 수익뿐 아니라 청산 위험을 확대한다.
- 거래소 장애, WebSocket 지연, FX 지연 시 계산/알림이 늦거나 건너뛸 수 있다.
- notification은 편의 기능이며 주문/리스크 관리의 유일한 신호로 사용하면 안 된다.
- 다중 pair 저장 모델은 준비되어 있지만 현재 Batch runtime은 한 configured pair만 수집한다.
- 현재 운영/스테이징 배포 대상은 없다. `prd` 설정과 배포 런북은 향후 운영 환경을 위한 계약이다.
