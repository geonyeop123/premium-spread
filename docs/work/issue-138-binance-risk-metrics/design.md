# #138 바이낸스 위험 지표 조회 — 설계

이슈: https://github.com/geonyeop123/premium-spread/issues/138 (상위 #137)

## 결정

### D1. 도메인 값 타입은 출처별로 둘로 나눈다

`ForeignPerpAccountSnapshot`에 nullable 필드 두 개를 기본값 `null`로 추가한다.

| 필드 | 타입 | 출처 | 없음(null)의 뜻 |
|---|---|---|---|
| `positionRisk` | `ForeignPerpPositionRisk?` | `/fapi/v3/positionRisk` | 포지션 없음, 수동 모드, 해석 실패 |
| `symbolConfig` | `ForeignPerpSymbolConfig?` | `/fapi/v1/symbolConfig` | 포지션 없음, 수동 모드, 요청·해석 실패 |

- `ForeignPerpPositionRisk(liquidationPrice: BigDecimal?, markPrice, isolatedMargin, isolatedWallet, maintMargin, positionInitialMargin, notional)` — 전부 `BigDecimal`. `liquidationPrice == null`이 "청산가 없음"이다(응답 0 → null).
- `ForeignPerpSymbolConfig(leverage: Int, marginType: ForeignPerpMarginType)`, `enum ForeignPerpMarginType { CROSSED, ISOLATED }`.
- **근거:** 두 값은 실패 단위가 다르다(positionRisk는 이미 필수 호출, symbolConfig는 부분 실패 허용). 한 타입에 묶으면 "일부만 null"을 표현하려고 내부가 다시 nullable 투성이가 된다.
- 불변식(도메인 `init`): `markPrice > 0`, `liquidationPrice`는 null 이거나 `> 0`, 증거금 4종(`isolatedMargin`·`isolatedWallet`·`maintMargin`·`positionInitialMargin`) `>= 0`, `notional`은 부호 있음(숏 음수), `leverage >= 1`. `toString`은 금액을 노출하지 않는다(기존 `ForeignPerpBalance` 관례).

### D2. 포지션이 없으면 두 필드 모두 null 이고 symbolConfig 를 호출하지 않는다

이슈 "포지션이 없을 때: 위험 지표가 없다"를 그대로 따른다. 수량 0 판정은 기존 `readPosition()`이 한다. 수량이 0이 아니어도 `liquidationPrice`가 0이면 `positionRisk`는 남기고 청산가만 null이다.

### D3. positionRisk 위험 필드 해석 실패는 회차를 깨지 않는다

기존 필수 필드(`positionAmt`, `entryPrice`) 해석 실패는 지금처럼 `UNEXPECTED`로 던진다. 새로 읽는 7개 필드 중 하나라도 없거나 숫자가 아니거나 불변식을 어기면 `positionRisk = null` + WARN이다.
**근거:** 이번 이슈가 더하는 값이 없다고 해서 이미 동작하던 순손익 회차가 실패하면 안 된다(이슈의 부분 실패 원칙을 같은 값 묶음에 일관 적용).

### D4. symbolConfig 는 잔고와 같은 방식으로 한 번만 보낸다

- 위치: `commissionRate` 다음, `account` 앞. 깊이(depth)는 여전히 마지막.
- `signed.send(path, "symbol=BTCUSDT", BALANCE_TIMEOUT)` 한 번. `-1021`도 재측정·재시도 없이 요청 실패로 흡수한다 — 회차 시세 신선도를 잡아먹지 않게 한다(잔고 호출과 같은 이유).
- 응답 배열에서 `symbol == BTCUSDT` 행을 찾는다. 없으면 MISSING, `leverage`가 양의 정수가 아니면 NOT_NUMBER/NOT_POSITIVE, `marginType`이 CROSSED/ISOLATED가 아니면 UNKNOWN.
- 취소(인터럽트)는 흡수하지 않고 다시 던진다(잔고와 동일).
- **timeout 예산:** 깊이 조회 전 선택 호출은 잔고·symbolConfig 둘이고 각각 1.5초라 최악 합계는 3초다. Job 의 시세 skew 한도(`MAX_QUOTE_SKEW` 5초) 안에 들어간다. 선택 호출을 더 붙이면 이 합계를 다시 계산한다(최종 리뷰 지적 반영).

### D5. 로그는 `AccountBalanceLog`를 재사용한다

`BalanceItem`에 `BINANCE_POSITION_RISK`, `BINANCE_SYMBOL_CONFIG`을 추가하고 `BalanceUnavailableReason`에 `UNKNOWN`을 추가한다. 로그에는 항목·사유·범주·예외 클래스명만 남고 값은 남지 않는다(이슈: "잔고 조회 실패와 같은 방식").

### D6. 아키텍처 테스트는 "읽기 경로가 스캔 대상 안에 있다"를 단정한다

`BinanceFuturesPrivateAccountAdapter.kt`는 이미 GET 전용 스캔 대상이다. 새 경로가 스캔 대상 밖 파일로 새는 것을 막으려고 `REQUIRED_READ_PATHS = {"/fapi/v3/positionRisk", "/fapi/v1/symbolConfig"}` 각각이 스캔 대상 파일 중 하나에 나타나는지 확인하는 테스트를 추가한다.

### D7. 수동 모드·Job·소비자는 바꾸지 않는다

`BinanceFuturesManualAccountAdapter`는 새 필드를 채우지 않는다(기본값 null). `AccountPnlMonitorJob`은 새 필드를 읽지 않는다 — 기록·표시는 #137의 다른 하위 이슈 범위다.

## 범위 밖

이력 테이블, 알림 표기, 청산 알림 정책, 롱 hedge 의 LONG 행 선택 규칙 변경.
