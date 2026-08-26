package io.premiumspread.domain.tracking

import jakarta.persistence.AttributeConverter

/**
 * DB 는 legacy 표현(OPEN/CLOSED)을 유지하고 domain 과 API 는 정렬된 표현(ACTIVE/ARCHIVED)을 쓴다.
 *
 * 기존 컬럼 값을 재작성하면 이전 application image 가 `valueOf` 에서 실패해 롤백이 불가능해진다.
 * `docs/runbooks/deployment.md` "Rollback 제약"이 forward migration 의 이전 image 호환을 요구하므로,
 * 값 변환은 migration 이 아니라 이 converter 한 곳이 담당한다.
 * 저장값 리터럴은 이 파일과 Flyway migration 밖에 나타나지 않는다.
 *
 * `@Converter` 를 붙이지 않는다. `@EntityScan` 이 domain 패키지를 훑으면서 이 클래스를 주워
 * AttributeConverterManager 에 등록하고, 엔티티의 `@Convert(converter = ...)` 가 다시 등록해
 * "registered multiple times" 로 EntityManagerFactory 생성이 실패한다.
 * 명시 참조 하나만 남긴다.
 */
class TrackingStatusConverter : AttributeConverter<TrackingStatus, String> {

    override fun convertToDatabaseColumn(attribute: TrackingStatus): String = when (attribute) {
        TrackingStatus.ACTIVE -> "OPEN"
        TrackingStatus.ARCHIVED -> "CLOSED"
    }

    override fun convertToEntityAttribute(dbData: String): TrackingStatus = when (dbData) {
        "OPEN" -> TrackingStatus.ACTIVE
        "CLOSED" -> TrackingStatus.ARCHIVED
        else -> throw IllegalStateException("Unknown tracking status in database: $dbData")
    }
}
