---
feature: 실계정 순손익 알림 (Live Account PnL Alert)
slug: live-account-pnl-alert
status: FROZEN
frozen_at: 2026-10-20T10:39:53+09:00
verdict_commit:
source: docs/work/live-account-pnl-alert/design.md (owner 승인 2026-10-20, 커밋 616bb74)
---

## 범위

**포함**

- 빗썸 private REST 조회 adapter — 잔고·평균매수가·체결 수수료·매도 수수료율 (design §1.2)
- 바이낸스 USDⓈ-M signed REST 조회 adapter — 포지션·진입가·`COMMISSION`·`FUNDING_FEE` 이력·taker 수수료율 (§1.2)
- 계정 포지션 정의 — 계정 상태 그대로, 한쪽 0·바이낸스 롱이면 포지션 없음 (§2)
- 순수익률 계산 — 호가 기준 판정값 + 체결가 기준 병기값, 이력 미확보 항목 0 처리 + 미반영 플래그 (§3, §3.1)
- 수익 알림 규칙 — 1% 돌파 즉시, 1시간 재발송, 0.9% 재무장 (§4.1)
- 모니터링 중단·복구 알림 — 5분 (§4.2)
- 메일 내용 (§4.3)
- 알림 상태 DB 저장과 재시작 내구성 (§4.4)
- 기본 꺼짐, 환경변수 credential, 비노출 (§5)
- 외부 API 조사 기록 (§7, `TP-OPEN-1`)

**제외** *(scope creep 차단선)*

- 주문·취소·출금·이체 API 구현 (§1.3)
- 웹 화면·REST endpoint (§1.3)
- master spec·거래 준비 산출물 수정 (§0.1, §0.2)
- 기존 `Tracking`·`TrackingGrossPnl`·`grossPnl` 계약 변경 (§1.3)
- 분할 매매의 실현손익·수수료 배분 (§3)
- BTC 외 심볼, 다른 거래소, 다중 계정 (§1.3)

## 기존 gate 조사

> Gate 1 필수. 2026-10-20 worktree `df15e79` 기준으로 실제로 찾고 실행했다.

| 찾아본 것 | 있었나 | 쓸 수 있나 |
|---|---|---|
| architecture test | **있다** — `architecture-tests/` 독립 source set, `./gradlew architectureTest`. `ArchitectureBoundaryTest`, `KotlinSourceDependencyScanner`, `ObservabilityCardinalityArchitectureTest`, `VerifiedBalanceNoCacheArchitectureTest` 등 9개. 2026-10-20 실행 exit 0 | **그대로 쓴다.** 모듈 경계·import 회귀 방어선. 조회 전용 강제(AC10)는 같은 source set 에 신규 테스트를 얹는다 |
| lint 규칙 | 있다 — ktlint·detekt (CI `quality-gate.yml`). 로컬 gradle task 없음 | 회귀 방어선(CI)으로만 |
| custom gradle task | **있다** — `architectureTest`, `*:integrationTest`, `verifyMigrations`, `verifyTestIsolationPolicy`, `verifyCoverageExclusions`, `harnessCheck` | **그대로 쓴다** |
| CI job | 있다 — `.github/workflows/quality-gate.yml` | 회귀 방어선 |
| 동결 계약 테스트 | **있다** — `io.premiumspread.config.TradePreparationWiringContractTest` (거래 준비 AC20: production 잔고 포트 구현 = `DeclaredBalanceAdapter` 뿐). 2026-10-20 실행 exit 0 | 회귀 방어선 R2. 이 단위가 그 포트를 구현하지 않음을 지킨다 |
| 기존 gross 계산 테스트 | **있다** — `domain/src/test/.../tracking/TrackingTest.kt` | 회귀 방어선 R3 |
| 외부 HTTP mock 패턴 | **있다** — `infrastructure/batch` 의 `ExchangeRateClientTest` (MockWebServer) | adapter 테스트(AC7·AC8)가 같은 패턴을 따른다 |

**결론.** 모든 수용기준을 `기존` 또는 `신규테스트` 로 덮는다. `신규스크립트` 는 필요 없다.

**오프라인 실행 메모.** 2026-10-20 첫 `--offline` 실행은 worktree Gradle cache 부재(`kotlin-allopen:2.0.20`)로
실패했고, 한 번 온라인 실행으로 cache 를 채운 뒤 `--offline` 이 동작했다. 아래 명령은 cache 가 채워진 상태 전제다.

