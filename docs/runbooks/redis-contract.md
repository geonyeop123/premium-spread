# Redis key, TTL, payload 계약

## 소유권과 공통 원칙

- key/TTL 함수의 코드 SSOT는 `modules/redis`의 `RedisKeyGenerator`, `RedisTtl`, time-unit enum이다.
- business Hash payload version은 `infrastructure:common`의 `schema_version=2`다.
- key segment는 lowercase canonical form을 사용한다.
- score는 epoch milliseconds다. 시간 범위는 `[from,to)`다.
- payload identity가 요청 key와 다르거나 필수 필드가 없거나 숫자/시각 파싱에 실패하면 `corrupt`다.
- corrupt time-series row가 하나라도 있으면 부분 결과를 반환하지 않는다.
- Redis 장애는 bounded `cache.read.total{cache,outcome=error}`를 남기고 가능한 조회만 DB로 fallback한다.

## Latest Hash

| Key | TTL | Payload |
|---|---:|---|
| `ticker:{exchange}:{symbol}` | 5초 | `schema_version`, `exchange`, `symbol`, `currency`, `price`, `volume`, `timestamp` |
| `fx:{base}:{quote}` | 31분 | `schema_version`, `base`, `quote`, `rate`, `timestamp`, `source` |
| `premium:{korea}:{foreign}:{symbol}` | 5초 | 아래 premium v2 payload |

`timestamp`, `observed_at`, `fx_observed_at`은 epoch milliseconds다. ticker의 빈 `volume`은 `null`로
복원한다. FX/ticker reader는 cutover 호환을 위해 unversioned payload를 읽지만 새 writer는 항상 version 2를 쓴다.

Premium v2 필수 payload:

```text
schema_version=2
symbol, rate
korea_price, foreign_price, foreign_price_krw
fx_rate, observed_at
korea_exchange, foreign_exchange
fx_source, fx_observed_at
```

Premium v2는 모든 pair/FX metadata가 있어야 한다. 일부만 있는 payload나 요청 MarketPair와 다른 payload는
miss/corrupt로 처리하고 현재 시각이나 기본 pair를 합성하지 않는다.

## Time-series ZSet

| Key | TTL | Member | Score |
|---|---:|---|---|
| `ticker:seconds:{exchange}:{symbol}` | 5분 | `{epochMs}:{price}` | sampled epoch ms |
| `ticker:minutes:{exchange}:{symbol}` | 25시간 | `high:low:open:close:avg:count` | bucket epoch ms |
| `ticker:hours:{exchange}:{symbol}` | 31일 | `high:low:open:close:avg:count` | bucket epoch ms |
| `premium:{korea}:{foreign}:{symbol}:history` | 1시간 | `rate:koreaPrice:foreignPrice` | observed epoch ms |
| `premium:{korea}:{foreign}:{symbol}:seconds` | 5분 | `rate:koreaPrice:foreignPrice:fxRate` | observed epoch ms |
| `premium:{korea}:{foreign}:{symbol}:minutes` | 25시간 | `high:low:open:close:avg:count:fxRate` | bucket epoch ms |
| `premium:{korea}:{foreign}:{symbol}:hours` | 31일 | 같은 형식 | bucket epoch ms |
| `premium:{korea}:{foreign}:{symbol}:days` | 366일 | 같은 형식 | bucket epoch ms |

Ticker day 집계는 DB에만 저장한다. flat price가 반복돼도 ticker seconds member에 epoch ms가 포함되므로
ZSet member collision로 관측값이 사라지지 않는다. Premium aggregation의 nullable `fxRate`는 마지막 빈 segment로
표현할 수 있으며 reader는 `null`로 복원한다.

## Summary Hash

```text
premium:{korea}:{foreign}:{symbol}:summary:{interval}
```

Payload는 `high`, `low`, `current`, `current_ts`, `updated_at`이다.

| interval | TTL |
|---|---:|
| `1m` | 10초 |
| `10m` | 30초 |
| `1h` | 1분 |
| `1d` | 5분 |

## Premium legacy cutover

Legacy symbol-only key는 다음 형태다.

```text
premium:{symbol}
premium:{symbol}:history
premium:seconds:{symbol}
premium:minutes:{symbol}
premium:hours:{symbol}
premium:days:{symbol}
summary:{interval}:{symbol}
```

- writer는 legacy key를 갱신하지 않는다.
- `MarketPair.default(symbol)`인 BITHUMB/BINANCE 요청만 v2 miss 뒤 legacy를 읽는다.
- non-default pair는 legacy를 절대 읽지 않는다.
- legacy hit은 남은 TTL이 없거나 5초보다 길 때만 최대 5초로 줄인다. 조회로 TTL을 연장하지 않는다.
- partial metadata, 잘못된 version/숫자/시각은 legacy라도 corrupt로 폐기한다.
- `cache.read.total`의 `legacy_hit`을 cutover 관찰에 사용하고 0이 유지된 뒤 외부 consumer 의존을 제거한다.

## Auth와 Job operational key

| Key | 값/TTL |
|---|---|
| `auth:refresh:{memberId}` | refresh hash/jti/family/generation/expiry; Refresh Token 남은 TTL |
| configured `batch.jobs.*.lock-key` | owner token lock; job별 typed lease와 renew |
| `batch:last_run:{job}` | 마지막 성공 epoch ms; 5분 |

Refresh Token 원문은 Redis에 저장하지 않는다. `auth:refresh` key는 Lua replace/rotate/revoke만으로 상태를
바꾸고 Redis `TIME`을 concurrent grace 판정에 사용한다.

## 변경 절차

1. `modules:redis` key/TTL 함수와 adapter reader/writer를 함께 변경한다.
2. 기존 key consumer가 있으면 versioned write와 bounded dual-read 기간을 명시한다.
3. 정상/legacy/corrupt/TTL/range/Redis outage 통합 테스트를 추가한다.
4. 이 문서와 cache metric/dashboard를 같은 commit에서 갱신한다.
5. 배포 뒤 `legacy_hit`, `corrupt`, `error` 증가를 확인하고 cutover 종료 전 rollback 호환성을 검증한다.
