-- Read-only V12/timestamp preflight. 비밀값을 파일에 기록하지 않는다.
SELECT VERSION();
SELECT @@global.time_zone, @@session.time_zone, @@system_time_zone;

SELECT installed_rank, version, description, type, script, checksum, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank DESC;

SELECT COUNT(*) AS position_rows FROM position;
SELECT COUNT(*) AS exchange_rate_rows,
       MIN(observed_at) AS min_observed_at,
       MAX(observed_at) AS max_observed_at
FROM exchange_rate;
SELECT COUNT(*) AS premium_minute_rows,
       MIN(minute_at) AS min_minute_at,
       MAX(minute_at) AS max_minute_at
FROM premium_minute;
SELECT COUNT(*) AS ticker_minute_rows,
       MIN(minute_at) AS min_minute_at,
       MAX(minute_at) AS max_minute_at
FROM ticker_minute;