**신규테스트 명령의 작성 시점 실행.** `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountNetPnlTest'
--offline --no-daemon` 을 2026-10-20 실행 — `No tests found for given includes` exit 1. 테스트 클래스 부재에 의한
실패이므로 **RED 가 아니다.** 각 AC 의 RED 는 plan 의 해당 태스크에서 테스트를 먼저 작성한 뒤 기대한 assertion
실패로 기록한다.

## 수용기준

| # | 수용기준 (관찰 가능) | 근거 | 출처 | 티어 | 검증 수단 | 검증 명령 | 통과 조건 |
|---|---|---|---|---|---|---|---|
| AC1 | 고정 입력(빗썸 수량·평단·bid 레벨·최근체결가, 바이낸스 수량·진입가·ask 레벨·최근체결가, 환율, 진입 수수료, 펀딩, 수수료율)에 대해 순손익 계산이 청산가를 각 leg 수량만큼 호가 레벨을 소화한 VWAP 로 잡고(깊이 부족이면 `INSUFFICIENT_DEPTH` 실패) design §3 공식의 `gross`·`entryFee`·`funding`·`exitFeeEst`·`net`·`netPercent`(호가 기준)·`netPercentLast`(체결가 기준)를 손으로 계산한 기대값과 정확히 같은 `BigDecimal` 로 낸다. 같은 입력을 최근 체결가로 넣은 `gross` 는 `Tracking.grossPnl` 의 `totalGrossPnlKrw` 와 같다. 진입 수수료·펀딩 이력이 미확보로 주어지면 그 항목은 0 이고 결과에 항목별 미반영 플래그가 참이다 | design §3, §3.1 | `요구` (수수료·펀딩 반영, 지금 청산 기준, 호가 판정·체결가 병기, 미확보 시 0 + 명시) + VWAP 는 `리뷰` (⑥) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountNetPnlTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountNetPnlTest' --offline --no-daemon` | exit 0 |
| AC2 | 빗썸 수량 0, 바이낸스 수량 0, 바이낸스 포지션이 롱인 각 경우에 계정 포지션 판정이 "포지션 없음" 을 낸다. 빗썸 수량>0 이고 바이낸스 숏 수량>0 이면 두 leg 의 수량을 각자 보존한 포지션을 낸다 | design §2 | `요구` (계정에 김프 포지션만 있음) + 롱 처리는 `추론` (design §6 내 판단) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountHedgePositionTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountHedgePositionTest' --offline --no-daemon` | exit 0 |
| AC3 | 고정 `Clock` 으로 순수익률 시계열을 넣었을 때 알림 정책이 다음 발송 결정을 낸다: 1% 미만→1% 이상 전이 시 즉시 1회 / 에피소드 중 1% 이상이고 마지막 발송 후 정확히 60분 미만이면 0회, 60분 이상이면 1회 / 에피소드 중 0.9% 이상 1% 미만에서는 60분이 지나도 0회 / 0.9% 미만 하락 후 다시 1% 이상이면 즉시 1회 / 포지션 없음이면 에피소드 종료·0회 / 에피소드 중 어느 leg 든 수량·평단이 바뀌면 새 에피소드로 즉시 1회 / 계산 실패 회차는 상태 불변·0회 | design §4.1 | `요구` (돌파 즉시·1시간 재발송·내려갔다 올라가면 즉시) + 0.9% 재무장은 `추론` (design §6 내 판단) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.PnlAlertPolicyTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.PnlAlertPolicyTest' --offline --no-daemon` | exit 0 |
| AC4 | 고정 `Clock` 으로 마지막 계산 성공 후 5분 이하에서는 중단 발송 0회, 5분 초과 첫 회차에 원인 범주(빗썸 인증 / 바이낸스 인증 / 네트워크·요청 한도 / 시세 지연 / 환율 수집 지연 / 호가 깊이 부족 / 기타 오류) 하나를 담은 중단 발송 1회, 이후 실패가 계속돼도 추가 0회, 그 뒤 첫 성공 회차에 복구 발송 1회를 낸다 | design §4.2 | `요구` (조회 실패 지속 시 중단 알림) + 5분·복구 메일은 `추론` (design §6 내 판단) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.MonitoringOutagePolicyTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.MonitoringOutagePolicyTest' --offline --no-daemon` | exit 0 |
| AC5 | 모니터링 Job 이 fake port 로 실행될 때: 호가 관측이 10초 초과로 오래됐거나 두 호가 관측 시각 차이가 5초 초과이거나 환율 수집 마지막 성공이 2시간 초과면 그 회차를 계산 실패로 기록하고 수익 발송을 enqueue 하지 않는다. 환율 값 자체가 2시간 넘게 같아도 수집 마지막 성공이 2시간 이내면 계산을 성공시킨다. 분류되지 않은 조회·해석 예외는 `UNEXPECTED` 범주로 중단 정책에 입력되고 Job 결과는 실패가 아니다. 상태 저장소 예외만 Job 실패다. 1% 이상 정상 회차에는 발송 1건을 enqueue 한다 | design §3 신선도 | `추론` (design §6 내 판단: 신선도 기준) + `리뷰` (⑥ 미분류 예외) | T1 | `신규테스트` `io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest' --offline --no-daemon` | exit 0 |
| AC6 | 실제 MySQL(Testcontainers)에서 1% 이상 회차로 발송 1건이 저장된 뒤, 새 Job 인스턴스(재시작 모사)가 같은 에피소드 안 60분 이내 1% 이상 회차를 처리하면 추가 발송 row 가 0건이다. 발송 대상은 at-least-once 로 SMTP fake 에 전달된다. 두 Job 인스턴스가 같은 회차를 동시에 처리해도 메일 row·전송은 1건이다. stale 복구된 claim 의 늦은 완료 처리는 상태를 바꾸지 않는다 | design §4.4 | `상위` (`.ai/rules/batch.md` Durable notification) + `리뷰` (⑥ claim·fencing) | T1 | `신규테스트` `io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest` | `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest' --offline --no-daemon` | exit 0 |
| AC7 | MockWebServer 에서 빗썸 adapter 가 (a) 요청 Authorization 헤더의 JWT 를 공식 문서 규격(access key·nonce·timestamp·query hash)대로 서명하고 테스트 secret 으로 검증 가능하며, (b) 기록 응답에서 KRW-BTC 수량·평균매수가·체결 수수료·매도 수수료율을 Snapshot 으로 내고, (c) 401 응답을 "빗썸 인증" 실패 범주로 낸다 | design §1.2, §7 | `요구` (빗썸 실보유내역) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.bithumb.BithumbPrivateAccountAdapterTest' --offline --no-daemon` | exit 0 |
| AC8 | MockWebServer 에서 바이낸스 adapter 가 (a) query 서명을 공식 문서 예제 벡터와 같은 HMAC-SHA256 값으로 만들고 `X-MBX-APIKEY` 헤더를 붙이며, (b) 기록 응답에서 BTCUSDT 숏 수량·진입가·진입 체결 수수료(BNB 수수료는 BNBUSDT 가격으로 USDT 환산, USDT·BNB 외 자산이면 `UNEXPECTED` 실패)·`FUNDING_FEE` 합계(여러 페이지)·taker 수수료율을 Snapshot 으로 내고, (c) 401/서명 오류 응답을 "바이낸스 인증" 실패 범주로 낸다 | design §1.2, §7 | `요구` (바이낸스 포지션·수수료·펀딩비) | T1 | `신규테스트` `io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest` | `./gradlew :infrastructure:batch:test --tests 'io.premiumspread.infrastructure.batch.exchange.binance.BinanceFuturesPrivateAccountAdapterTest' --offline --no-daemon` | exit 0 |
| AC9 | 고정 계산 결과로 만든 수익 메일의 제목에 순수익률(소수 둘째 자리 %)과 순수익금(원 단위)이 들어 있고, 본문에 gross·진입 수수료·누적 펀딩·예상 청산 수수료·체결가 기준 수익률·양 leg 수량/평단/청산가/최근체결가·환율·관측 시각이 있으며, 미반영 항목이 있으면 "미반영" 문구가 있다. 제목·본문 어디에도 테스트용 API key·secret 문자열이 없다 | design §4.3 | `요구` (수익률·수익금 명시) + 근거 표기는 `추론` (design §6 내 판단) | T1 | `신규테스트` `io.premiumspread.domain.accountpnl.AccountPnlMailContentTest` | `./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon` | exit 0 |
| AC10 | `architectureTest` 가 신규 private 계정 adapter 소스(빗썸·바이낸스)에서 GET 외 HTTP method 호출과 주문·출금·이체 경로 문자열을 찾으면 실패하고, 현재 소스에서는 통과한다 | design §0.1 (`LIVE-3`·`LIVE-4` 유지), §1.3 | `상위` (master spec `LIVE-3`·`LIVE-4`) | T1 | `신규테스트` `io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest` | `./gradlew :architecture-tests:architectureTest --tests 'io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon` | exit 0 |
| AC11 | 기능 플래그가 없거나 false 이거나 credential 환경변수 중 하나라도 비어 있거나 `batch.scheduling.enabled=false` 이면 batch context 에 모니터링 Job·Scheduler·private adapter bean 이 0개다. 플래그 true + credential·수신 주소 전부 존재 + 메일 발송 bean(`EmailSender`) 존재면 각 1개다. credential 값은 configuration properties 의 `toString()` 과 startup 로그에 나타나지 않는다 | design §5 | `요구` (로컬 실행, 조회 전용 key) + 기본 꺼짐·비노출은 `상위` (`LIVE-2`) | T1 | `신규테스트` `io.premiumspread.config.AccountPnlMonitorWiringTest` | `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon` | exit 0 |
| AC12 | `understanding.md` 에 design §7 조사 표의 8개 항목 각각에 대해 공식 문서 URL·확인일·결론이 기록되어 있다 (`TP-OPEN-1` 해소 근거) | design §7 | `상위` (거래 준비 `TP-OPEN-1`) | T3 | 기록 문서 `docs/work/live-account-pnl-alert/understanding.md` | — | 8개 항목 전부 URL·확인일·결론 존재, 사람 사후 검토 |
| AC13 | owner 가 로컬에서 실제 read-only key 로 batch 를 실행했을 때, 메일에 찍힌 순수익률·수익금이 owner 가 거래소 화면에서 확인한 수량·평단·수수료·펀딩으로 검산한 값과 일치함을 owner 가 확인한다 | design §0, §5 | `요구` (실제 보유내역 기반 손익) | T4 | owner 확인 | — | PR 코멘트에 owner 확인 기록 |

