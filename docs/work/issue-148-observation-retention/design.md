# 설계 — 실계정 포지션 관측 이력 보존 기간 정책 (#148)

이슈: https://github.com/geonyeop123/premium-spread/issues/148 · 관련: #129(관측, V24) · #139(레그, V28) · #130(정산)

## 배경

`account_pnl_observation` 과 레그 테이블 두 개는 매분 최대 1행씩 쌓인다(테이블당 연 약 52만 행). 보존 정책이 없다.
이 작업은 보존 기간을 넘긴 관측 행과 그 레그 행을 주기적으로 지운다. 브레인스토밍 질의는 이슈의 조사·제약으로 대신했고,
보존 기간 기본값만 계약 승인 때 묻는다.

## 현재 구조에서 지켜야 하는 것

- **정산(#130)이 관측을 읽는다.** `AccountRealizedPnlJob` 은 사건 시각의 환율을 `findFxAt(occurredAt, 10분)` 으로
  `account_pnl_observation` 에서 찾는다. 정산 대상은 하한이 `realized-pnl.max-age`(기본 90d) 이내인 건뿐이므로
  필요한 관측 시각은 항상 `now − max-age − 10분` 보다 늦다.
- **레그 → 관측 FK.** 레그 행을 먼저 지워야 관측 삭제가 실패하지 않는다.
- **배치 Job 실행 계약.** `JobExecutor` 의 lock·lease·timeout 을 쓴다(`.ai/rules/batch.md`). Scheduler 는 Job 한 번 호출만 한다.

## 결정

### D1. 대상 테이블

- `account_pnl_observation`(HOLDING·FLAT 모두), `account_foreign_leg_observation`, `account_korea_leg_observation`.
- 건·사건(`account_position_episode`·`account_position_event`)·정산 열은 지우지 않는다. 행 수가 작고 정산·기준 포지션(#131)이 읽는다.

### D2. 기준 시각과 보존 기간

- 삭제 조건: `observed_at < now − retention`. 시각은 주입한 `Clock` 이다.
- 설정 `account-pnl-monitor.position-history.retention.*`:
  - `enabled` (기본 true)
  - `retention` (기본값 = 계약 승인 결정. 후보 180d)
  - `batch-size` (기본 1000, 1~10000)
  - `max-batches-per-run` (기본 50, 1~10000)
  - `interval` (기본 1h, Scheduler `fixedDelay`)
- **시작 검증:** `retention` 이 `account-pnl-monitor.realized-pnl.max-age`(바인딩 안 됐으면 기본 90d) + 1일 이하이면 batch 가 시작하지 않는다
  (`IllegalStateException`, 메시지에 두 설정 키 이름만). 1일 여유는 환율 조회 허용 10분과 시계 오차를 덮는다.
  정산을 꺼 둔 경우에도 같은 검증을 한다 — 다시 켜는 순간 근거가 사라져 있으면 안 되기 때문이다.
  `AccountRealizedPnlProperties` bean 은 모니터·정산 조건을 통과할 때만 등록되므로 **그 bean 에 의존하지 않는다.**
  retention Configuration 이 `Binder.get(environment).bind("account-pnl-monitor.realized-pnl.max-age", Duration::class.java).orElse(90d)` 로
  직접 읽는다(모니터·정산 on/off 와 무관하게 사용자가 넣은 값을 본다).
- **기본 보존 기간은 `{RETENTION_DEFAULT}` 로 고정한다**(계약 승인 결정). 환경 변수 `ACCOUNT_PNL_OBSERVATION_RETENTION` 으로 바꾼다.
  나머지 설정의 환경 변수: `ACCOUNT_PNL_OBSERVATION_RETENTION_ENABLED`, `..._BATCH_SIZE`, `..._MAX_BATCHES_PER_RUN`, `..._INTERVAL`.

### D3. 삭제 알고리즘 — 배치 1회 = 트랜잭션 1개

1. `SELECT id, episode_id, observed_at FROM account_pnl_observation WHERE observed_at < ? ORDER BY observed_at, id LIMIT ?`
   (`ix_account_pnl_observation_observed_at` 사용)
2. 고른 관측의 키로 두 레그 테이블 행 DELETE (`(episode_id, observed_at) IN ((?, ?), …)`, PK 사용)
3. 고른 `id` 로 관측 DELETE
4. 커밋. 반환값 = 지운 관측 행 수.
- 레그 행은 관측과 같은 트랜잭션에서 먼저 지우므로 FK 위반도, 관측 없는 레그 행도 생기지 않는다.
- 모니터는 최근 시각 행만 INSERT 하므로 삭제 범위와 겹치지 않는다. 별도 행 잠금(`FOR UPDATE`)은 두지 않는다.

### D4. Job — `AccountObservationRetentionJob`

- `JobExecutor.execute(jobConfigs.get(JobId.ACCOUNT_OBSERVATION_RETENTION)) { … }` 안에서 배치를 최대 `max-batches-per-run` 번 반복한다.
  배치가 `batch-size` 보다 적게 지우면 멈춘다. 상한에 닿으면 WARN(지운 수·상한만) 하고 다음 회차에 이어 간다.
- 배치 사이에 interrupt 를 확인한다(timeout·lock 상실 취소 시 다음 배치를 시작하지 않는다).
- 결과: 성공이면 `JobResult.Success`, store 예외면 `JobResult.Failure`(예외 클래스명만 로그).
- lock: `batch.jobs.account-observation-retention` — 기본 `lock:account-pnl:observation-retention`, lease 150s, timeout 120s.

### D5. 배선

- domain: `JobId.ACCOUNT_OBSERVATION_RETENTION("account_observation_retention")`, port `AccountObservationRetentionStore { fun deleteObservedBefore(cutoff: Instant, limit: Int): Int }`.
- infrastructure:common: `JdbcAccountObservationRetentionStore`(@Repository, 자체 `TransactionTemplate`).
- apps:batch: Job, `AccountObservationRetentionScheduler`(`@ConditionalOnBatchScheduling` + `retention.enabled`), Configuration·Properties, `BatchJobProperties` lock, `application.yml`.
- 모니터 on/off(`account-pnl-monitor.enabled`)와 무관하게 동작한다 — 모니터를 꺼도 이미 쌓인 행은 정리돼야 한다.

### D6. 관측성

- INFO 로그 한 줄: 지운 관측 행 수, 배치 수. 값(가격·금액)은 로그에 없다.
- `JobExecutor` 가 기존 Job 처럼 outcome metric·last-success 를 남긴다.

## 범위 밖

- 시간 단위 다운샘플링 보관, 건·사건·정산 데이터 정리, premium·ticker 시계열 정리, 조회 API.

## codex 스펙 리뷰 (1라운드)

- ISSUE-1 정산 비활성 시 max-age 출처 → 수용. bean 대신 `Binder` 로 직접 바인딩(D2), AC4 에 모니터·정산 꺼짐 조합 추가.
- ISSUE-2 기본 보존 기간 미확정 → 수용. 계약 승인 때 고정하고 AC6(설정 없을 때 기본값 cutoff) 추가.

## 위험

- 첫 배포 시 쌓인 행이 많으면 여러 회차에 걸쳐 지운다(회차당 최대 5만 행 = 1000 × 50). 로컬·운영 모두 현재 행 수가 보존 기간 안이라 첫 회차에 지울 것이 없다.
- 보존 기간을 실수로 짧게 넣으면 데이터가 사라진다 — D2 시작 검증이 정산 근거만큼은 지킨다. 그 밖의 하한은 두지 않는다.
