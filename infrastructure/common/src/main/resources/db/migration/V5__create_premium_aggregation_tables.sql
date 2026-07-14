-- 프리미엄 분 집계 테이블
CREATE TABLE premium_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    minute_at DATETIME NOT NULL COMMENT '분 정각 시각',
    high DECIMAL(10,4) NOT NULL COMMENT '최고 프리미엄',
    low DECIMAL(10,4) NOT NULL COMMENT '최저 프리미엄',
    open DECIMAL(10,4) NOT NULL COMMENT '시작 프리미엄',
    close DECIMAL(10,4) NOT NULL COMMENT '종료 프리미엄',
    avg DECIMAL(10,4) NOT NULL COMMENT '평균 프리미엄',
    count INT NOT NULL COMMENT '데이터 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_minute (symbol, minute_at),
    INDEX idx_minute_at (minute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 프리미엄 시간 집계 테이블
CREATE TABLE premium_hour (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    hour_at DATETIME NOT NULL COMMENT '시간 정각 시각',
    high DECIMAL(10,4) NOT NULL COMMENT '최고 프리미엄',
    low DECIMAL(10,4) NOT NULL COMMENT '최저 프리미엄',
    open DECIMAL(10,4) NOT NULL COMMENT '시작 프리미엄',
    close DECIMAL(10,4) NOT NULL COMMENT '종료 프리미엄',
    avg DECIMAL(10,4) NOT NULL COMMENT '평균 프리미엄',
    count INT NOT NULL COMMENT '데이터 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_hour (symbol, hour_at),
    INDEX idx_hour_at (hour_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 프리미엄 일 집계 테이블
CREATE TABLE premium_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    day_at DATE NOT NULL COMMENT '일자',
    high DECIMAL(10,4) NOT NULL COMMENT '최고 프리미엄',
    low DECIMAL(10,4) NOT NULL COMMENT '최저 프리미엄',
    open DECIMAL(10,4) NOT NULL COMMENT '시작 프리미엄',
    close DECIMAL(10,4) NOT NULL COMMENT '종료 프리미엄',
    avg DECIMAL(10,4) NOT NULL COMMENT '평균 프리미엄',
    count INT NOT NULL COMMENT '데이터 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_day (symbol, day_at),
    INDEX idx_day_at (day_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