**기준 개수**: 13개

**분할하지 않는 이유**: 13개는 계산(AC1·AC2) → 판정(AC3·AC4·AC5) → 저장(AC6) → 외부 조회(AC7·AC8) → 출력(AC9) →
경계(AC10·AC11) → 실측(AC12·AC13) 의 하나의 수직 경로다. 조회 adapter 만 먼저 머지하면 소비자가 없는 bean 이 되고,
판정만 먼저 머지하면 입력이 fake 뿐이라 owner 에게 가치가 0 이다. owner 가 원하는 것은 "메일이 온다"는 하나의 결과이고,
AC13 은 전 경로가 있어야만 검증된다. 대신 plan 태스크를 위 순서로 쪼개 태스크 단위 리뷰를 받는다.

**티어 강등 사유** *(T1 이 아닌 항목만. Gate 1 에서 사람이 함께 승인한다)*

- AC12 → T3: 산출물이 외부 공식 문서를 읽고 내린 사람의 판단 기록이다. 문서 URL 의 내용이 맞는지는 exit code 로 잴 수
  없다. 형식(항목 존재)만 재는 스크립트를 만들면 내용 없는 표도 통과하므로 T1 흉내가 된다.
- AC13 → T4: 실 credential·실계정은 테스트·CI 에서 쓰지 않는다(프로젝트 testing 규칙, master spec). 실계정 값과의 일치는
  owner 만 거래소 화면으로 확인할 수 있다.

