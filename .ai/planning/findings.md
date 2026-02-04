# Findings: Redis ZSet 쓰기연산 최적화

## Requirements
- ZSet을 활용하여 초당 데이터는 Redis에만 적재
- Key 전략, TTL 전략, 데이터 구성 설계
- 초당 데이터로 분/시간/일 데이터 배치 적재
- 초당 데이터 대상: Ticker, Fx, Premium
- 서머리 데이터: 기준(1분, 10분, 1시간, 1일) 대비 최고점, 최저점, 현재(00초 기준)

### 사용자 결정 사항
| 항목 | 결정 | 비고 |
|------|------|------|
| 초당 데이터 보관 | **5분** | 10분 서머리까지 실시간 계산 가능 |
| 서머리 조회 방식 | **캐시된 서머리** | 별도 캐시 키에 저장, 조회 빠름 |
| DB 저장 단위 | **분 + 시간 + 일** | 세분화된 히스토리 보관 |

## 현재 구조 분석

### 현재 데이터 흐름
```
[외부 API] → [배치 1초] → Redis Hash + DB INSERT
```

### 현재 Redis 저장 방식
| 데이터 | 키 패턴 | 저장 방식 | TTL |
|--------|---------|----------|-----|
| Ticker | ticker:{exchange}:{symbol} | Hash | 5초 |
| FX | fx:{base}:{quote} | Hash | 15분 |
| Premium | premium:{symbol} | Hash | 5초 |
| Premium History | premium:{symbol}:history | ZSet | 1시간 |

### 현재 DB 저장 (문제점)
- `PremiumSnapshotRepository.save()` - **1초마다 INSERT**
- premium_snapshot 테이블에 매초 데이터 적재
- **하루 86,400건** INSERT 발생 → 부하 원인

### 기존 ZSet 활용 (참고)
```kotlin
// PremiumCacheService.saveHistory()
redisTemplate.opsForZSet().add(key, value, score)
// - key: premium:btc:history
// - score: timestamp (epoch millis)
// - value: "premiumRate:koreaPrice:foreignPrice"
```

## 주요 파일 위치
| 역할 | 경로 |
|------|------|
| Redis 키 생성 | modules/redis/.../RedisKeyGenerator.kt |
| Redis TTL | modules/redis/.../RedisTtl.kt |
| Ticker 캐시 | apps/batch/.../cache/TickerCacheService.kt |
| FX 캐시 | apps/batch/.../cache/FxCacheService.kt |
| Premium 캐시 | apps/batch/.../cache/PremiumCacheService.kt |
| Premium 스케줄러 | apps/batch/.../scheduler/PremiumScheduler.kt |
| DB 저장 | apps/batch/.../repository/PremiumSnapshotRepository.kt |

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 초당 데이터 ZSet만 저장 | DB INSERT 부하 제거 |
| 분/시간/일 배치 집계 | 정확한 통계 + DB 부하 분산 |
| 서머리 데이터 별도 캐시 | 차트 조회 성능 최적화 |

---

## ZSet 전략 설계 (Phase 2)

### 1. Key 전략

#### 초당 데이터 (ZSet)
```
ticker:seconds:{exchange}:{symbol}    # ticker:seconds:bithumb:btc
fx:seconds:{base}:{quote}             # fx:seconds:usd:krw
premium:seconds:{symbol}              # premium:seconds:btc
```

#### 집계 데이터 (ZSet)
```
ticker:minutes:{exchange}:{symbol}    # 분 집계
ticker:hours:{exchange}:{symbol}      # 시간 집계
premium:minutes:{symbol}
premium:hours:{symbol}
```

#### 서머리 캐시 (Hash)
```
summary:1m:{symbol}     # 최근 1분 서머리
summary:10m:{symbol}    # 최근 10분 서머리
summary:1h:{symbol}     # 최근 1시간 서머리
summary:1d:{symbol}     # 최근 1일 서머리
```

### 2. TTL 전략

| 키 패턴 | TTL | 이유 |
|---------|-----|------|
| *:seconds:* | 5분 | 10분 서머리 실시간 계산 + 여유 |
| *:minutes:* | 2시간 | 시간 집계용 + 1시간 서머리 |
| *:hours:* | 25시간 | 일 집계용 + 여유 |
| summary:1m:* | 10초 | 빈번한 갱신 |
| summary:10m:* | 30초 | 적당한 갱신 |
| summary:1h:* | 1분 | 느린 갱신 |
| summary:1d:* | 5분 | 가장 느린 갱신 |

