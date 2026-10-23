# owner 알림 채널 기반 — Discord·메일 라우팅 (issue #88) 설계

- slug: `owner-alert-channel` · base `origin/dev` `1bce3dd` · branch `feat/issue-88-owner-alert-channel`
- 입력: `docs/work/owner-alert-channel/_brief.md` (③ 합의), `.ai/rules/{architecture,batch,testing,naming}.md`
- 상태: APPROVED — ⑥ R2 APPROVE · ⑦ owner 승인 2026-10-23, DoD 동결 b2d0878 — 변경 내역 §14

## 0. 요약

owner 알림(실계정 순손익 알림 4종 — 수익·정시 보고·모니터링 중단·복구)을 **메일과 Discord 두 채널**로 보낸다.
각 채널은 env 로 켜고 끈다. 알림 종류별 기본 경로(메일 여부, Discord 채널 `pnl`/`market`/`ops`, 푸시 `무음`/`소리`/`@here`)는
Domain enum `OwnerAlertKind` **한 곳**에 두고 env 로 덮어쓴다. 한 알림은 기존 outbox(`account_pnl_mail`)에 **켜진 채널 수만큼 행**으로
들어가고, 기존 claim·fencing·재시도 루프가 행의 채널에 맞는 sender 로 보낸다.

핵심 결정 (근거는 각 절):

| # | 결정 | 절 |
|---|---|---|
| K1 | outbox 는 **V19 로 `account_pnl_mail` 을 확장**한다(`channel` + Discord 메타 3컬럼, UNIQUE 를 `(event_key, channel)` 로). 새 테이블·rename 은 하지 않는다 | §4 |
| K2 | Domain 행 타입 `AccountPnlMail` 은 이름을 유지하고 `channel`·`discordRoute`·`discordColor` 를 **기본값 있는 프로퍼티**로 추가한다 — 동결 테스트가 무수정 컴파일된다 | §4.3 |
| K3 | 설정 prefix 는 `owner-alert`, env 는 `OWNER_ALERT_*`. 수신 주소·SMTP·정시 보고 on/off 는 기존 env 그대로. 수신 주소 **필수 판정과 읽기**는 `OwnerAlertChannelRequirements` 한 곳이 소유한다 | §7 |
| K4 | #88 의 전달은 **기존 `AccountPnlMonitorJob` 회차 안**에서 한다(새 Job 없음). 전달 루프는 application `OwnerAlertDispatcher` 로 추출해 #90/#91 의 독립 전달 Job 이 그대로 재사용한다 | §5, §9 |
| K5 | Discord 꺼짐이면 4종 모두 메일(**기존 동작 그대로**). Discord 켜짐이면 brief §3 표대로 메일은 중요 알림(수익·중단)만 | §3.3 · 사용자 확정 D1 |
| K6 | Discord 실패: 429(Retry-After ≤ 2s 면 adapter 안에서 1회 재시도)·5xx·네트워크 → 재시도(다음 회차, 최대 5회), 그 밖 4xx → 즉시 `FAILED` | §5.3 |
| K7 | 배포는 **구 batch 중지 → 신 batch 기동**(겹치지 않음). V19 는 구 binary 의 INSERT 와만 호환되고 구 binary 의 claim 은 채널을 모른다. 롤백은 DISCORD 미발송 행을 먼저 `FAILED` 로 닫는다 | §4.5 · 오케스트레이터 판정 I-1 |

## 1. 범위

### 1.1 포함

- Domain 라우팅 모델: `OwnerAlertKind`(4종) · 채널·Discord 대상·푸시 enum · 기본 경로 · override · 채널 가용성 해석 (§3)
- outbox 채널 확장: V19, JDBC store 채널 컬럼·`markFailed`, batch fixture 스키마 (§4)
- Discord 렌더링 4종(embed 제목·본문·색), Discord 제한(제목 255·본문 4096·content 2000·embed 합 6000) 안전 절단 (§6)
- Discord webhook adapter (`infrastructure:batch`), webhook 3개 + fallback, 재시도 분류, URL 비노출, 취소 전파 (§5.3)
- 전달 루프 추출(`OwnerAlertDispatcher`)과 채널별 라우팅 (§5)
- 설정·기동 조건: `owner-alert.*` 바인딩(`String?` 직접 해석·마스킹), 채널 판정·수신 주소 필수 판정 한 곳, 누락 WARN, app·infra 조건 일치 (§7)
- 기존 4종을 라우팅 위로 이전 — **메일 eventKey·제목·본문 바이트 불변** (§6.1)
- 배포·롤백 절차와 롤백 SQL (§4.5)
- runbook 갱신 (§13)

### 1.2 제외 (scope creep 차단선)

