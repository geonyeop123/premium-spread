package io.premiumspread.domain

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import java.time.Instant
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate

/**
 * JPA 영속 identity를 공유하는 엔티티 기반 클래스다.
 *
 * 영속화 전 엔티티(id=0)는 같은 인스턴스만 같고, 영속화 후에는 동일 concrete class와 id를 기준으로 같다.
 * hashCode는 영속화 전후 변하지 않도록 concrete class 기반 상수를 사용한다.
 */
@MappedSuperclass
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
        protected set

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
        protected set

    open fun guard() = Unit

    @PrePersist
    @PreUpdate
    private fun validateBeforeWrite() = guard()

    fun delete(now: Instant) {
        deletedAt = deletedAt ?: now
    }

    fun restore() {
        deletedAt = null
    }

    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BaseEntity || persistenceType() != other.persistenceType()) return false
        return id != 0L && id == other.id
    }

    final override fun hashCode(): Int = persistenceType().hashCode()

    private fun persistenceType(): Class<*> {
        var type: Class<*> = javaClass
        while (type.superclass != BaseEntity::class.java) {
            type = type.superclass
        }
        return type
    }
}