## 회귀 방어선

이번 변경으로 깨질 수 있는 기존 동작. RED 면제 대상이다.

| # | 지켜야 할 동작 | 검증 명령 |
|---|---|---|
| R1 | 모듈 경계·import·관측 cardinality 아키텍처 규칙 | `./gradlew architectureTest --offline --no-daemon` |
| R2 | 거래 준비 AC20 — production 잔고 포트 구현은 `DeclaredBalanceAdapter` 뿐 | `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` |
| R3 | 기존 `Tracking.grossPnl` 결과 불변 | `./gradlew :domain:test --tests 'io.premiumspread.domain.tracking.TrackingTest' --offline --no-daemon` |
| R4 | 전체 unit test | `./gradlew test --offline --no-daemon` |
| R5 | migration append-only·빈 DB→latest·이전→latest | `./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon` |
| R6 | 기존 premium threshold 알림 durable delivery (outbox 를 일반화하는 경우 특히) | `./gradlew :apps:batch:integrationTest --offline --no-daemon` |
| R7 | 정본 문서·하네스 정합성 | `./gradlew harnessCheck --offline --no-daemon` |

## 스펙 리뷰 라운드 기록 (⑥ codex adversarial-review)

| 라운드 | 지적 | 처리 | 반영 커밋 |
|---|---|---|---|
| R0 (형식 미지정 실행) | high 2 — master spec credential 경계 우회, outbox 동시성 | 전자 REBUT(owner 승인 결정 design §0.1, 범위 밖) · 후자 R1 과 중복 | — |
| R1 | high 2 · medium 1 — outbox claim·fencing 부재, 미분류 예외의 중단 정책 우회, 최우선 호가 전량 청산 과대평가 | ACCEPT 3 | `7f7654c` |
| R2 | medium 2 — 두 호가 시점 skew, fingerprint 의 한국 leg 누락 | ACCEPT 2 | `47b24e8` |
| R3 | medium 1 — BNB 수수료 0 처리 | ACCEPT | `ceaadaf` |
| R4 | medium 1 — BNB 환산값 캐시 고정 | ACCEPT | `cc9e0d6` |
| R5 | medium 1 — 같은 fingerprint 재진입 시 캐시 고정 | ACCEPT | `06a6e51` |
| R6 | 없음 — verdict approve | — | — |

