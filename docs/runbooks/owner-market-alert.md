# owner 시장 알림 Runbook

owner 가 김프 시장 변화를 메일·Discord 로 받는 기능이다(#90). 김프 급변, 기준선 돌파, 신고가·신저가, 일일 요약 4종을 30초마다 판정한다.
batch 가 이미 수집·집계하는 김프 데이터만 쓰고 거래소 API 를 새로 호출하지 않는다. 채널·outbox 는 실계정 순손익 알림·운영 알림과 같은 것을 쓴다
([account-pnl-alert.md](account-pnl-alert.md), [owner-ops-alert.md](owner-ops-alert.md)). PnL 모니터를 켜지 않아도 동작한다.
단위 `%p` 는 김프 퍼센트 포인트다. 대상은 `batch.market` 의 기본 pair 하나(`btc-bithumb-binance`)다.

## 무엇을 알리나

| 알림 | 판정 기준 (기본값) | 경계 | 알림 종류 |
|---|---|---|---|
| 김프 급변 | 최근 10분 분봉 종가의 저점 대비 현재 김프가 **+0.5%p 이상**(급등), 고점 대비 **−0.5%p 이하**(급락) | 정확히 0.5%p 는 알림. 방향마다 독립이며 같은 방향은 30분 쿨다운(30분 정확히 경과하면 다시 알림) | `PREMIUM_SWING` |
| 기준선 돌파 | owner 가 정한 상단 이상으로 진입 / 하단 이하로 진입. 히스테리시스 0.1%p | 현재값 == 상단은 돌파. 상단 아래로 히스테리시스만큼(`상단 − 0.1`) 내려와야 벗어난 것으로 보고, 그 뒤 다시 넘으면 알림. 복귀는 알리지 않음 | `PREMIUM_THRESHOLD_CROSSED` |
| 신고가·신저가 | 현재 김프가 직전 1시간(24시간) 고점을 0.1%p 이상 넘음(저점은 반대) | 정확히 0.1%p 는 알림. 둘 다 해당하면 24시간 하나만. 쿨다운 1시간 창 30분, 24시간 창 2시간 | `PREMIUM_NEW_EXTREME` |
| 일일 요약 | 09:00(KST) 이후 첫 회차에 하루 1회. 전일 김프 시가·고가·저가·종가·평균, 환율, 실계정 순수익률 | 날짜 키로 하루 1통. 재시작해도 같은 날 다시 보내지 않음 | `DAILY_SUMMARY` |

- 급변은 분봉 **종가**를 쓴다(1초 튀는 값 하나로 알림이 나지 않게). 신고가·신저가는 분·시 **고가·저가**를 쓴다(튀는 값은 기준을 높여 알림을 줄이는 쪽).
- 기준선은 `OWNER_ALERT_MARKET_UPPER_THRESHOLD`·`_LOWER_THRESHOLD` 중 **하나 이상** 설정해야 동작한다(기본 꺼짐). 둘 다 비면 기준선만 쉬고 나머지 3종은 그대로 동작한다.
- **기준선을 설정한 직후 이미 넘어 있으면 알리지 않는다.** 그 회차는 현재 구역만 기록(기준 재설정)하고, 이후 구역이 바뀔 때부터 알린다. 기준선 값을 바꿔도 같다.
- 알림은 하나의 판정마다 켜진 채널 수만큼 outbox 행(`account_pnl_mail`)이 되고, 발송은 독립 전달 Job(60초)이 한다. 판정 지연은 신호 Job 주기 30초 + 전달 주기 60초가 더해진다.

## 근거가 부족하면 쉰다

가짜 알림을 막으려고 근거가 부족한 판정은 **알리지도, 상태를 바꾸지도 않는다**(다음 회차에 다시 본다). 재시작 직후에도 영속 집계를 읽으므로 가짜 알림이 나지 않는다.

| 원천 | 쉬는 조건 | 영향 |
|---|---|---|
| 현재 김프 | 없음, pair 다름, 나이 10초 초과, 읽기 예외 | 급변·기준선·신고가 모두 쉼 |
| 분 집계 | 읽기 예외, pair 불일치 | 급변·1시간·24시간 쉼 |
| 시간 집계 | 읽기 예외, pair 불일치 | 24시간만 쉼(1시간은 판정) |
| 창별 bucket 수 | 기대 수의 **80% 미만**(급변 10분 → 8개, 1시간 → 48개, 24시간 시간 집계 23개 → 19개) | 그 창만 쉼 |
| 요약 입력(일 집계·환율·실계정) | 읽기 **예외** | 그 회차 요약 보류, 다음 회차 재시도 |

- 요약 입력의 값이 **없음**(전일 집계 없음·환율 없음·실계정 없음)은 쉬는 조건이 아니다. 해당 줄을 "없음"으로 표시하고 보낸다.
- 80% 는 상수라 env 로 바꿀 수 없다. 수집이 몇 분 끊긴 정도는 판정을 계속하고, 24시간 중 약 4시간 40분을 넘게 비면 쉰다.

## 한계

- **분 집계 지연:** 분 집계는 다음 분 0초 cron 이 쓴다. 분이 바뀐 직후 수 초 동안 직전 분 bucket 이 없을 수 있어, 기준이 **최대 약 2분** 덜 최신일 수 있다. 진행 중인 분은 현재값이 대표한다.
- 신고가 쿨다운 동안 더 큰 신고가가 나와도 같은 창·방향은 쿨다운이 끝나야 다시 알린다.
- 24시간 근거가 부족하면(시간 집계 공백) 1시간 신고가로만 나갈 수 있다. 문장은 사실이지만 24시간 신고가이기도 했을 수 있다.
- 요약은 첫 배포·장기 정지 뒤 기동했을 때 **그날 것만** 즉시 1회 보낸다(지난 날짜는 소급하지 않는다).
- 이 기능은 batch 한 인스턴스를 전제한다. 시장 Job 이 3회·5분 연속 실패하면 운영 알림 `JOB_CONSECUTIVE_FAILURES`(신호 `job-failures.owner_market_signal`)로 알려진다([owner-ops-alert.md](owner-ops-alert.md)). 시장 알림 경로 자신으로는 알리지 않는다.

## 일일 요약

09:00(`OWNER_ALERT_MARKET_SUMMARY_HOUR`, zone `Asia/Seoul`) 이후 첫 회차에 전일(00:00~24:00)을 요약한다. 시각 하한이 01시인 이유는 일 집계 cron(00:00:10) 전에 "전일 집계 없음"으로 확정 발송되지 않게 하기 위해서다.

| 줄 | 값 없을 때 |
|---|---|
| 김프 시가·고가·저가·종가·평균 | `김프: 전일 집계 없음` |
| 환율 전일 마감 | `환율(USD/KRW) 전일 마감: 없음` |
| 환율 현재 | `환율(USD/KRW) 현재: 없음` |
| 실계정 순수익률 %와 순손익 원 | 포지션 없음이면 `포지션 없음`, PnL 모니터 꺼짐·기록 없음·최근 10분(`OWNER_ALERT_MARKET_SUMMARY_PNL_MAX_AGE`) 안에 계산 없음이면 `없음` |

- 실계정 값은 PnL 모니터가 매 회차 `account_pnl_latest`(단일 행)에 남긴 순수익률(%)과 순손익(원) **두 값만** 쓴다. 거래소 API 를 추가로 부르지 않는다. 수량·평균가·청산가·수수료·포지션 방향은 담지 않는다.
- **노출 범위:** 실계정 금액은 일일 요약에만, market 채널(또는 fallback 채널)과 메일에 나간다. 이미 PNL 채널 정시 보고에 나가는 값이다. **market 채널을 다른 사람과 공유한다면 금액이 노출되므로** 채널을 분리하거나 `OWNER_ALERT_DAILY_SUMMARY_DISCORD=none` 으로 요약의 Discord 를 끈다.
- 본문에는 webhook URL, 수신 주소, API key, 예외 메시지, 거래소 원천 가격을 담지 않는다. 로그에도 금액을 남기지 않는다.

## 라우팅

| 알림 | Discord 대상 | 알림 방식 | 메일 |
|---|---|---|---|
| 김프 급변(`PREMIUM_SWING`) | market | 소리 | **아니오** |
| 기준선 돌파(`PREMIUM_THRESHOLD_CROSSED`) | market | 소리 | **아니오** |
| 신고가·신저가(`PREMIUM_NEW_EXTREME`) | market | 무음 | **아니오** |
| 일일 요약(`DAILY_SUMMARY`) | market | 무음 | 예 |

- **Discord 가 꺼져 있으면(기본) 4종 모두 메일로 온다.** 급변·기준선·신고가도 메일이 된다. owner 가 `OWNER_ALERT_{KIND}_EMAIL=false` 를 명시했을 때만 빠진다.
  Discord 없이 메일만 쓰면 알림이 많아질 수 있다. 기본값 기준 실제 메일 상한은 다음과 같다.

  | 알림 | 메일 상한 |
  |---|---|
  | 급변 | 방향별 30분마다 |
  | 1시간 신고가·신저가 | 방향별 30분마다 |
  | 24시간 신고가·신저가 | 방향별 2시간마다 |
  | 기준선 돌파 | 쿨다운 없음. 히스테리시스 폭을 넘게 출렁이면 그때마다 반복 |
  | 일일 요약 | 하루 1통 |

  메일만 쓴다면 급변·신고가·기준선 메일을 `OWNER_ALERT_{KIND}_EMAIL=false` 로 줄이는 것을 권장한다.
- 급변 메일 끄기 예(Discord 를 쓰는 경우 Discord 알림은 그대로, 메일만 끈다):

  ```text
  OWNER_ALERT_PREMIUM_SWING_EMAIL=false
  OWNER_ALERT_PREMIUM_NEW_EXTREME_EMAIL=false
  ```

  Discord 도 꺼져 있으면 급변은 어느 채널로도 가지 않아 행이 만들어지지 않는다(기동 INFO `owner-alert routes:` 에 `premium-swing=off`).
- Discord 가 켜져 있으면 급변·기준선·신고가는 Discord 로만 오고, 일일 요약만 메일도 함께 온다.
- **market webhook(`OWNER_ALERT_DISCORD_WEBHOOK_MARKET`)을 설정하지 않으면** 기존 fallback 으로 `PNL → MARKET → OPS` 순서의 첫 설정 대상으로 간다. 예: `https://discord.example.test/api/webhooks/market-example`.
- override env 는 `OWNER_ALERT_{KIND}_EMAIL`·`_DISCORD`·`_PUSH` 12개(`{KIND}` = `PREMIUM_SWING`, `PREMIUM_THRESHOLD_CROSSED`, `PREMIUM_NEW_EXTREME`, `DAILY_SUMMARY`)다.
  값과 의미는 [account-pnl-alert.md](account-pnl-alert.md#종류별-override-env)와 같다. 두 채널이 모두 꺼지도록 override 한 알림은 행을 만들지 않는다.

## 환경변수

| env | property | 기본 | 의미 |
|---|---|---|---|
| `OWNER_ALERT_MARKET_ENABLED` | `owner-alert.market.enabled` | `true` | 시장 알림 Job on/off. 빈 값 = true, 그 밖은 trim 후 `equalsIgnoreCase("true")` |
| `OWNER_ALERT_MARKET_SWING_WINDOW` | `owner-alert.market.swing-window` | `10m` | 급변 비교 창. 1m~60m, 정확히 분 단위 |
| `OWNER_ALERT_MARKET_SWING_THRESHOLD` | `owner-alert.market.swing-threshold` | `0.5` | 급변 폭(%p, 이상). 양수 |
| `OWNER_ALERT_MARKET_SWING_COOLDOWN` | `owner-alert.market.swing-cooldown` | `30m` | 같은 방향 급변 재알림 간격(이상) |
| `OWNER_ALERT_MARKET_UPPER_THRESHOLD` | `owner-alert.market.upper-threshold` | 빈 값(꺼짐) | 상단 기준선(%). **소수 넷째 자리까지**(`3.1234` 가능, `3.12345` 는 기동 실패), 절댓값 100만 미만 |
| `OWNER_ALERT_MARKET_LOWER_THRESHOLD` | `owner-alert.market.lower-threshold` | 빈 값(꺼짐) | 하단 기준선(%). 같은 제한. 상단·하단 모두 있으면 `상단 − 하단 > 히스테리시스 × 2` |
| `OWNER_ALERT_MARKET_THRESHOLD_HYSTERESIS` | `owner-alert.market.threshold-hysteresis` | `0.1` | 기준선 히스테리시스(%p). 0 이상, 소수 넷째 자리까지 |
| `OWNER_ALERT_MARKET_EXTREME_MIN_DELTA` | `owner-alert.market.extreme-min-delta` | `0.1` | 신고가·신저가 최소 폭(%p, 이상). 양수 |
| `OWNER_ALERT_MARKET_EXTREME_HOUR_COOLDOWN` | `owner-alert.market.extreme-hour-cooldown` | `30m` | 1시간 창 재알림 간격 |
| `OWNER_ALERT_MARKET_EXTREME_DAY_COOLDOWN` | `owner-alert.market.extreme-day-cooldown` | `2h` | 24시간 창 재알림 간격 |
| `OWNER_ALERT_MARKET_SUMMARY_HOUR` | `owner-alert.market.summary-hour` | `9` | 일일 요약 시각(KST 시). **1~23** (0 은 기동 실패) |
| `OWNER_ALERT_MARKET_SUMMARY_PNL_MAX_AGE` | `owner-alert.market.summary-pnl-max-age` | `10m` | 요약에 쓰는 실계정 값의 최대 나이(정확히 같으면 유효) |
| `OWNER_MARKET_SIGNAL_FIXED_RATE` | `batch.scheduling.owner-market-signal.fixed-rate` | `30s` | 시장 신호 Job 주기 |
| `OWNER_MARKET_SIGNAL_LOCK_KEY`·`_LEASE`·`_TIMEOUT` | `batch.jobs.owner-market-signal.*` | `lock:owner-market:signal` · `20s` · `10s` | 시장 신호 Job lock (lease > timeout) |
| `OWNER_ALERT_{KIND}_EMAIL`·`_DISCORD`·`_PUSH` | `owner-alert.routes.{key}.*` | 빈 값 | `{KIND}` ∈ `PREMIUM_SWING`, `PREMIUM_THRESHOLD_CROSSED`, `PREMIUM_NEW_EXTREME`, `DAILY_SUMMARY` (12개) |

채널 env(`OWNER_ALERT_EMAIL_ENABLED`·`OWNER_ALERT_DISCORD_ENABLED`·`OWNER_ALERT_DISCORD_WEBHOOK_*`·`ACCOUNT_PNL_ALERT_RECIPIENT`·SMTP)는
[account-pnl-alert.md](account-pnl-alert.md)와 같다. `owner-alert.market.*` 잘못된 값(`0s`, 음수, 정밀도 초과, 요약 시각 0 등)은 기동 실패다. 실제 webhook URL·수신 주소는 이 문서에 쓰지 않는다.
기준선 설정 예(가짜 값): `OWNER_ALERT_MARKET_UPPER_THRESHOLD=3.5`, `OWNER_ALERT_MARKET_LOWER_THRESHOLD=1.2`.

**기준선 히스테리시스 주의:** `OWNER_ALERT_MARKET_THRESHOLD_HYSTERESIS` 를 0 이나 아주 작게 두면 김프가 기준선 근처에서 흔들릴 때 진입·이탈이 반복돼
**소리 알림이 회차(30초)마다 날 수 있다.** 기본 0.1%p 이상을 권장하고, 변동이 큰 구간에서는 더 크게 둔다.

## 기동 판정과 로그

시장 신호 Job·Scheduler 는 **batch scheduling 켜짐 ∧ 채널 준비(ready) ∧ `OWNER_ALERT_MARKET_ENABLED` 가 꺼지지 않음**일 때 뜬다. 채널 준비 규칙은 [owner-ops-alert.md](owner-ops-alert.md#기동-판정과-로그)와 같고 PnL 모니터와는 무관하다.

- **채널이 준비되지 않으면 시장 알림도 같은 조건으로 시작되지 않는다.** 이때 로그는 기존 `owner-alert channel not ready; owner alert delivery and ops alerts not started: …`(WARN) 또는
  `owner-alert email channel has no owner recipient; owner alert delivery and ops alerts not started: …`(INFO) 한 줄이며 시장 알림을 따로 언급하지 않는다. 채널이 아무것도 켜지지 않은 로컬 기본은 로그가 없다.
- ready 인데 `OWNER_ALERT_MARKET_ENABLED=false`: INFO `owner-alert market alerts disabled by OWNER_ALERT_MARKET_ENABLED`.
- 종류별 최종 경로는 기동 INFO `owner-alert routes:` 한 줄에 나온다. 시장 알림 4종 기본값은 `premium-swing=discord(MARKET,SOUND), premium-threshold-crossed=discord(MARKET,SOUND), premium-new-extreme=discord(MARKET,SILENT), daily-summary=email+discord(MARKET,SILENT)` 이다(Discord 가 꺼져 있으면 `email`).
- 기준선 env 가 모두 비어 있어도 기동은 정상이다(기준선만 쉼). 로그에는 env 이름과 boolean 만 찍히고 값은 찍히지 않는다.

## 알림 메시지 예

고정 시각 기준 가짜 값이다. 메일 subject 는 `[시장] ` + 핵심이고, Discord 제목은 이모지 + 핵심이다(급등 `📈`, 급락 `📉`, 상단 `🔺`, 하단 `🔻`, 신고가 `⬆️`, 신저가 `⬇️`, 요약 `🗓️`). 마지막 줄은 항상 `조치 안내: docs/runbooks/owner-market-alert.md` 이다.

급변 (subject `[시장] 김프 급등 BTC BITHUMB/BINANCE +0.52%p (10분)`):

```text
현재 김프: +2.35% (2026-10-28 09:00:00 (2026-10-28T00:00:00Z))
최근 10분 분봉 종가 저점: +1.83%
변화: +0.52%p
기준: 10분 안에서 ±0.5%p 이상 · 같은 방향은 30분 동안 다시 알리지 않습니다.
알림 시각: 2026-10-28 09:00:10 (2026-10-28T00:00:10Z)
```

일일 요약 (실계정 값은 가짜):

```text
대상일: 2026-10-27 00:00 ~ 24:00 (Asia/Seoul)
김프 시가 +1.90% · 고가 +2.40% · 저가 +1.70% · 종가 +2.10% · 평균 +2.00%
환율(USD/KRW) 전일 마감: 1,385.20
환율(USD/KRW) 현재: 1,386.00 (2026-10-28 09:00:00 (2026-10-28T00:00:00Z))
실계정 순수익률: +1.20% (12,000원) · 계산 2026-10-28 08:59:30 (2026-10-27T23:59:30Z)
요약 시각: 2026-10-28 09:00:10 (2026-10-28T00:00:10Z)
```

## 상태 확인과 정리

신호 상태 한 행이 신호 하나다(급변 방향 2·기준선 1·신고가 4·요약 1, 대상 pair 마다 `market.<pk>.…` key). 현재 상태를 본다.

```sql
SELECT signal_key, alert_kind, last_notified_at, premium_zone, summary_date FROM owner_market_alert_state;
```

`batch.market` 을 바꿔 더 이상 관측하지 않는 pair 의 행은 남는다. 다른 알림 상태에는 영향이 없다. 해당 pair 키(`<pk>`, 예 `btc-bithumb-binance`)를 확인하고 아래로 정리한다.

```sql
DELETE FROM owner_market_alert_state WHERE signal_key LIKE 'market.<pk>.%';
```

기준선 구역·쿨다운이 사라지므로 같은 pair 를 다시 쓰면 기준선은 재설정(알림 없음)부터 시작한다. 요약의 `summary_date` 도 지워지므로 오늘 요약 시각 이후라면 그날 요약이 한 번 더 나갈 수 있다.

## 배포

- **V22(`owner_market_alert_state`·`account_pnl_latest` 신규 테이블 둘)는 additive 다.** 순수 `CREATE TABLE` 이라 구 binary 와 호환되고 **롤백 SQL 은 없다.** 스키마는 되돌리지 않는다.
- **API 를 먼저 배포한다.** Flyway 는 API 만 실행한다. 신 batch 가 V22 적용 전에 뜨면 첫 회차 상태 조회와 PnL 최신값 저장이 테이블 없음으로 실패한다.
- 신 → 구 batch 로 되돌리면 시장 알림만 멈춘다. 상태 행은 마지막 값으로 남고 다시 올리면 이어서 판정한다.
- Redis key·TTL·payload, 공개 endpoint 는 바뀌지 않는다. 새 lock key `lock:owner-market:signal` 은 기존 `lock:*` 경로의 Job 설정 값이다([redis-contract.md](redis-contract.md) 무변경).

## Discord 수신 체크리스트 (owner-market-alerts AC17)

자기 Discord 서버에 market 채널 webhook 을 만들어 로컬 batch 를 띄운 뒤 확인하고, 결과를 **PR 코멘트**로 기록한다. (c)(d) 는 관찰 기회가 없으면 "미관찰"로 기록한다.

- [ ] (a) 기동 INFO `owner-alert routes:` 에 시장 알림 4종이 기본 경로로 보인다(`premium-swing=discord(MARKET,SOUND), premium-threshold-crossed=discord(MARKET,SOUND), premium-new-extreme=discord(MARKET,SILENT), daily-summary=email+discord(MARKET,SILENT)`).
- [ ] (b) 다음 09:00 KST 이후 market 채널에 **무음** 일일 요약이 1통, 메일이 1통 온다.
- [ ] (c) `OWNER_ALERT_MARKET_UPPER_THRESHOLD` 를 현재 김프 바로 위로 두고 기다리면 돌파 시 **소리** 알림이 온다. 설정 직후 이미 넘어 있으면 알리지 않는 것이 정상이다.
- [ ] (d) 급변·신고가는 관찰되면 기록한다(급변 **소리**, 신고가 **무음**). 관찰 기회가 없으면 "미관찰"로 기록한다.