- 신규 알림 10종(#89 계정 A1~A3, #90 시장 M1~M4, #91 운영 O1~O3) **구현**. 이 문서는 그것들이 끼워질 자리(§9)와 목표 라우팅 표(§3.2)만 정한다
- 시장·운영 알림용 독립 전달 Job·Scheduler·JobId (#90 이 추가 — §9)
- 회원용 김프 구독 알림(`notification_delivery`, `NotificationDeliveryJob`) — 무변경
- 테이블·Domain 타입 rename (§4.4 이름 부채), 수신 주소 property key rename (§7.2)
- Discord 봇 토큰·OAuth·메시지 수정/삭제·스레드, Slack 경로 변경
- 순손익 공식·수익/중단 정책·에피소드·정시 보고 생성 규칙 변경, 표시 zone 배선 변경(§10)
- 메트릭 추가 (기존 `batch.job.run` 으로 충분)
- 직전 단위 산출물(`docs/work/live-account-pnl-*`, `account-pnl-round-log`, `bithumb-orders-query-hash`) 수정

## 2. 현재 구조와 동결 테스트가 만드는 제약

현재(`1bce3dd`) 흐름: `AccountPnlMonitorScheduler` → `AccountPnlMonitorJob.run()` → `JobExecutor`(lock `lock:account-pnl:monitor`, lease 50s,
timeout 40s) → 관측·정책 → `AccountPnlAlertStore.save(version, state, mails)`(상태+outbox 한 트랜잭션) → 같은 회차에서 `claim`(1건씩, 최대
10건) → `AccountPnlMailSender.send` → `markSent`/`markAttemptFailed`. outbox `account_pnl_mail` 는 `UNIQUE(event_key)`.

설계를 묶는 기존 테스트 계약 (수정 금지 또는 추가만):

| 테스트 | 강제하는 것 | 이 설계의 대응 |
|---|---|---|
| `AccountPnlMonitorJobTest` (동결) | Job 생성자 앞 8개 위치 인자 타입, `run()` 안에서 claim·발송, `FakeStore : AccountPnlAlertStore` 가 6개 메서드를 `override`, `AccountPnlMail(k, s, b)`·`ClaimedAccountPnlMail(id, mail, token, attempts)` 생성, claim 횟수·owner 접두사 `account-pnl-monitor:`, 관측 중 interrupt 예외 재전파 | 생성자 인자 **뒤에만** 기본값 있는 인자 추가, 전달은 회차 안 유지, store interface 에 **추상 메서드 추가 금지**(기본 구현 메서드만), 행 타입에 기본값 프로퍼티만 추가, 관측 경로 무변경 |
| `AccountPnlMonitorJobHourlyReportTest`·`...ObservationLogTest` (직전 단위) | 같은 생성자·`hourlyReportEnabled` | 같음 |
| `AccountPnlMonitorWiringTest` (확장만) | 기존 runner 구성(5개 configuration)으로 Job·`AccountPnlEmailSenderAdapter` 가 뜨고/안 뜸, `EmailSender` bean 이 없으면 adapter 0개, 누락 WARN 문구(env 이름 `ACCOUNT_PNL_ALERT_RECIPIENT` 포함), `email disabled` WARN 문구, property key `account-pnl-monitor.recipient` | 새 bean 은 **`AccountPnlMonitorAdapterConfiguration` 안**에 둔다(K4 근거). 기존 WARN 문구·수신 주소 property key·env 유지 |
| `JdbcAccountPnlAlertStoreIntegrationTest` (확장만) | `claim.mail == AccountPnlMail(k, s, b)` 동등성 | 메일 행은 `channel=EMAIL`, Discord 필드 null 로 읽혀 동등 |
| `AccountPnlMailContentTest` golden | 수익 메일 바이트 불변 | 정시 보고·중단·복구 golden 을 **먼저** 추가(특성화)한 뒤 서식 함수를 추출 |
| `AccountPnlFixtureSchemaIntegrationTest` | `account_pnl_mail` 컬럼 목록 = V18 | 클래스 KDoc 이 "새 migration 이 바꾸면 함께 고친다"고 명시 — **허용 예외**(기대 목록 끝에 4컬럼 추가, dod R7) |

## 3. 채널 모델과 라우팅 (Domain `io.premiumspread.domain.owneralert`)

### 3.1 타입

- `OwnerAlertChannel { EMAIL, DISCORD }` — outbox `channel` 값
- `DiscordTarget { PNL, MARKET, OPS }` — 어느 webhook(채널)로 보내는가. outbox `discord_target` 값
- `DiscordPush { SILENT, SOUND, HERE }` — 무음(`flags: 4096`) / 소리 / 소리 + `@here`. outbox `discord_push` 값
- `DiscordRoute(target, push)`, `OwnerAlertRoute(email: Boolean, discord: DiscordRoute?)`
- `OwnerAlertKind` — 종류별 **기본 경로를 생성자 인자로** 갖는 enum. 라우팅 표의 유일한 코드 위치다.
  `key` 는 `name.lowercase().replace('_', '-')` (`HOURLY_REPORT` → `hourly-report`). 코드 어디에도 `OwnerAlertKind` 전체에 대한
  `when` 을 두지 않는다 — #89~#91 이 enum 값을 더해도 컴파일이 깨지는 곳이 없어야 한다.
- `OwnerAlertRouteOverride(email: Boolean?, discordTarget: DiscordTarget?, discordDisabled: Boolean, push: DiscordPush?)`
- `OwnerAlertChannels(email: Boolean, discord: Boolean)` — 이번 기동에서 **실제로 쓸 수 있는** 채널(sender bean 존재)
- `OwnerAlertRouting(channels, overrides)` — `resolve(kind): OwnerAlertRoute`. `EMAIL_ONLY` 상수 = `channels(email=true, discord=false)`, override 없음

### 3.2 기본 라우팅 표 (전문)

#88 이 구현하는 4종 — `OwnerAlertKind` 생성자 인자 그대로:

| enum | key | Discord 대상 | 푸시 | 메일 |
|---|---|---|---|---|
| `PROFIT` | `profit` | `PNL` | `HERE` | true |
| `HOURLY_REPORT` | `hourly-report` | `PNL` | `SILENT` | false |
| `MONITORING_OUTAGE` | `monitoring-outage` | `OPS` | `SOUND` | true |
| `MONITORING_RECOVERED` | `monitoring-recovered` | `OPS` | `SOUND` | false |

후속 이슈가 enum 값으로 추가할 목표 표 (brief §3, 이 단위는 코드에 넣지 않는다 — 이름은 권고):

| 이슈 | enum (권고) | 알림 | Discord 대상 | 푸시 | 메일 |
|---|---|---|---|---|---|
| #89 | `POSITION_CHANGED` | 포지션 변경 감지(불일치 포함) | `PNL` | `HERE` | true |
| #89 | `NET_PERCENT_SWING` | 순수익률 급변 | `PNL` | `SOUND` | false |
| #89 | `UNRECOVERED_REMINDER` | 미반영 항목 리마인더 | `PNL` | `SILENT` | false |
| #90 | `PREMIUM_THRESHOLD_CROSSED` | 김프 기준선 돌파 | `MARKET` | `SOUND` | false |
| #90 | `PREMIUM_SWING` | 김프 급변 | `MARKET` | `SOUND` | false |
| #90 | `PREMIUM_NEW_EXTREME` | 1h/1d 신고가·신저가 | `MARKET` | `SILENT` | false |
| #90 | `DAILY_SUMMARY` | 일일 요약 09:00 | `MARKET` | `SILENT` | true |
| #91 | `FX_INGESTION_DELAYED` | 환율 수집 지연 | `OPS` | `SOUND` | false |
| #91 | `MARKET_STREAM_DOWN` | 시세 수집 끊김 지속 | `OPS` | `SOUND` | false |
| #91 | `JOB_CONSECUTIVE_FAILURES` | Job 연속 실패 | `OPS` | `SOUND` | true |

### 3.3 해석 규칙 `OwnerAlertRouting.resolve(kind)` (전문)

```
d = kind.defaultRoute, o = overrides[kind] ?: OwnerAlertRouteOverride()

configuredDiscord = if (o.discordDisabled) null
                    else d.discord?.let { DiscordRoute(o.discordTarget ?: it.target, o.push ?: it.push) }
                         ?: o.discordTarget?.let { DiscordRoute(it, o.push ?: DiscordPush.SOUND) }
configuredEmail   = o.email ?: d.email

discord = if (channels.discord) configuredDiscord else null
email   = channels.email && (if (channels.discord) configuredEmail else (o.email ?: true))
→ OwnerAlertRoute(email, discord)
```

- **Discord 가 꺼져 있으면 메일이 유일한 채널이므로 모든 종류가 메일로 간다**(기존 동작 그대로 — 정시 보고·복구 메일도 계속 온다).
  owner 가 그 종류의 `email=false` 를 **명시**했을 때만 빠진다. **사용자 확정 D1**.
- Discord 가 켜지면 표의 `메일` 칸이 적용된다 → 메일은 수익·중단만, **정시 보고·복구 메일은 더 이상 오지 않는다**(Discord 로만). runbook 에 굵게 적는다.
- 결과가 `OwnerAlertRoute(false, null)` 이면 그 알림은 행을 만들지 않는다(owner 가 명시적으로 끈 경우뿐).

### 3.4 행 확장 `OwnerAlertRows.expand` (Domain `accountpnl`)

`expand(mail, route, discord: () -> DiscordMessage)` → 0~2행, **메일 행 먼저**:
- `route.email` 이면 입력 `mail` **그 자체**(바이트 불변)
- `route.discord != null` 이면 `AccountPnlMail(mail.eventKey, msg.title, msg.description, DISCORD, route.discord, msg.color)` — `discord()` 는
  이때만 호출(렌더링 실패가 메일 경로를 막지 않게)

의존 방향은 `accountpnl → owneralert` 한 방향이다(행 타입이 `accountpnl` 에 있으므로 확장 함수도 `accountpnl` 에 둔다).

## 4. Outbox

### 4.1 대안 비교

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **A (채택)** V19 `ALTER account_pnl_mail` | `channel`(기본 `'EMAIL'`)·`discord_target`·`discord_push`·`discord_color` 추가, UNIQUE `(event_key)` → `(event_key, channel)`, CHECK | 기존 claim·fencing·stale 복구·재시도 코드와 integration 테스트 재사용. 이미 쌓인 행이 그대로 메일 행이 된다. **INSERT 호환만 보장한다**: API 가 V19 를 먼저 적용해도 구 batch 의 `INSERT (event_key, subject, body, ...)` 는 기본값 `EMAIL` 로 들어가고 `ON DUPLICATE KEY` 가 복합 UNIQUE 로 계속 중복 제거된다. **구 binary 의 claim 은 채널을 모른다**(`JdbcAccountPnlAlertStore.kt:72-80` 의 SELECT 에 channel 조건이 없고 `toPendingMail` :251-259 가 행을 메일로 만든다) — DISCORD 행이 있는 DB 에서 구 batch 가 돌면 embed 제목·설명이 메일로 나간다. 그래서 batch 는 겹치지 않게 교체하고 롤백은 §4.5 SQL 을 먼저 실행한다 | 테이블·타입 이름이 "mail"·"account_pnl" 인데 Discord·(후속) 시장/운영 행을 담는다 — 이름 부채(§4.4). fixture 스키마 테스트 1개 허용 예외. 구·신 batch 동시 실행 불가(§4.5) |
| B 새 테이블 `owner_alert_outbox` | V19 `CREATE TABLE`, 새 store | 이름이 정직하다. 구 binary 가 Discord 행을 볼 수 없다 | V18 테이블의 PENDING 행을 옮기거나 두 테이블을 모두 drain 해야 한다. 구 batch 가 배포 사이에 옛 테이블에 넣는 행은 `INSERT…SELECT` 이관이 놓친다. claim·fencing·stale·재시도 SQL 과 integration 테스트 전부 복제. 동결 `AccountPnlMonitorJobTest` 가 `AccountPnlAlertStore` 를 쓰므로 store port 는 결국 같은 이름으로 남는다 |
| C `RENAME TABLE` + ALTER | 이름 정직 + 데이터 보존 | | 배포 사이 구 batch 의 모든 SQL 이 실패(`Table doesn't exist`) → 회차 Failure·Slack. API 만 Flyway 를 돌리는 구조에서 rolling 불가 |

`event_key` 정책: 기존 메일 eventKey(`account-pnl-{kind}-{epoch ms}`)를 **그대로** 쓰고 Discord 행도 같은 eventKey 를 쓴다. 채널은 별도 컬럼이 식별한다 —
eventKey 에 채널 접미사를 붙이는 대안(UNIQUE 무변경)은 같은 정보를 두 곳에 두므로 버린다. `DROP INDEX` 는 `verifyMigrations` 금지 목록(TRUNCATE·
DROP TABLE·WHERE 없는 DELETE)에 없고 데이터를 지우지 않는다.

### 4.2 V19 DDL (전문)

위치 `infrastructure/common/src/main/resources/db/migration/V19__add_owner_alert_channel.sql` (번호 = `ls … | sort -V | tail -1` 의 V18 + 1, Task 0 확인).

```sql
-- owner 알림 채널(메일·Discord) 확장. 기존 행은 모두 메일이므로 channel 기본값 'EMAIL' 로 채워진다.
-- 한 알림은 켜진 채널 수만큼 행이 된다 — 같은 event_key 가 채널마다 한 행씩 있도록 UNIQUE 를 (event_key, channel) 로 넓힌다.
-- Discord webhook URL 은 저장하지 않는다(adapter 설정이 소유). discord_* 는 DISCORD 행에만 있다.
ALTER TABLE account_pnl_mail
    ADD COLUMN channel        VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN discord_target VARCHAR(16) NULL,
    ADD COLUMN discord_push   VARCHAR(16) NULL,
    ADD COLUMN discord_color  INT         NULL,
    DROP INDEX uk_account_pnl_mail_event_key,
    ADD CONSTRAINT uk_account_pnl_mail_event_key_channel UNIQUE (event_key, channel),
    ADD CONSTRAINT ck_account_pnl_mail_channel CHECK (
        (channel = 'EMAIL' AND discord_target IS NULL AND discord_push IS NULL AND discord_color IS NULL)
        OR (channel = 'DISCORD' AND discord_target IS NOT NULL AND discord_push IS NOT NULL AND discord_color IS NOT NULL)
    );
```

- 한 문장으로 둔다 — MySQL DDL 은 트랜잭션이 아니므로 두 문장으로 나누면 두 번째 실패 시 반쯤 적용된 V19 가 남는다. `[ASSUMPTION]` A2.
- 새 컬럼은 **끝에** 붙는다(ORDINAL_POSITION 기존 11개 유지).
- `idx_account_pnl_mail_status_id (status, id)` 는 그대로 — claim 조회 무변경.
- batch 는 Flyway 를 쓰지 않으므로 `apps/batch/src/integrationTest/resources/batch-schema.sql` 의 `account_pnl_mail` 을 V19 최종 형태로 맞춘다.

### 4.3 행 모델

`AccountPnlMail(eventKey, subject, body, channel = EMAIL, discordRoute: DiscordRoute? = null, discordColor: Int? = null)` —
`init` 에서 `channel == DISCORD` ⇔ `discordRoute != null` ⇔ `discordColor != null`. DISCORD 행의 `subject` = embed 제목, `body` = embed 설명.

| 컬럼 | EMAIL 행 | DISCORD 행 |
|---|---|---|
| `event_key` | `account-pnl-{kind}-{epoch ms}` | 같은 알림의 메일 행과 같은 값 |
| `channel` | `EMAIL` | `DISCORD` |
| `subject` | 메일 제목 (≤255) | embed 제목 (≤255, §6.3) |
| `body` | 메일 본문 (≤65,535 bytes) | embed 설명 (≤4,096 UTF-16, 최대 ~12KB) |
| `discord_target` / `discord_push` / `discord_color` | NULL | enum 이름 / enum 이름 / `0..0xFFFFFF` |

Discord 행은 **논리 대상(PNL/MARKET/OPS)만** 저장하고 URL 은 발송 시점에 설정에서 해석한다 — env 를 바꾸고 재시작하면 남은 행도 새
webhook 으로 간다. 메일 수신 주소를 저장하지 않는 기존 원칙과 같다.

### 4.4 이름 부채 (의도된 것)

테이블 `account_pnl_mail`, Domain `AccountPnlMail`·`ClaimedAccountPnlMail`·`AccountPnlAlertStore` 는 이제 owner 알림 outbox 전체를 뜻한다.
rename 은 (1) 동결 테스트가 이름을 import 하고 (2) 테이블 rename 은 구 batch 의 모든 SQL 을 깨뜨려 이 단위에서 하지 않는다. 각 타입 KDoc 과
runbook 에 "owner 알림 공용 outbox" 임을 적는다. rename 은 구 binary 가 없어진 뒤 별도 refactor 이슈로 한다(#89~#91 의 전제 아님).

### 4.5 배포·롤백 (오케스트레이터 판정 I-1)

구 binary(`1bce3dd` 이하)의 claim SQL 은 `channel` 조건이 없어 DISCORD 행도 집어 메일로 보낸다. 따라서 **구·신 batch 가 같은 DB 에서 동시에 돌면 안 된다**
(분산 lock `lock:account-pnl:monitor` 는 회차를 직렬화할 뿐 구 binary 가 DISCORD 행을 claim 하는 것을 막지 못한다).

배포 순서 (runbook 에 그대로):
1. API 배포 — Flyway 가 V19 적용. 이 시점에는 DISCORD 행이 없으므로 구 batch 는 그대로 돌아도 안전하다(INSERT 호환, §4.1).
2. **구 batch 중지** — 프로세스가 끝났는지 확인한다.
3. 신 batch 기동.

롤백 (신 batch → 구 batch) — **신 batch 를 중지한 뒤, 구 batch 를 기동하기 전에** 아래 SQL 을 실행한다(전문):

```sql
UPDATE account_pnl_mail
SET status = 'FAILED',
    claim_token = NULL,
    locked_by = NULL,
    locked_at = NULL
WHERE channel = 'DISCORD'
  AND status IN ('PENDING', 'PROCESSING');
```

- 효과: 미발송 Discord 행이 닫히고(그 알림의 Discord 사본은 유실, 메일 행은 영향 없음), 구 batch 의 channel 무관 claim 이 집을 수 있는 행은 EMAIL 뿐이다.
  `SENT`/`FAILED` 행은 claim 대상이 아니므로 그대로 둔다. `attempt_count` 는 바꾸지 않는다(운영자 조치이지 발송 시도가 아니다).
- 스키마는 되돌리지 않는다(V19 는 append-only, 구 binary 의 INSERT 는 V19 에서 동작). 이 SQL 은 WHERE 가 있는 UPDATE 이므로 migration 금지 목록과 무관하다
  (migration 이 아니라 runbook 운영 절차다).
- 이 SQL 이 구 claim 에 대해 안전하다는 것은 V19 migration integration test 가 고정한다(dod AC4-f).

## 5. 전달

### 5.1 흐름 (Scheduler → Job 단일 진입 유지)

```
AccountPnlMonitorScheduler ──run()──▶ AccountPnlMonitorJob (JobExecutor: lock:account-pnl:monitor, 50s/40s)
   관측 → PnlAlertPolicy / MonitoringOutagePolicy → OwnerAlert(kind, mail, discord 렌더러)
   → routing.resolve(kind) → OwnerAlertRows.expand → store.save(version, state, rows)   [한 트랜잭션, DB-first]
   → OwnerAlertDispatcher.dispatch()   [claim(1) commit → 채널별 send → markSent / markFailed / (회차 끝) markAttemptFailed]
        EMAIL   → AccountPnlMailSender      ← infrastructure:batch AccountPnlEmailSenderAdapter (기존)
        DISCORD → OwnerAlertDiscordSender   ← infrastructure:batch DiscordWebhookSenderAdapter (신규)
```

새 Job·Scheduler·JobId 없음. 전달은 지금처럼 같은 회차 안에서 즉시 일어나고, 실패 행은 다음 회차(60초)에 재시도된다.

### 5.2 `OwnerAlertDispatcher` (application, `apps/batch/.../application/notification/owneralert/`)

`AccountPnlMonitorJob.deliver()`·`deliverNext`·`markSent`·`send`·`releaseFailed` 를 **동작 그대로** 옮기고 채널 분기만 더한다. 회차 상한 10건,
claim concurrency 1, 실패 건은 회차 끝에 되돌림(같은 회차 재claim 방지), 매 claim 전 interrupt 확인(취소되면 남은 행 PENDING 으로 `Success`) —
동결 `AccountPnlMonitorJobTest` 가 지킨다.

상태 전이표 (행 단위, 전문):

| claim 한 행 | sender 결과 | 전이 | 시점 |
|---|---|---|---|
| EMAIL | 성공 | `markSent` → `SENT` | 즉시 |
| EMAIL | `OwnerAlertSendException(permanent = true)` | `markFailed` → `FAILED`(attempt+1) | 즉시 |
| EMAIL | 그 밖 예외 | `markAttemptFailed` → `PENDING` 또는 5회째 `FAILED` | 회차 끝 |
| DISCORD | Discord sender 없음(채널 꺼짐) | `markFailed` → `FAILED` | 즉시 |
| DISCORD | 성공 | `markSent` | 즉시 |
| DISCORD | `OwnerAlertSendException(permanent = true)` | `markFailed` | 즉시 |
| DISCORD | `OwnerAlertSendException(permanent = false)` 또는 그 밖 예외 | `markAttemptFailed` | 회차 끝 |
| 어느 쪽이든 | 전이가 fencing 됨(false) | WARN `transition was fenced` 후 계속 | — |

메일 채널이 꺼진 기동에서는 app 이 `UNAVAILABLE_MAIL_SENDER`(항상 `OwnerAlertSendException(true, "owner alert channel unavailable: EMAIL")`)를
넘긴다 — 채널을 끄기 전에 쌓인 메일 행은 1회 시도 후 `FAILED`. Discord 를 끈 경우도 같다. runbook 에 적는다.

`markFailed` 는 `AccountPnlAlertStore` 에 **기본 구현 메서드**로 추가한다(기본 = `markAttemptFailed` 위임 — 재시도 불가 실패를 구분하지 못하는
구현은 시도 실패로 센다). JDBC 구현은 override 해서 즉시 `FAILED`. 추상 메서드로 두면 동결 `FakeStore` 가 컴파일되지 않는다.

로그 (키 전문): 실패 WARN `Owner alert send failed: id={}, channel={}, attempt={}, permanent={}, error={}, cause={}, reason={}` — `error`/`cause` 는
예외 **단순 클래스 이름**, `reason` 은 `OwnerAlertSendException` 의 메시지(우리가 만든 문자열, URL 없음)이고 그 밖 예외면 `-`. **담지 않는 값**:
수신 주소, webhook URL·token, 비-`OwnerAlertSendException` 의 메시지(SMTP 예외는 주소를 담을 수 있다), 본문.

### 5.3 Discord webhook adapter (`infrastructure:batch`, `notification/DiscordWebhookSenderAdapter`)

요청: `POST {webhook URL}` `Content-Type: application/json`, WebClient(기존 infra:batch 패턴), 요청 timeout 3s. 봇 토큰·OAuth 없음.

payload (전문):

| push | `content` | `allowed_mentions` | `flags` |
|---|---|---|---|
| `SILENT` | 없음 | `{"parse":[]}` | `4096` (SUPPRESS_NOTIFICATIONS) |
| `SOUND` | 없음 | `{"parse":[]}` | 없음 |
| `HERE` | `"@here"` | `{"parse":["everyone"]}` | 없음 |

공통: `"embeds":[{"title":…,"description":…,"color":…}]`. 키 순서 `content`, `embeds`, `allowed_mentions`, `flags`.

- Discord `allowed_mentions.parse` 에는 `here` 타입이 없다 — `@here` 와 `@everyone` 은 모두 `everyone` 으로 허용된다. "here 만 허용"은
  **content 가 정확히 `@here` 이고 embed 설명의 멘션은 알림을 만들지 않는다**는 것으로 보장한다. `[ASSUMPTION]` A4.
- `SILENT`/`SOUND` 는 `parse: []` 로 본문에 우연히 들어간 멘션도 막는다.

응답 분류 (전문, 위에서부터 먼저 맞는 행):

| 응답 | 결과 |
|---|---|
| 2xx | 성공 |
| 429, 대기 시간 ≤ 2s, 이번 send 의 첫 시도 | 대기 후 **1회** 재시도 (WARN `Discord webhook rate limited; retrying once: target={}, retryAfterMs={}`) |
| 429, 대기 시간 없음·파싱 불가·> 2s, 또는 재시도도 429 | `OwnerAlertSendException(false, "Discord webhook {TARGET} rate limited")` |
| 그 밖 4xx (400·401·403·404·413 …) | `OwnerAlertSendException(true, "Discord webhook {TARGET} responded {status}")` |
| 5xx·1xx·3xx | `OwnerAlertSendException(false, "Discord webhook {TARGET} responded {status}")` |
| **취소** — `sleeper` 대기 중 `InterruptedException`, 또는 요청 예외의 원인 체인(자기 자신 포함, 깊이 16)에 `InterruptedException`·`CancellationException` 이 있음 | interrupt 플래그를 **복구**하고 `OwnerAlertSendException(false, "Discord webhook {TARGET} interrupted")` 를 던진다 |
| 그 밖 요청 예외(연결 실패·timeout·DNS 등) | `OwnerAlertSendException(false, "Discord webhook {TARGET} request failed: {예외 단순 클래스 이름}")` |

- 취소 판정은 `AccountPnlMonitorJob` 의 `isCancellation`(`AccountPnlMonitorJob.kt:347-350`)과 같은 규칙이다. 다만 원본 예외를 그대로 다시 던지지 않는다 —
  `WebClientRequestException` 메시지가 요청 URI(= token)를 담기 때문이다. 대신 플래그를 복구한 뒤 cause 없는 위 예외를 던지고, 디스패처는 그 행을
  재시도 가능 실패로 모은 뒤 **다음 claim 전 interrupt 확인에서 회차를 멈춘다**(남은 행 PENDING, `Success` — §8). 플래그를 복구하지 않으면 취소된 회차가
  나머지 행까지 계속 보내므로 이 규칙은 필수다. 테스트: 호출 스레드에 interrupt 를 건 뒤 `send` → `permanent=false`·메시지 `… interrupted`·플래그 true·
  URL 없음, 그리고 원인 체인에 `InterruptedException` 을 감싼 요청 예외를 분류 함수에 넣으면 취소로 판정(dod AC6-i).
- 대기 시간: `Retry-After` 헤더(초, 소수 허용) → 없으면 JSON 본문 `retry_after`(초) → 둘 다 없으면 "없음". 음수는 없음.
- `{TARGET}` 은 요청 route 의 enum 이름. **예외는 `cause` 를 연결하지 않는다** — `WebClientResponseException`/`WebClientRequestException` 메시지는
  요청 URI(= token)를 담는다. 예외 메시지·원인 체인·stack trace 어디에도 URL·token 이 없어야 한다(AC6).
- 대기는 주입 가능한 `sleeper` 로 한다(테스트는 실제로 잠들지 않는다).
- 최악 소요: 행당 3s(+429 경로 2s+3s). 회차 상한 10행이면 이론상 40s timeout 을 넘을 수 있다 — 넘으면 `JobExecutor` 가 interrupt 하고 위 취소 규칙으로
  남은 행은 PENDING 으로 다음 회차에 간다(at-least-once 유지). 실제 회차당 행은 0~4 개다. `[ASSUMPTION]` A6.

Webhook 해석 `DiscordWebhooks` (infra, `class` — 자동 `toString` 없음):
- 설정된 대상은 자기 URL. **설정 안 된 대상은 `[PNL, MARKET, OPS]` 순서에서 처음 설정된 대상으로 fallback** — 하나만 설정하면 전부 그리로,
  둘만 설정하면 빠진 대상은 그 순서의 첫 설정 대상으로 간다(예: PNL·OPS 설정 → MARKET 은 PNL). 오케스트레이터 판정 A5.
- `toString()` = `DiscordWebhooks(pnl=set, market=fallback(PNL), ops=set)` 형태 — URL 없음.

### 5.4 DB·cache 순서

DB 만 쓴다. 상태 갱신 + outbox 행 삽입이 한 트랜잭션(DB-first)이고 외부 발송은 claim commit **뒤**다(기존 계약). cache·Redis 무변경.
발송은 at-least-once — Discord 성공 후 `markSent` 전에 프로세스가 죽으면 같은 embed 가 한 번 더 갈 수 있다(runbook 명시).

## 6. Discord 렌더링 (Domain `accountpnl/AccountPnlDiscordContent`)

### 6.1 메일 불변

`AccountPnlMailContent` 의 공개 함수 4개 시그니처·eventKey·제목·본문은 바이트 불변이다. Discord 렌더러와 숫자 서식을 공유하려고 private 서식
요소(`TIME_FORMAT`(`private val`, :20)·`RUNBOOK`·`sign`(:141)·`isZero`(:154)·`percent`·`krw`·`plainKrw`·`cost`·`krwOrZero`·`costUsdt`·`signedUsdt`·`plain`·
`format`·`label`·`time`)를 `internal object AccountPnlFormat` 으로 옮긴다. `eventKey`·`HOURLY_HEADER`·`FOREIGN_PRICE_SCALE`·`profitLines` 는 메일 전용이라
남긴다. 옮기기 **전에** 정시 보고·중단(빗썸 인증/그 밖)·복구 메일 golden 을 특성화 테스트로 추가한다(기존 수익 golden 2건과 함께 6건).

### 6.2 템플릿 (exact copy)

`{p}` = `percent()`(부호 + `0.00` + `%`), `{krw}` = `krw()`, `{cost}` = `cost()`, `{krwOrZero}`, `{plain}` 은 메일과 같은 함수.
`{short(t)}` = `t` 를 `zone` 에서 `yyyy-MM-dd HH:mm z` (`Locale.ROOT`) — `Asia/Seoul` 이면 `2026-10-23 09:26 KST` (`[ASSUMPTION]` A1).

| 종류 | 제목 | 색 |
|---|---|---|
| 수익 | `💰 수익 알림 · 순수익률 {p(netPercent)}` | `0x2ECC71` (3066993) |
| 정시 보고 | `📊 정시 보고 {HH}:00` (`calculatedAt` 의 `zone` 정시) | `0x3498DB` (3447003) |
| 중단 | `🚨 모니터링 중단 — {label(category)}` | `0xE74C3C` (15158332) |
| 복구 | `✅ 모니터링 복구` | `0x95A5A6` (9807270) |

수익·정시 보고 설명 (줄 단위, `\n` 연결):

```
순수익률 {p(netPercent)} · 순손익 {krw(netKrw)}
체결가 기준 {p(netPercentAtLast)} ({krw(netKrwAtLast)})
빗썸 청산가 {format("#,##0", koreaExitPrice)}원 × {plain(korea.quantity)} BTC (호가 VWAP)
바이낸스 청산가 {format("#,##0.####", foreignExitPrice)} USDT × {plain(|foreign.signedQuantity|)} BTC · 환율 {format("#,##0.00", fxRate)}
gross {krw(grossKrw)} · 예상 청산 수수료 {cost(exitFeeEstimateKrw)}
진입 수수료 {cost(entryFeeKrw)} · 펀딩 {krwOrZero(fundingKrw)}
⚠ 미반영: {items}                                        ← 미반영 항목이 하나라도 있을 때만
빗썸 평단 사용자 수정됨                                   ← korea.averageBuyPriceModified 일 때만
바이낸스 포지션: {수동 입력(env) | 거래소 API} · {short(calculatedAt)}
```

`{items}` 는 순서대로 `진입 수수료(빗썸, 이력 없음)`, `진입 수수료(바이낸스, {사유})`, `펀딩비({사유})` 중 해당 항목을 `, ` 로 잇는다.
`{사유}` = 출처 `MANUAL_ENV` 면 `수동 미입력`, 아니면 `이력 없음`. brief §1 예시와는 1행 구분자(`·`)만 다르다.

중단 설명:

```
실계정 순손익 모니터링이 5분 넘게 중단되었습니다.
마지막 성공: {short(lastSuccessAt) | 없음}
알림 시각: {short(now)}
빗썸 Open API key 의 허용 IP 가 현재 공인 IP 와 같은지 확인하세요.   ← category == BITHUMB_AUTH 일 때만
조치 안내: docs/runbooks/account-pnl-alert.md
```

복구 설명: `실계정 순손익 모니터링이 복구되었습니다.\n복구 시각: {short(now)}`

### 6.3 Discord 제한과 절단 (`DiscordMessage`)

| 항목 | Discord 제한 | 우리 상한 |
|---|---|---|
| embed 제목 | 256 | **255** (outbox `subject VARCHAR(255)`) |
| embed 설명 | 4096 | 4096 |
| content | 2000 | `@here` 5자만 씀 |
| embed 합계(제목+설명+…) | 6000 | 255 + 4096 = 4351 < 6000 — 다른 embed 필드를 쓰지 않으므로 항상 만족 |

- 길이는 UTF-16 code unit(`String.length`) 기준으로 잰다 — code point 수 ≤ UTF-16 길이이므로 Discord 가 어느 쪽으로 세든 넘지 않는다.
- `DiscordMessage.of(title, description, color)` 가 절단한다: 넘치면 `max - 1` 까지 자르고 `…` 를 붙인다. 자르는 지점이 surrogate pair 가운데면
  한 칸 더 앞에서 자른다. 기본 생성자는 상한·`color ∈ 0..0xFFFFFF`·제목 비어 있지 않음을 `require` 한다.

## 7. 설정·env·기동 조건

### 7.1 env (최종 이름)

고정 env 5종 + 종류별 패턴 3종:

| env | property | 기본 | 의미 |
|---|---|---|---|
| `OWNER_ALERT_EMAIL_ENABLED` | `owner-alert.email.enabled` | `true` | 메일 채널 요청. 실제 사용은 `NOTIFICATION_EMAIL_ENABLED=true` 도 필요 |
| `OWNER_ALERT_DISCORD_ENABLED` | `owner-alert.discord.enabled` | `false` | Discord 채널 요청 |
| `OWNER_ALERT_DISCORD_WEBHOOK_PNL` | `owner-alert.discord.webhook.pnl` | 빈 값 | **비밀**. pnl 채널 webhook URL (https) |
| `OWNER_ALERT_DISCORD_WEBHOOK_MARKET` | `owner-alert.discord.webhook.market` | 빈 값 | **비밀**. market 채널 |
| `OWNER_ALERT_DISCORD_WEBHOOK_OPS` | `owner-alert.discord.webhook.ops` | 빈 값 | **비밀**. ops 채널 |
| `OWNER_ALERT_{KIND}_EMAIL` | `owner-alert.routes.{key}.email` | 빈 값(기본 표) | `true`\|`false` |
| `OWNER_ALERT_{KIND}_DISCORD` | `owner-alert.routes.{key}.discord` | 빈 값 | `pnl`\|`market`\|`ops`\|`none` |
| `OWNER_ALERT_{KIND}_PUSH` | `owner-alert.routes.{key}.push` | 빈 값 | `silent`\|`sound`\|`here` |

`{KIND}` ∈ `PROFIT`, `HOURLY_REPORT`, `MONITORING_OUTAGE`, `MONITORING_RECOVERED` (enum 이름), `{key}` 는 §3.1 의 key. 값은 trim·대소문자 무관,
빈 값 = 미지정. 종류별 env 3개씩은 **사용자 확정 D3**.

기존 env 유지: `ACCOUNT_PNL_ALERT_RECIPIENT`(property `account-pnl-monitor.recipient` — owner 메일 수신 주소, **메일 채널이 실제로 쓰일 때만 필수**, §7.2),
`NOTIFICATION_EMAIL_ENABLED`·`MAIL_*`·`ALERT_EMAIL_FROM`(SMTP), `ACCOUNT_PNL_HOURLY_REPORT_ENABLED`(정시 보고 **생성** on/off — 라우팅과 별개),
그 밖 `ACCOUNT_PNL_*`·`BITHUMB_*`·`BINANCE_*`. brief 가 예시로 든 `ACCOUNT_PNL_EMAIL_ENABLED`/`ACCOUNT_PNL_DISCORD_*` 는 구현된 적이 없으므로
호환 alias 를 두지 않는다.

### 7.2 기동 판정 (app 조건 = infra 조건, 전문)

```
emailRequested    = owner-alert.email.enabled 가 비었거나 없으면 true, 아니면 equalsIgnoreCase("true")
emailEffective    = emailRequested && notification.email.enabled equalsIgnoreCase "true"
discordRequested  = owner-alert.discord.enabled equalsIgnoreCase "true"
webhookConfigured = webhook.pnl / .market / .ops 중 하나라도 공백 아님
discordEffective  = discordRequested && webhookConfigured
recipientRequired = emailEffective

채널 필수 키   = recipientRequired 면 [account-pnl-monitor.recipient] 아니면 []
필수 키        = 채널 필수 키 + bithumb 2개 + 바이낸스 모드별(기존)   (선언 순서 = 기존 순서: recipient → bithumb → 모드)
채널 준비      = 채널 필수 키 모두 있음 ∧ ¬(discordRequested ∧ ¬webhookConfigured) ∧ (emailEffective ∨ discordEffective)
모니터 기동 ⇔ account-pnl-monitor.enabled ∧ 필수 키 모두 있음 ∧ 채널 준비 ∧ scheduling 켜짐 (기존)
```

**수신 주소 소유 (오케스트레이터 판정 I-3).** 메일 채널의 필수 키 판정과 수신 주소 읽기는 infra `OwnerAlertChannelRequirements` 한 곳이 소유한다:

| 멤버 | 계약 |
|---|---|
| `RECIPIENT_KEY = "account-pnl-monitor.recipient"` | 수신 주소 property key. env `ACCOUNT_PNL_ALERT_RECIPIENT` 는 `application.yml` 의 기존 줄(`recipient: ${ACCOUNT_PNL_ALERT_RECIPIENT:}`)이 그대로 연결한다 — key·env 무변경(기존 wiring 테스트·운영 env 호환). key rename 은 이 상수와 yml 한 줄만 바꾸면 되는 별도 작업이다 |
| `recipientRequired(env) = emailEffective(env)` | 메일 채널이 실제로 쓰일 때만 필수 |
| `missingKeys(env): List<String>` | `recipientRequired ∧ RECIPIENT_KEY 공백` 이면 `[RECIPIENT_KEY]`, 아니면 `[]` — 채널 쪽 필수 키 |
| `ready(env): Boolean` | `missingKeys.isEmpty() ∧ ¬discordMisconfigured ∧ anyChannel` — 위 "채널 준비" |
| `recipient(env): String` | 메일 sender 생성용. 공백이면 `IllegalStateException("account-pnl-monitor.recipient is required when the owner email channel is on")` (값 없음) |

- `AccountPnlMonitorRequirements.missingKeys` = `OwnerAlertChannelRequirements.missingKeys(env)` + bithumb·모드 키 중 공백(기존 순서). 기존 `COMMON` 에서 recipient 를 뺀다.
- **수신 주소가 묶이는 곳**: 메일 sender bean `accountPnlMailSender(emailSender: EmailSender, environment: Environment)` 가
  `AccountPnlEmailSenderAdapter(emailSender, OwnerAlertChannelRequirements.recipient(environment))` 로 만든다. 이 bean 은 더 이상
  `AccountPnlMonitorProperties` 를 받지 않는다 — 채널 bean 3개가 `EmailSender`·`Environment`·`OwnerAlertProperties`·`ObjectProvider` 만 의존하므로
  #90 이 그대로 `OwnerAlertChannelConfiguration` 으로 옮길 수 있다(§9). `AccountPnlMonitorProperties.recipient` 필드는 기존 바인딩·마스킹 테스트 때문에 남기되
  adapter 는 읽지 않는다(KDoc 에 "owner 메일 수신 주소는 `OwnerAlertChannelRequirements.recipient` 가 읽는다" 명시).
- app `AccountPnlMonitorEnabledCondition` 은 infra 를 import 하지 않으므로 같은 규칙(`RECIPIENT` 를 `emailEffective` 일 때만 필수)을 다시 적고, 일치는 wiring 행렬이 고정한다.

app `AccountPnlMonitorEnabledCondition` 의 평가 순서와 로그 (className 가드, **값 없이 env 이름만**):

| 순서 | 조건 | 결과 | 로그 (문구 전문) |
|---|---|---|---|
| 1 | `account-pnl-monitor.enabled` 아님 | false | 없음 |
| 2 | 모드 해석(기존) | 알 수 없는 값이면 예외 | — |
| 3 | 필수 키 누락 (recipient 는 `emailEffective` 일 때만 대상) | false | WARN `account-pnl-monitor enabled but required settings missing; monitor not started: binanceMode={}, missing={}` (기존. `missing` 은 env 이름 목록 — 예 `[ACCOUNT_PNL_ALERT_RECIPIENT]`) |
| 4 | `discordRequested ∧ ¬webhookConfigured` | false | WARN `owner-alert discord enabled but no webhook configured; monitor not started: set one of [OWNER_ALERT_DISCORD_WEBHOOK_PNL, OWNER_ALERT_DISCORD_WEBHOOK_MARKET, OWNER_ALERT_DISCORD_WEBHOOK_OPS]` |
| 5 | `¬emailEffective ∧ ¬discordEffective` | false | WARN `account-pnl-monitor enabled but email disabled; monitor not started` (기존 문구 그대로) |
| 6 | `emailRequested ∧ ¬smtp ∧ discordEffective` | true | WARN `owner-alert email enabled but NOTIFICATION_EMAIL_ENABLED is not true; email channel off` |
| 7 | 그 밖 | true | 없음 |

6번은 "SMTP 가 꺼져 있어도 Discord 만으로 기동 + WARN 1회" — **사용자 확정 D2**.

infra (`AccountPnlMonitorAdapterConfiguration`):
- 클래스 조건의 `@ConditionalOnBean(EmailSender::class)` 를 `@Conditional(OwnerAlertChannelAvailableCondition::class)` 로 바꾼다 —
  `AnyNestedCondition(REGISTER_BEAN)` = [`EmailSender` bean 존재 ∧ `emailEffective`] ∨ [`discordEffective`]. 기존 "`EmailSender` 가 없으면 adapter
  0개" 계약(속성은 켜졌는데 bean 없음)이 그대로 유지되고, Discord-only 기동(SMTP 꺼짐 → `EmailSender` 없음)도 열린다. `[ASSUMPTION]` A7.
- `AccountPnlCredentialsPresentCondition` = `enabled ∧ AccountPnlMonitorRequirements.missingKeys.isEmpty() ∧ ¬discordMisconfigured ∧ anyChannel`.
- 채널 판정·수신 주소 함수는 `infrastructure/batch/.../owneralert/OwnerAlertChannelRequirements` 한 곳(#90 이 재사용).

bean (모두 `AccountPnlMonitorAdapterConfiguration` 안, K4):

| bean | 조건 | 비고 |
|---|---|---|
| `accountPnlMailSender` (기존 이름) | + `@ConditionalOnBean(EmailSender)` + `OwnerAlertEmailRequestedCondition`(`emailEffective`) | 인자 `(EmailSender, Environment)`, 수신 주소 = `OwnerAlertChannelRequirements.recipient(environment)` (위 I-3) |
| `ownerAlertDiscordSender` (신규) | `DiscordWebhookConfiguredCondition`(`discordEffective`) | `DiscordWebhookSenderAdapter`. 생성 시 INFO `owner-alert discord webhooks: pnl={}, market={}, ops={}` — 값은 `set` 또는 `fallback(PNL\|MARKET\|OPS)` |
| `ownerAlertRouting` (신규) | 없음 | `OwnerAlertRouting(OwnerAlertChannels(email = 메일 sender bean 존재, discord = Discord sender bean 존재), properties.resolvedOverrides())` — 채널 플래그를 **bean 존재**에서 만들어 라우팅과 sender 가 어긋날 수 없다 |

app `AccountPnlMonitorConfiguration.accountPnlMonitorJob` 은 `ObjectProvider<AccountPnlMailSender>`·`ObjectProvider<OwnerAlertDiscordSender>`·
`OwnerAlertRouting` 을 받는다. 메일 sender 가 없으면 `OwnerAlertDispatcher.UNAVAILABLE_MAIL_SENDER` 를 넘긴다.

### 7.3 Properties 와 비노출 (`infrastructure/batch/.../owneralert/OwnerAlertProperties`)

`@ConfigurationProperties("owner-alert")`, 같은 configuration 에서 `@EnableConfigurationProperties` 로 바인딩(모니터 구성이 켜질 때만 검증 —
직전 단위와 같은 시점). webhook 과 route 값은 **`String?` 으로 받아 init 에서 직접 해석**하고, 실패 예외는 property key 와 규칙만 담으며 `cause` 를
연결하지 않는다(`URISyntaxException` 메시지는 입력 문자열을 담는다).

| 입력 | 규칙 | 실패 메시지 (전문) |
|---|---|---|
| `discord.webhook.{pnl\|market\|ops}` | trim 후 빈 값 = 미설정. 아니면 절대 URI, scheme `https`(대소문자 무관), host 있음 | `owner-alert.discord.webhook.{pnl\|market\|ops} must be an https URL` |
| `routes` 의 key | `OwnerAlertKind.key` 중 하나 | `owner-alert.routes.{key} is not a known alert kind (known: profit, hourly-report, monitoring-outage, monitoring-recovered)` — 목록은 enum 에서 생성 |
| `routes.{key}.email` | `true`\|`false` | `owner-alert.routes.{key}.email must be true or false` |
| `routes.{key}.discord` | `pnl`\|`market`\|`ops`\|`none` | `owner-alert.routes.{key}.discord must be pnl, market, ops or none` |
| `routes.{key}.push` | `silent`\|`sound`\|`here` | `owner-alert.routes.{key}.push must be silent, sound or here` |

key 는 사용자가 쓴 설정 이름이라 메시지에 넣는다(비밀 아님). 값은 넣지 않는다. http 를 거부하는 이유: token 이 경로에 있다.

**검증 시점 결정 (R1 Minor 8):** webhook 형식 검증은 `owner-alert.discord.enabled` 와 **무관하게** 바인딩 시점에 한다 — Discord 를 꺼 둔 채 잘못된 webhook 값
(예: `http://…`)이 남아 있어도 모니터 구성이 바인딩되면 기동 실패다. 이유: (1) route 값 검증도 채널 상태와 무관하게 실패하므로 규칙이 하나다, (2) 잘못된
비밀값을 둔 채 Discord 를 켜는 순간 처음 실패하는 지연 실패를 없앤다, (3) 실패 메시지는 key 만 담으므로 기동 실패가 누출 경로가 되지 않는다. 모니터가
꺼져 있으면(구성 비활성) 바인딩되지 않으므로 검증하지 않는다. runbook 에 "Discord 를 끈 상태여도 webhook env 에 잘못된 값이 있으면 기동 실패 — 비우거나
https URL 로" 를 적고, dod AC10-c 가 Discord 꺼짐 케이스로 고정한다.

`toString` 노출 (data class 자동 생성 포함, 전문):
- `OwnerAlertProperties(email=Email(enabled=…), discord=Discord(enabled=…, webhook=Webhook(pnl=set|<empty>, market=…, ops=…)), routes={…})`
- `Webhook.toString()` 은 override — `set`/`<empty>` 만. `RouteOverride` 는 자동 `toString`(email/discord/push 값 — 비밀 아님).
- `DiscordWebhooks.toString()` override (§5.3). `DiscordWebhookSenderAdapter` 는 `toString` 을 override 하지 않는 일반 class(필드 출력 없음).
- 수신 주소는 `OwnerAlertProperties` 에 없다(§7.2 — `Environment` 에서 sender 생성 시 한 번 읽는다). `AccountPnlMonitorProperties.toString` 의 기존 마스킹 유지.

## 8. 배치 Job 계약

- Scheduler→Job: 기존 `AccountPnlMonitorScheduler` → `AccountPnlMonitorJob.run()` 한 번. 무변경.
- lock key·lease·timeout: 기존 `lock:account-pnl:monitor`, 50s, 40s. **새 JobId·JobConfig 없음.**
- 생성자: 기존 11개 인자 뒤에 `routing: OwnerAlertRouting = OwnerAlertRouting.EMAIL_ONLY`, `discordSender: OwnerAlertDiscordSender? = null` 추가.
  `routing.channels.discord && discordSender == null` 이면 `IllegalArgumentException("owner-alert discord channel is enabled but no Discord sender was provided")`.

`JobResult` 분기 (전문 — 굵은 행이 이 단위의 추가):

| 상황 | 결과 |
|---|---|
| `recoverStale`/`load` 예외 | `Failure` |
| 관측 중 예외의 원인 체인에 `InterruptedException`·`CancellationException` | interrupt 플래그 복구 후 **예외를 다시 던진다**(결과 없음, `JobExecutor` 로 전파) — 저장·발송 없음. 기존 동작(`AccountPnlMonitorJob.kt:137-141`), 동결 테스트 `관측 중 인터럽트 예외는 실패 범주로 삼키지 않고 저장 없이 다시 던진다` |
| 저장 전 interrupt | `Skipped("cancelled")` |
| `save` 예외 | `Failure` |
| version 충돌 | `Skipped("state-version-conflict")` |
| `claim`·`markSent`·`markAttemptFailed` 예외 | `Failure` |
| **`markFailed` 예외** | **`Failure`** |
| 발송 실패(재시도 가능) | 행 → `PENDING`/`FAILED`, `Success` |
| **발송 실패(재시도 불가·채널 없음)** | **행 → `FAILED`, `Success`** |
| 발송 중 interrupt (Discord adapter 취소 규칙 §5.3 포함) | sender 가 실패로 끝났으면 그 행은 재시도 가능 실패(회차 끝 `markAttemptFailed`), sender 가 성공한 뒤 플래그만 섰으면 그 행은 `SENT`(기존 동결 테스트 `발송 중 인터럽트되면 보낸 건은 SENT 로 표시하고 claim 루프를 멈춘다`). 어느 쪽이든 claim 루프를 멈추고 남은 행 `PENDING`, `Success` |
| 그 밖 | `Success` |

## 9. #89~#91 연결 경로 (구조 변경 없이 끼우기)

| 이슈 | 추가하는 것 | 이 단위가 이미 준 것 |
|---|---|---|
| #89 계정 A1~A3 | `OwnerAlertKind` 값 3개(§3.2 표), 렌더러, 모니터 Job 의 알림 생성 분기, yml `routes` 3블록·env·runbook 행. 상태 컬럼이 필요하면 V20 | 라우팅·행 확장·전달·채널 설정 전부. 생산자가 모니터 Job 이라 배선 변화 없음 |
| #90 시장 M1~M4 | enum 4개, 시장 상태 store(새 테이블 + 같은 트랜잭션에서 outbox 행 삽입), 시장 알림 Job, **`OwnerAlertDeliveryJob`**(새 `JobId.OWNER_ALERT_DELIVERY` + Scheduler, 내부는 `OwnerAlertDispatcher` 그대로), 채널 bean 3개(`accountPnlMailSender`·`ownerAlertDiscordSender`·`ownerAlertRouting`)를 **본문 그대로** `OwnerAlertChannelConfiguration` 으로 이동(클래스 조건 = scheduling ∧ `OwnerAlertChannelAvailableCondition` ∧ `OwnerAlertChannelRequirements.ready`), 모니터 구성의 클래스 조건은 계정 키만 보게 축소 | `owner-alert` prefix·env 가 `account-pnl-monitor` 와 분리돼 있다. 디스패처는 Job 독립 클래스. 채널 판정·**수신 주소 필수 판정·수신 주소 읽기**가 `OwnerAlertChannelRequirements` 한 곳이고, 채널 bean 이 `AccountPnlMonitorProperties` 에 의존하지 않는다(§7.2 I-3) |
| #91 운영 O1~O3 | enum 3개, 운영 신호 Job, 같은 delivery Job 사용 | 같음 |

#90 주의: 김프 알림은 Premium identity 이므로 eventKey 에 `MarketPair`(빗썸 KRW ↔ 바이낸스 USDT, BTC)를 넣어야 한다(architecture 규칙).
#90 의 bean 이동은 `AccountPnlMonitorWiringTest` runner 구성 변경을 동반한다 — 그 단위의 dod 가 허용 예외로 다룬다.
outbox 4메서드를 `OwnerAlertOutbox` 상위 interface 로 올리는 것도 #90 몫이다(같은 시그니처라 기존 fake 가 무수정 컴파일된다).
#90 이 독립 전달 Job 을 추가하면 그 Job 의 claim 도 channel 을 아는 신 store 를 쓰므로 §4.5 의 "구 batch 와 겹치지 않음" 제약은 그대로 유지된다.

## 10. 설계 완결성 체크리스트 (orchestrator ④)

| 항목 | 결정 |
|---|---|
| Facade 이름·주입 | **N/A** — batch 전용, REST 없음. Batch 단일 진입은 `AccountPnlMonitorScheduler` → `AccountPnlMonitorJob` (무변경) |
| Domain port · 구현 모듈 | `OwnerAlertDiscordSender`(신규) ← `infrastructure:batch` `DiscordWebhookSenderAdapter`. `AccountPnlMailSender`(기존) ← `AccountPnlEmailSenderAdapter`. `AccountPnlAlertStore`(기존 + `markFailed` 기본 메서드) ← `infrastructure:common` `JdbcAccountPnlAlertStore`. Discord sender 는 port 로 둔다 — Domain/Application 이 HTTP 를 몰라야 하고 테스트 fake 경계가 필요하다 |
| MarketPair identity | **N/A** — owner 계정 알림은 단일 계정 헤지(BTC)이며 pair 로 키를 나눈 행·캐시가 없다. eventKey 는 기존 그대로. #90 의 시장 알림은 pair 를 eventKey 에 넣어야 한다(§9) |
| 시간·범위 | 모든 시각 `Instant` + 주입 `Clock`. 표시·정시 버킷 zone 은 **Job 생성자 기본값 `Asia/Seoul`**(`AccountPnlMonitorJob.kt:64`)이다 — app 배선(`AccountPnlMonitorConfiguration.kt:110-121`)은 zone 을 넘기지 않으며 `aggregation.zone` 은 이 Job 에 연결돼 있지 않다. 이 단위는 그 배선을 바꾸지 않고(§1.2) Discord 표시 시각도 같은 `zone` 을 쓴다. 새 범위 질의 없음(범위 계약 N/A) |
| DB·cache 순서 | DB-first 한 트랜잭션(상태 + outbox 행), 외부 발송은 claim commit 뒤. cache 없음 |
| Flyway | 필요. `V19__add_owner_alert_channel.sql` (latest V18 → 19). DDL §4.2. batch fixture 동기화. 배포·롤백 §4.5 |
| batch lock·lease·timeout·JobResult | 새 Job 없음. 기존 값 유지, 분기 §8 |
| 알림 event identity | owner 알림은 구독이 없다 — identity = `(event_key, channel)`, eventKey = 종류 + 정시 버킷/계산 시각(기존). cooldown 은 정책(`PnlAlertPolicy` 60분)이 소유. claim/fencing 경로 기존 그대로 |
| 공개 endpoint | 변경 없음 (`PublicEndpointPolicy`·`auth-security.md` 무변경) |
| Redis key/TTL/payload | 변경 없음 |

## 11. 테스트 전략

| 계층 | 무엇을 | 어디서 |
|---|---|---|
| Domain | 라우팅 기본표·해석 규칙·override, 행 확장, 행 불변식, Discord 절단 경계값(255/256, 4096/4097, surrogate), 렌더링 golden 4종, 메일 golden 6건 | `domain` unit (고정 `Instant`, `ZoneId.of("Asia/Seoul")`) |
| adapter | Discord payload 3종 JSON, target·fallback(1개·2개 설정), 응답 분류 전부, URL 비노출(메시지·원인 체인·stack trace), 대기 중 interrupt·요청 원인 체인 취소의 플래그 복구 | `infrastructure:batch` unit + MockWebServer |
| 설정 | webhook·route 해석·실패 메시지 비노출·`toString`·fallback 해석·dash key 바인딩, 채널 판정·수신 주소 필수 판정(`OwnerAlertChannelRequirements`) | `infrastructure:batch` unit (`ApplicationContextRunner`/`Binder`/`MockEnvironment`) |
| persistence | V19 두 경로(빈 DB, V18→V19 + 기존 행), CHECK·UNIQUE, 구 binary INSERT 호환, 롤백 SQL 뒤 구 binary claim 이 EMAIL 만 집음, store 채널 round-trip·중복 제거·`markFailed`·fencing | `infrastructure:common` integration (MySQL Testcontainers) |
| application | 디스패처 전이표 전부, Job 의 4종 × 채널 구성 행 생성, 재시도·영구 실패 | `apps:batch` unit (fake store·sender, `MutableClock`) |
| wiring | 채널 판정 행렬(app = infra), WARN 문구·env 이름만(수신 주소 누락은 메일 채널이 쓰일 때만), 라우팅 플래그 = sender bean 존재, Discord 꺼짐에도 잘못된 webhook 은 기동 실패, yml routes key = enum key | `apps:batch` unit (`ApplicationContextRunner`, `OutputCaptureExtension`) |
| E2E | 실제 MySQL fixture + 실제 store + 실제 Discord adapter(MockWebServer): 메일+Discord 발송, 503 재시도·재시작, 404 영구 실패 | `apps:batch` integration |
| 경계 | 모듈·import·관측 cardinality·test isolation(테스트 URL 은 `*.example.test`/localhost) | root `architectureTest` (기존 규칙, 신규 규칙 없음) |
| 사람 | 실제 Discord 채널에서 4종 수신·무음/소리/@here 체감 | T4 owner |

## 12. 가정과 결정

### 사용자 확정 (2026-10-23)

| # | 질문 | 확정 |
|---|---|---|
| D1 | Discord 가 꺼져 있을 때 정시 보고·복구도 메일로 보낼까? (brief §3 표는 둘 다 메일 `-`) | **Discord 꺼짐 → 4종 모두 지금처럼 메일. Discord 켜짐 → 라우팅 표대로 메일은 수익·중단만, 정시 보고·복구는 Discord 만** (§3.3) |
| D2 | `OWNER_ALERT_EMAIL_ENABLED=true`(기본)인데 SMTP 가 꺼져 있고 Discord 는 정상이면? | **Discord 만으로 모니터 기동 + WARN 1회** (§7.2 6번) |
| D3 | 알림 종류별 override 를 env 3개씩(`OWNER_ALERT_{KIND}_EMAIL/_DISCORD/_PUSH`)으로 둘까? | **종류별 env 3개씩** (yml 에 명시, 테스트가 enum 과 일치 고정 — §7.1, dod AC9-h) |

### 오케스트레이터 판정 — ⑦ 사용자 승인으로 확정 (2026-10-23)

| # | 판정 | 반영 위치 |
|---|---|---|
| A5 | webhook 3개 중 2개만 설정되면 빠진 대상은 `PNL → MARKET → OPS` 순서의 첫 설정 대상으로 간다(1개만 설정이면 모두 그리로 — brief) | §5.3, dod AC6-b |
| I-1 | 배포 = 구 batch 중지 후 신 batch 기동(겹치지 않음). 롤백 = 신 batch 중지 → §4.5 SQL(`WHERE channel = 'DISCORD' AND status IN ('PENDING','PROCESSING')` 을 `FAILED` 로) → 구 batch 기동. §4.1 의 "rolling 안전" 주장은 "INSERT 호환만, 구 binary claim 은 채널을 모른다"로 좁힌다 | K7, §4.1, §4.5, dod AC4-f·AC12-h |
| I-3 | 수신 주소 소유를 지금 옮긴다 — `OwnerAlertChannelRequirements` 가 `recipientRequired = emailEffective`·채널 필수 키·수신 주소 읽기를 소유하고, 메일 sender bean 은 `AccountPnlMonitorProperties` 대신 그것으로 수신 주소를 묶는다. `ACCOUNT_PNL_ALERT_RECIPIENT`·`account-pnl-monitor.recipient` 는 그대로 동작 | §7.2, §9, dod AC9-b·j·k |

### [ASSUMPTION]

| # | 가정 | 확인 방법·시점 |
|---|---|---|
| A1 | `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT)` 가 `Asia/Seoul` 에서 `KST` 를 낸다(JDK 21 CLDR) | Task 0 jshell 1줄. 다르면 `z` 대신 고정 접미사 규칙을 Gate 1 에 보고 |
| A2 | MySQL 8.0 에서 한 `ALTER TABLE` 로 컬럼 추가 + 인덱스 교체 + 새 컬럼을 참조하는 CHECK 추가가 성공한다 | Task 5 migration integration test |
| A3 | Spring `Map<String, data class>` 바인딩이 `hourly-report` 처럼 dash 가 든 key 를 보존한다 | Task 8 테스트 |
| A4 | Discord webhook 은 `flags: 4096` 을 받아 무음 전송하고, `@here` 는 `allowed_mentions.parse=["everyone"]` 일 때 울리며, 429 에 `Retry-After` 헤더와 `retry_after` 본문을 준다 | 외부 API 사실 — AC13(T4)에서 owner 가 확인 |
| A6 | 회차당 발송 행은 실제로 0~4개이며 Discord 장애 시 40s timeout 초과는 드물다 — 초과해도 at-least-once 는 유지 | 운영 관찰. 문제되면 #90 delivery Job 분리 시 재검토 |
| A7 | 클래스 수준 `AnyNestedCondition(REGISTER_BEAN)` 안의 `@ConditionalOnBean(EmailSender)` 가 auto-configuration import 경로에서 기존 `@ConditionalOnBean` 과 같은 시점에 평가된다 | Task 10 — 기존 wiring 테스트 무수정 통과 + Discord-only 케이스 |

### [DECISION_NEEDED]

없음 (D1~D3 사용자 확정, A5·I-1·I-3 은 위 판정으로 ⑦ 확인 대기).

## 13. 문서 갱신 대상

- `docs/runbooks/account-pnl-alert.md` — `## 환경변수` 표에 owner 채널 env(고정 5종 + 종류별 패턴 3종), `ACCOUNT_PNL_ALERT_RECIPIENT` 필수 조건 변경, 신규
  `## owner 알림 채널 (메일·Discord)` 절(webhook 만드는 법, URL 은 비밀, fallback(1개·2개 설정), 기본 라우팅 표, override env, 기동 판정·WARN·INFO 전문,
  Discord 켜면 정시 보고·복구 메일이 멈춘다, 재시도·영구 실패·채널 끄면 남은 행 FAILED, 중복 가능, Discord 를 꺼도 잘못된 webhook 값은 기동 실패,
  Spring web client DEBUG 로깅 금지, webhook 유출 시 Discord 에서 재발급), 신규 `### 배포와 롤백` (§4.5 순서와 롤백 SQL 전문),
  `## 끄는 법과 데이터 정리` 의 outbox 설명(채널 컬럼, owner 알림 공용), `## Discord 수신 체크리스트 (owner-alert-channel AC13)`
- `.ai/architecture/ARCHITECTURE_DESIGN.md` — 실계정 알림 데이터 흐름에 Discord 채널 한 줄 (`docs/check-documentation.sh` 통과)
- `docs/runbooks/redis-contract.md`·`auth-security.md`·`durable-notification-delivery.md` — 무변경

## 14. 변경 이력

| 라운드 | 변경 | 사유 |
|---|---|---|
| ⑥ R1 | K5·§3.3·§7.2 6번·§12 — D1~D3 을 사용자 확정으로 기록, `[DECISION_NEEDED]` 제거 | 사용자 결정 2026-10-23 |
| ⑥ R1 | K7·§4.1·§4.5 — "rolling 안전"을 "INSERT 호환만"으로 좁히고 배포 순서·롤백 SQL 추가 | Important 1 (구 claim SQL 에 channel 조건 없음), 판정 I-1 |
| ⑥ R1 | K3·§7.2·§7.3·§9 — 수신 주소 필수 판정·읽기를 `OwnerAlertChannelRequirements` 로, 메일 sender 가 `AccountPnlMonitorProperties` 에 의존하지 않게 | Important 3, 판정 I-3 |
| ⑥ R1 | §5.3 — A5 를 판정으로, 2개 설정 fallback 예시 | 판정 A5 |
| ⑥ R1 | §5.3 — 요청 원인 체인의 interrupt/취소를 플래그 복구 후 cause 없는 예외로 | Minor 6 |
| ⑥ R1 | §6.1 — 옮기는 서식 요소에 `sign`·`isZero`·`TIME_FORMAT`(`private val`) 추가 | Minor 4 |
| ⑥ R1 | §7.1 — env 를 "고정 5종 + 종류별 패턴 3종"으로 표기 | Minor 7 |
| ⑥ R1 | §7.3 — webhook 형식 검증은 Discord 꺼짐에도 한다(유지 결정과 이유) | Minor 8 |
| ⑥ R1 | §8 — 관측 중 interrupt 재전파 분기 추가 | Minor 3 |
| ⑥ R1 | §10 — zone 출처를 Job 생성자 기본값으로 정정 | Minor 1 |

## 구현 중 변경

승인된 절은 고치지 않고, 구현 중 설계와 달라진 점만 적는다.

- **조건 애노테이션 묶음.** app/infra 클래스의 기동 조건을 `@Conditional(A, B)` 하나로 묶었다. Kotlin 은 같은 애노테이션을 중복 선언할 수
  없고, `@Conditional` 에 여러 조건을 주면 AND 로 평가되므로 의미는 같다.
- **SMTP 플래그 비교.** SMTP 플래그는 trim 없이 비교한다. `EmailAutoConfiguration` 의 `@ConditionalOnProperty` 와 같은 규칙이다.
- **기동 INFO 추가.** 기동 시 `owner-alert routes: …` INFO 한 줄로 종류별 해석 경로를 요약한다(⑨ 리뷰 권고).
- **복구 SQL 비교 방식.** runbook 복구 SQL 은 컬럼 collation(`utf8mb4_unicode_ci`) 때문에 `BINARY` 비교를 쓴다.
- **전달 로그 문구.** `Account PnL mail …` 을 `Owner alert …` 로 바꿨다.
- **Discord 미반영 줄 기준.** Discord 에서 값을 반영하지 못한 줄은 `AccountNetPnl.*Unrecovered` 플래그를 기준으로 판정한다. calculate
  경로에서 snapshot 이 null 인 경우와 같은 취급이다.
- **이월.** poison row claim 격리, 회차 시간 예산, HTTP 408 처리, route key 대소문자 중복, KST 의 CLDR 의존은 이번에 다루지 않고 이슈 #90
  코멘트로 넘겼다.