## 증거 로그

> 구현 중 append only. 지우거나 고쳐 쓰지 말 것.

### Gate 1 기준선 (2026-10-20, `616bb74`)

```
$ ./gradlew architectureTest :apps:api:test --tests '*TradePreparationWiring*' --no-daemon -q
exit=0   (R1·R2 기준선. 첫 --offline 실행은 cache 부재로 실패 → 온라인 1회로 cache 채움)

$ ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountNetPnlTest' --offline --no-daemon
> No tests found for given includes: [io.premiumspread.domain.accountpnl.AccountNetPnlTest](--tests filter)
BUILD FAILED  exit=1   (테스트 부재 — RED 아님. RED 는 구현 태스크에서 기록)
```

### ⑧ 착수 (2026-10-20, `e97fd01` origin/dev merge 후)

```
$ ./gradlew compileKotlin compileTestKotlin --offline --no-daemon -q
> No cached version of com.squareup.okhttp3:mockwebserver:4.12.0 available for offline mode.
BUILD FAILED   (worktree Gradle cache 부재)
$ ./gradlew compileKotlin compileTestKotlin compileIntegrationTestKotlin compileArchitectureTestKotlin --no-daemon -q
exit=0   (온라인 1회 실행으로 cache 채움 — 이후 전부 --offline)
$ ./gradlew compileKotlin compileTestKotlin --offline --no-daemon -q
exit=0
```

### AC2 — Task 2 (2026-10-20, 커밋 전)

```
$ ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountHedgePositionTest' --offline --no-daemon   # RED (resolve 가 항상 포지션 반환하는 stub)
AccountHedgePositionTest > 빗썸 수량 0 이면 포지션 없음() FAILED
AccountHedgePositionTest > 바이낸스 롱이면 포지션 없음() FAILED
AccountHedgePositionTest > 바이낸스 수량 0 이면 포지션 없음() FAILED
AccountHedgePositionTest > 빗썸 수량이 dust 미만이면 포지션 없음() FAILED
7 tests completed, 4 failed
BUILD FAILED

$ (같은 명령, dust/롱 판정 구현 후)   # GREEN
BUILD SUCCESSFUL
$ ./gradlew :domain:test --offline --no-daemon
BUILD SUCCESSFUL
```

### AC1 — Task 3 (2026-10-20, 커밋 전)

