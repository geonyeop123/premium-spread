package io.premiumspread.domain.tracking

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * DB 는 legacy 표현(OPEN/CLOSED)을 유지하고 domain 과 API 는 정렬된 표현(ACTIVE/ARCHIVED)을 쓴다.
 *
 * 기존 컬럼 값을 재작성하면 이전 application image 가 `valueOf` 에서 실패해 롤백이 불가능해진다.
 * `docs/runbooks/deployment.md` "Rollback 제약"이 forward migration 의 이전 image 호환을 요구하므로,
 * 값 변환은 migration 이 아니라 이 converter 한 곳이 담당한다.
 * 저장값 리터럴은 이 파일과 Flyway migration 밖에 나타나지 않는다.
 */
@Converter(autoApply = false)
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