### 3. 데이터 구조

#### ZSet Member 포맷
```
# Ticker
score: timestamp (epoch millis)
member: "{price}:{volume}"

# Premium
score: timestamp (epoch millis)
member: "{premiumRate}:{koreaPrice}:{foreignPrice}:{fxRate}"
```

#### Summary Hash 필드
```
{
  "high": "3.25",           # 최고점
  "low": "2.80",            # 최저점
  "current": "3.10",        # 현재값 (00초 기준)
  "current_ts": "1707012000000",  # 현재값 타임스탬프
  "updated_at": "1707012005000"   # 갱신 시각
}
```

### 4. 배치 스케줄 설계

```
[1초 스케줄러] - 기존 유지
    └→ ZSet 저장 (DB INSERT 제거)
       ├─ ticker:seconds:*
       └─ premium:seconds:*

[10초 스케줄러] - 신규
    └→ 서머리 캐시 갱신
       ├─ summary:1m:* (최근 60개 데이터 집계)
       ├─ summary:10m:* (최근 600개 데이터 집계)
       └─ summary:1h:* (최근 3600개 or 분 집계 60개)

[1분 스케줄러] - 신규 (정각 실행)
    └→ 분 집계 생성
       ├─ 초당 ZSet에서 high/low/open/close 추출
       ├─ ticker:minutes:* ZSet 추가
       ├─ premium:minutes:* ZSet 추가
       └→ DB INSERT (분 테이블)

[1시간 스케줄러] - 신규 (정시 실행)
    └→ 시간 집계 생성
       ├─ 분 ZSet에서 집계
       ├─ ticker:hours:* ZSet 추가
       ├─ premium:hours:* ZSet 추가
       └→ DB INSERT (시간 테이블)

[1일 스케줄러] - 신규 (자정 실행)
    └→ 일 집계 생성
       ├─ 시간 ZSet에서 집계
       └→ DB INSERT (일 테이블)

[5분 스케줄러] - 신규 (cleanup)
    └→ summary:1d:* 갱신 (시간 집계 기반)
```

### 5. DB 테이블 구조 (신규)

```sql
-- 분 집계 테이블
CREATE TABLE premium_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    minute_at DATETIME NOT NULL,        -- 분 정각 시각
    high DECIMAL(10,4) NOT NULL,        -- 최고 프리미엄
    low DECIMAL(10,4) NOT NULL,         -- 최저 프리미엄
    open DECIMAL(10,4) NOT NULL,        -- 시작 프리미엄
    close DECIMAL(10,4) NOT NULL,       -- 종료 프리미엄
    avg DECIMAL(10,4) NOT NULL,         -- 평균 프리미엄
    count INT NOT NULL,                  -- 데이터 수
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_symbol_minute (symbol, minute_at)
);

-- 시간 집계 테이블
CREATE TABLE premium_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    hour_at DATETIME NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_symbol_hour (symbol, hour_at)
);

-- 일 집계 테이블
CREATE TABLE premium_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    day_at DATE NOT NULL,
    high DECIMAL(10,4) NOT NULL,
    low DECIMAL(10,4) NOT NULL,
    open DECIMAL(10,4) NOT NULL,
    close DECIMAL(10,4) NOT NULL,
    avg DECIMAL(10,4) NOT NULL,
    count INT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_symbol_day (symbol, day_at)
);
```

### 6. 부하 비교

| 항목 | 현재 | 개선 후 |
|------|------|---------|
| 초당 DB INSERT | 1건/초 | 0건 |
| 분당 DB INSERT | 60건 | 1건 |
| 시간당 DB INSERT | 3,600건 | 61건 (60분 + 1시간) |
| 일간 DB INSERT | 86,400건 | 1,465건 (1440분 + 24시간 + 1일) |

**98.3% 감소** (86,400 → 1,465)

## Issues Encountered
| Issue | Resolution |
|-------|------------|
|       |            |

## Resources
- RedisKeyGenerator.kt: 키 패턴 정의
- RedisTtl.kt: TTL 상수 정의
- PremiumCacheService.kt: 기존 ZSet 패턴 참고

---
*Update this file after every 2 view/browser/search operations*