```
$ ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountNetPnlTest' --offline --no-daemon   # RED (calculate 가 전부 0 을 반환하는 stub)
AccountNetPnlTest > 호가 기준 순손익을 공식대로 계산한다() FAILED          (expected: -239381.4066 but was: 0)
AccountNetPnlTest > 진입 수수료 이력 미확보면 0 과 플래그() FAILED
AccountNetPnlTest > 펀딩 지급은 손실로 반영한다() FAILED
AccountNetPnlTest > 호가 깊이가 수량보다 부족하면 INSUFFICIENT_DEPTH() FAILED   (Expecting code to raise a throwable)
AccountNetPnlTest > 체결가 기준 gross 는 Tracking grossPnl 과 같다() FAILED
AccountNetPnlTest > 체결가 기준 값은 lastPrice 로 계산한다() FAILED
AccountNetPnlTest > 최우선 잔량이 부족하면 다음 레벨까지 VWAP 로 청산가를 계산한다() FAILED
AccountNetPnlTest > 바이낸스 매도호가도 앞 레벨부터 소화한다() FAILED
8 tests completed, 8 failed
BUILD FAILED

$ (같은 명령, VWAP·design §3 공식 구현 후)   # GREEN
BUILD SUCCESSFUL
$ ./gradlew :domain:test --tests 'io.premiumspread.domain.tracking.TrackingTest' --offline --no-daemon   # R3
BUILD SUCCESSFUL
$ ./gradlew :domain:test --offline --no-daemon
BUILD SUCCESSFUL
```

### AC3 — Task 4 (2026-10-20, 커밋 전)

```
$ ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.PnlAlertPolicyTest' --offline --no-daemon   # RED (decide 가 상태 불변·send=false 를 반환하는 stub)
PnlAlertPolicyTest > 0_99에서 1_00으로 오르면 즉시 발송() FAILED
PnlAlertPolicyTest > scale 이 달라도 1에 해당하면 발송한다() FAILED
PnlAlertPolicyTest > 에피소드 중 60분에 재발송한다() FAILED
PnlAlertPolicyTest > 에피소드 중 바이낸스 쪽이 바뀌어 fingerprint 가 바뀌면 새 에피소드로 즉시 발송() FAILED
PnlAlertPolicyTest > 에피소드 중 빗썸 쪽만 바뀌어도 새 에피소드로 즉시 발송() FAILED
12 tests completed, 8 failed
BUILD FAILED

$ (같은 명령, 상태기계 구현 후)   # GREEN
BUILD SUCCESSFUL
$ ./gradlew :domain:test --offline --no-daemon
BUILD SUCCESSFUL
```

### AC4 — Task 5 (2026-10-20, 커밋 전)

```
$ ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.MonitoringOutagePolicyTest' --offline --no-daemon   # RED (decide 가 TODO stub)
MonitoringOutagePolicyTest > 실패 5분 정각에는 알리지 않는다() FAILED
MonitoringOutagePolicyTest > 5분 1초에 원인 범주와 함께 1회 알린다() FAILED
MonitoringOutagePolicyTest > 복구 첫 회차에 1회 복구 알림() FAILED
MonitoringOutagePolicyTest > 첫 실행부터 실패면 첫 실행 시각 기준으로 잰다() FAILED
9 tests completed, 9 failed
BUILD FAILED

$ (같은 명령, 상태기계 구현 후)   # GREEN
BUILD SUCCESSFUL
$ ./gradlew :domain:test --offline --no-daemon
BUILD SUCCESSFUL
```

### AC9 — Task 6 (2026-10-20, 커밋 전)

```
$ ./gradlew :domain:test --tests 'io.premiumspread.domain.accountpnl.AccountPnlMailContentTest' --offline --no-daemon   # RED (TODO() stub)
AccountPnlMailContentTest > 제목에 순수익률과 순수익금이 있다() FAILED
AccountPnlMailContentTest > 본문에 계산 근거가 모두 있다() FAILED
AccountPnlMailContentTest > 미반영 항목을 명시한다() FAILED
AccountPnlMailContentTest > 평단 수정 표시를 넣는다() FAILED
AccountPnlMailContentTest > 중단 메일 제목에 범주가 있다() FAILED
BUILD FAILED

$ (같은 명령, 구현 후)   # GREEN
BUILD SUCCESSFUL
$ ./gradlew :domain:test --offline --no-daemon
BUILD SUCCESSFUL
```

### Task 7 — store (R5) (2026-10-20, 커밋 전)

AC 번호 없음. 상태·outbox 저장소(V18 + `JdbcAccountPnlAlertStore`) 실측. RED 는 port 와 틀린 값을 돌려주는 adapter
stub 으로 컴파일이 통과한 상태에서 확인했다(컴파일 실패를 RED 로 쓰지 않음).

