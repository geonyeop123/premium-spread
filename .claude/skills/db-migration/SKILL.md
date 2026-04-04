---
name: db-migration
description: "Flyway DB 마이그레이션 생성 스킬. 새 Entity, 컬럼, 인덱스 추가 시 마이그레이션 SQL을 생성한다. 기존 V1~V9+ 패턴 참조, 버전 번호 자동 결정, Entity↔SQL 정합성 검증을 수행한다. 'Entity 추가', '테이블 생성', '컬럼 추가', '인덱스', 'DB 스키마 변경', 'Flyway 마이그레이션' 요청 시 반드시 이 스킬을 사용할 것."
---

# DB Migration — Flyway 마이그레이션 생성 가이드

Entity 변경에 맞는 Flyway 마이그레이션 SQL을 생성하는 절차 가이드.

## 마이그레이션 경로

```
apps/api/src/main/resources/db/migration/V{N}__{description}.sql
```

## 생성 절차

### 1. 현재 버전 확인

```bash
ls apps/api/src/main/resources/db/migration/ | sort -V | tail -1
```

마지막 버전 번호 + 1을 새 버전으로 사용한다. origin 브랜치에도 확인하여 충돌을 방지한다:

```bash
git ls-tree -r origin/feature/premium --name-only | grep "db/migration" | sort -V | tail -1
```

### 2. Entity 변경 분석

implementer가 변경한 Entity 코드를 읽고 SQL로 변환한다.

**매핑 테이블:**

| Kotlin/JPA | MySQL |
|------------|-------|
| `String` | `VARCHAR(255)` |
| `@Column(length = N)` | `VARCHAR(N)` |
| `Long` | `BIGINT` |
| `Int` | `INT` |
| `BigDecimal` | `DECIMAL(19,8)` (금융 데이터 — 프리미엄/가격) |
| `Boolean` | `TINYINT(1)` |
| `LocalDateTime` | `DATETIME(6)` |
| `@Enumerated(STRING)` | `VARCHAR(50)` |
| `@Id @GeneratedValue(IDENTITY)` | `BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` |
| `BaseEntity` 상속 | `created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL` |
| `@Column(nullable = false)` | `NOT NULL` |
| `@Column(unique = true)` | `UNIQUE` |

### 3. 마이그레이션 SQL 작성

#### 테이블 생성
```sql
CREATE TABLE {table_name} (
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 비즈니스 컬럼
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

#### 컬럼 추가
```sql
ALTER TABLE {table_name}
    ADD COLUMN {column} {TYPE} {NULLABLE} {DEFAULT};
```

NOT NULL 컬럼 추가 시 반드시 DEFAULT를 지정하거나 기존 데이터 처리 방안을 포함한다.

#### 인덱스 추가
```sql
CREATE INDEX idx_{table}_{column} ON {table_name} ({column});
```

#### 외래키 추가
```sql
ALTER TABLE {table_name}
    ADD COLUMN {fk_column} BIGINT,
    ADD CONSTRAINT fk_{table}_{ref} FOREIGN KEY ({fk_column}) REFERENCES {ref_table}(id);
```

### 4. 정합성 검증

생성한 SQL과 Entity 코드를 교차 확인:
- [ ] Entity의 모든 `@Column` 필드가 SQL에 포함되었는가
- [ ] nullable 설정이 일치하는가
- [ ] 타입 매핑이 올바른가
- [ ] BaseEntity 컬럼(created_at, updated_at)이 포함되었는가
- [ ] 기존 마이그레이션과 충돌이 없는가

### 5. 안전성 확인

| 위험 요소 | 확인 사항 |
|-----------|----------|
| 데이터 손실 | DROP COLUMN, DROP TABLE 사용 시 경고 |
| NOT NULL 추가 | 기존 데이터에 NULL이 있으면 실패 — DEFAULT 필요 |
| 컬럼 타입 변경 | 기존 데이터 호환성 확인 |
| 대용량 테이블 ALTER | 잠금 시간 고려, 필요 시 pt-online-schema-change 권장 |

## 파일명 규칙

```
V{N}__create_{table}_table.sql          # 테이블 생성
V{N}__add_{column}_to_{table}.sql       # 컬럼 추가
V{N}__add_indexes_to_{table}.sql        # 인덱스 추가
V{N}__add_{fk}_to_{table}.sql           # 외래키 추가
V{N}__{descriptive_name}.sql            # 기타 (복합 변경)
```

description은 snake_case, 간결하게 변경 내용을 설명한다.
