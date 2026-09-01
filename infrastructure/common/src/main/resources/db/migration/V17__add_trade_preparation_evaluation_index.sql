-- 거래 준비 계획의 상태/pair 조회 인덱스다. CREATE INDEX 한 문장뿐이라 기존 테이블 정의를
-- 건드리지 않는 append-only 계약과 무충돌이다 (V16은 그대로 둔다).
--
-- 왜 필요한가: 조건 평가 scheduler는 1초 fixed-rate로 `status = 'WATCHING' + pair + deleted_at
-- IS NULL`을 조회하고, reconcile Job은 `status IN ('WATCHING','ARMED')`를 조회한다. V16에는
-- owner_id 인덱스와 active_key 유일 인덱스뿐이라 두 조회가 모두 full table scan이었다.
-- DRAFT·INVALIDATED 행은 삭제되지 않아 테이블이 사실상 append-only로 자라므로 그 비용은
-- 시간이 갈수록 커진다.
--
-- 컬럼 순서: status가 선두다.
--   1. 두 질의가 공유하는 유일한 술어다. 평가는 4컬럼 전부, reconcile은 status 1컬럼 prefix로
--      같은 인덱스를 탄다.
--   2. 선택도도 status뿐이다 — 활성(WATCHING·ARMED) 행은 owner 수로 유계인 반면
--      DRAFT·INVALIDATED는 무한히 늘고, symbol/exchange는 운용 pair가 하나인 현재 사실상 상수다.
--   3. pair 컬럼은 질의의 술어 순서를 그대로 따른다 — 다중 pair 운용 시 인덱스가 pair identity를
--      보존해 scan으로 되돌아가지 않는다.
-- deleted_at은 넣지 않는다. soft delete가 드물어 선택도 기여가 없고, status+pair 등치 뒤 남는
-- 행 수는 이미 매우 적다.
CREATE INDEX idx_trade_preparation_status_pair
    ON trade_preparation (status, symbol, korea_exchange, foreign_exchange);
