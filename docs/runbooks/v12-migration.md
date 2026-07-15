# V12 Position Migration Runbook

V12는 `position`을 `TRUNCATE`한 뒤 pair 컬럼으로 바꾸므로 일반 배포에서 자동 승인하지 않는다.
현재 운영/스테이징 환경은 없고 상태는 `NOT_DEPLOYED`, 로컬은 `APPLIED`다. 이 문서는 향후 새 환경을
위한 preflight/backfill/cutover 절차다. migration file/checksum은 immutable이며 backfill은 V12 파일을
수정하는 방식이 아니라 검증된 backup과 승인된 mapping으로 별도 수행한다.

## 상태 판정

`docker/preflight-v12.sh`를 API/Batch보다 먼저 실행한다.

- `APPLIED`: 그대로 배포한다. V12 파일/checksum은 수정하지 않는다.
- `PENDING_EMPTY`: 변경 승인을 받은 단 한 번의 실행에만 `MIGRATION_V12_ALLOW_EMPTY=true`를 설정한다.
- `PENDING_WITH_DATA`: 일반 배포를 중단하고 아래 보존 절차를 수행한다.
- `NOT_DEPLOYED`: 배포 대상이 없다는 운영 결정을 기록한다.

## PENDING_EMPTY

1. `position` row가 0인지 read-only 계정으로 재확인한다.
2. 배포 change/ticket에 승인자와 시간을 기록한다.
3. `.env` 파일은 false로 유지하고 해당 실행에만
   `MIGRATION_V12_ALLOW_EMPTY=true ./deploy/deploy.sh`처럼 process env로 전달한다.
4. Flyway V12 success/checksum `-1352556376`와 API readiness를 확인한다.
5. 즉시 플래그를 제거하고 재기동해 `APPLIED` 경로로 통과하는지 확인한다.

## PENDING_WITH_DATA

1. API/Batch replica를 0으로 만들고 DB write를 차단한다.
2. 전체 DB physical backup과 `position` logical dump를 만들고 별도 복원 환경에서 restore rehearsal을 완료한다.
3. row count, PK min/max, PK 목록 SHA-256, 핵심 금액/수량 합계를 원본·backup 양쪽에서 기록한다.
4. 별도 backup table 또는 별도 DB에 기존 position을 보존한다. 보존 검증 전 원본을 변경하지 않는다.
5. 기존 단일 exchange/quantity/entry_price를 어느 한국/해외 pair로 매핑할지 업무 승인을 받는다. 추측값을
   사용하지 않는다.
6. 검증된 backup이 있을 때만 원본 `position`을 비우고, 격리된 migration runner에서 한 번만
   `MIGRATION_V12_ALLOW_EMPTY=true`로 V12를 실행한다. 정상 API/Batch traffic은 아직 열지 않는다.
7. 승인된 mapping으로 backup에서 새 korea/foreign 컬럼을 backfill한다. row count, PK 목록 SHA-256,
   합계, FK, NOT NULL, sample PnL을 원본 기록과 대조한다.
8. 불일치하면 traffic을 열지 말고 전체 DB backup으로 복원한다. 일치하면 V12 Flyway checksum과
   readiness를 확인한 뒤 API, 마지막으로 Batch를 기동한다.
9. 승인 플래그를 제거한다. backup table/dump는 retention 만료와 운영 승인 전 삭제하지 않는다.

## Cutover 완료 조건

- V12 Flyway checksum이 `-1352556376`이고 migration history가 success다.
- 승인 전/backup/backfill 후 row count와 PK 목록 SHA-256이 일치한다.
- 수량·진입가·환율 합계, FK/NOT NULL, 한국/해외 거래소 region과 sample PnL이 승인 기준과 일치한다.
- API readiness 후에만 Batch를 시작한다.
- `MIGRATION_V12_ALLOW_EMPTY`가 제거된 재기동도 `APPLIED`로 통과한다.
- backup 위치, 복원 rehearsal, 승인자, 실행 SHA와 검증 결과가 change record에 남는다.

수동 `flyway_schema_history` insert/repair로 V12를 건너뛰지 않는다. 실행 로그, backup 위치, checksum,
row-count 결과와 승인자를 change record에 남긴다.