```text
$ ./gradlew :infrastructure:common:integrationTest --tests '*JdbcAccountPnlAlertStoreIntegrationTest' --offline --no-daemon   # RED (stub)
JdbcAccountPnlAlertStoreIntegrationTest > 빈 테이블이면 초기 상태 version 0() FAILED
JdbcAccountPnlAlertStoreIntegrationTest > version 이 다르면 상태도 메일도 저장하지 않는다() FAILED
JdbcAccountPnlAlertStoreIntegrationTest > 동시에 두 claim 해도 한 행은 한쪽만 잡는다() FAILED
JdbcAccountPnlAlertStoreIntegrationTest > 5번째 실패에서 FAILED() FAILED
JdbcAccountPnlAlertStoreIntegrationTest > stale PROCESSING 은 PENDING 으로 복구되고 이전 token 의 markSent 는 false() FAILED
...
12 tests completed, 12 failed
BUILD FAILED

$ (같은 명령, 구현 후)   # GREEN
BUILD SUCCESSFUL   (12 tests, 0 failures, 0 errors)
$ ./gradlew :infrastructure:common:verifyMigrations --offline --no-daemon   # R5
BUILD SUCCESSFUL
$ ./gradlew :infrastructure:common:test :domain:test :infrastructure:batch:test :apps:batch:test :apps:api:test architectureTest --offline --no-daemon
BUILD SUCCESSFUL
$ ./gradlew :infrastructure:common:integrationTest --offline --no-daemon
BUILD SUCCESSFUL
```

### AC7 — Task 8 (2026-10-20, 커밋 전)

```
RED   $ ./gradlew :infrastructure:batch:test --tests '...bithumb.BithumbPrivateAccountAdapterTest' --offline --no-daemon
      24 tests completed, 24 failed   (adapter 가 UnsupportedOperationException 을 던지는 stub)
GREEN $ 같은 명령 (구현 후)
      BUILD SUCCESSFUL (24건 통과)
      $ ./gradlew :infrastructure:batch:test :domain:test --offline --no-daemon
      BUILD SUCCESSFUL
```

### AC8 — Task 9 (2026-10-20, 커밋 전)

```
RED   $ ./gradlew :infrastructure:batch:test --tests '...binance.BinanceFuturesPrivateAccountAdapterTest' --offline --no-daemon
      23 tests completed, 22 failed   (adapter 가 UnsupportedOperationException 을 던지는 stub; 서명 벡터 테스트만 통과)
GREEN $ 같은 명령 (구현 후)
      BUILD SUCCESSFUL (23건 통과)
      $ ./gradlew :infrastructure:batch:test --offline --no-daemon
      BUILD SUCCESSFUL
```

### AC5 — Task 10 (2026-10-20, 커밋 전)

```
RED   $ ./gradlew :apps:batch:test --tests 'io.premiumspread.application.job.accountpnl.AccountPnlMonitorJobTest' --offline --no-daemon
      26 tests completed, 25 failed   (Job 이 Skipped("stub") 를 돌려주는 stub; typed JobConfig 테스트만 통과)
GREEN $ 같은 명령 (구현 후)
      BUILD SUCCESSFUL (26건 통과)
      $ ./gradlew :apps:batch:test :infrastructure:batch:test :domain:test --offline --no-daemon
      BUILD SUCCESSFUL
      $ ./gradlew architectureTest --offline --no-daemon
      BUILD SUCCESSFUL
```

### AC11 — Task 11 (2026-10-20, 커밋 전)

- RED: 조건 클래스(`AccountPnlCredentialsPresentCondition`, `AccountPnlMonitorEnabledCondition`) 적용을 제거하고
  `./gradlew :apps:batch:test --tests 'io.premiumspread.config.AccountPnlMonitorWiringTest' --offline --no-daemon`
  -> `9 tests completed, 1 failed` (`key 하나라도 비면 0개() FAILED`) — credential 이 비어도 bean 이 생긴다.
- GREEN: 조건 복원 후 같은 명령 -> `BUILD SUCCESSFUL` (9 tests, 0 failed). 케이스: 플래그 없음/false/key 누락 0개,
  scheduling 비활성 시 Scheduler 0개, 활성 시 Job·Scheduler·두 adapter·메일 adapter 각 1개,
  `JobLastSuccessReadPort` -> `RedisJobRunRecorderAdapter`(R-3), EmailSender 없으면 adapter 0개,
  `Properties toString`·startup 로그(OutputCapture)에 secret 값 없음.
- R2: `./gradlew :apps:api:test --tests '*TradePreparationWiring*' --offline --no-daemon` -> `BUILD SUCCESSFUL`.
- 로그 마스킹: `LoggingAutoConfigurationTest` 에 access key/secret 케이스 추가, `./gradlew test architectureTest --offline --no-daemon` -> `BUILD SUCCESSFUL`.

### AC6 — Task 12 (2026-10-20, 커밋 전)

- 첫 실행이 통과해 RED 가 없었다 (Task 7·10 완료 상태). 그래서 `JdbcAccountPnlAlertStore.save` 첫 줄에 `if (true) return true`
  를 임시로 넣고 같은 명령을 실행했다 -> `5 tests completed, 4 failed` (재발송 타이머·SMTP 실패 재시도·동시 인스턴스·stale
  fencing 4건 FAILED, 시나리오 순수익률 검증만 통과). 이어서 `git checkout -- <store 파일>` 로 되돌렸고 `git diff --stat` 이
  비었음을 확인했다.
- GREEN: `./gradlew :apps:batch:integrationTest --tests 'io.premiumspread.application.job.AccountPnlAlertDurabilityIntegrationTest' --offline --no-daemon`
  -> `BUILD SUCCESSFUL` (5 tests, 0 failed).
- R6: `./gradlew :apps:batch:integrationTest --offline --no-daemon` -> `BUILD SUCCESSFUL` (98 tests, 0 failed).
- 배선 메모: batch 통합 테스트 스키마는 `batch-schema.sql` fixture 라서 V18 과 같은 두 테이블을 추가하고 `BatchTestConfig.BATCH_TABLES` 에 등록했다.

### AC10 — Task 13 (2026-10-20, 커밋 전)

- RED: 금지 규칙 목록을 비운 채 `./gradlew :architecture-tests:architectureTest --tests 'io.premiumspread.architecture.PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon` → `금지 패턴의 각 대안은 위반 표본에 개별로 매치한다` FAILED.
- GREEN: 규칙 20개(`.post/.put/.delete/.patch(`, `HttpMethod.POST|PUT|DELETE|PATCH`, 범용 `.method(` 중 `HttpMethod.GET` 아닌 것, 경로·키워드 11종) 구현 → 같은 명령 3 tests, 0 failures. 실제 adapter 2개(Bithumb/Binance) 위반 0건.
- 전체 `./gradlew architectureTest --offline --no-daemon` BUILD SUCCESSFUL (R1).
- 주의: dod AC10 명령의 루트 `architectureTest` 는 `--tests` 옵션이 없다 — `:architecture-tests:architectureTest` 로 실행해야 한다.

### Task 14 — 회귀 (R1·R4·R7)

- `./gradlew test architectureTest --offline --no-daemon` -> exit 0 (R1·R4).
- `./gradlew harnessCheck --offline --no-daemon` -> exit 0 (R7).
- `bash docs/check-documentation.sh` -> exit 0 (`documentation check passed (20 files, 15 required paths)`).
- runbook `docs/runbooks/account-pnl-alert.md` 추가. AC13 은 owner 가 체크리스트대로 검산하고 PR 코멘트로 남긴다.

## 사람 확인 (T4)

> 판정 주체는 사람뿐이다. AI가 이 표를 채우지 않는다.

| # | 확인 사항 | 확인자 | 날짜 | 앵커 |
|---|---|---|---|---|
| AC13 | 실계정 메일 값과 거래소 화면 검산 일치 | | | |

## 변경 요청

> 동결 후 기준을 바꿔야 할 때만 작성. 사람이 승인하기 전까지 구현을 중단한다.

| 대상 | 변경 전 | 변경 후 | 사유 | 승인 |
|---|---|---|---|---|
| AC10 검증 명령 | `./gradlew architectureTest --tests '…PrivateAccountReadOnlyArchitectureTest' …` | `./gradlew :architecture-tests:architectureTest --tests '…PrivateAccountReadOnlyArchitectureTest' --offline --no-daemon` | 루트 `architectureTest` 태스크는 `--tests` 옵션이 없어 명령 자체가 실패한다(2026-10-20 재현). 수용기준 문구·통과 조건(exit 0)은 불변 | owner 승인 2026-10-20 |

## 최종 판정

```
DoD VERDICT: live-account-pnl-alert @ <commit SHA>
  수용기준 표:     13개  (T1 11 · T2 0 · T3 1 · T4 1)
  T1/T2 자동:      11개 중 <p>개 PASS
  T3 기록 제출:    1개 중 <q>건
  T4 사람 확인:    1개 중 <r>건 완료, <1-r>건 대기
  변경 요청:       <k>건
  =>
```
