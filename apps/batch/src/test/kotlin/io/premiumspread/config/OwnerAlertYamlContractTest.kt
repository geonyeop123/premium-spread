package io.premiumspread.config

import io.premiumspread.domain.owneralert.OwnerAlertKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource

/** application.yml 의 종류별 route env 가 OwnerAlertKind 와 1:1 인지 고정한다 (dod AC9-h). */
class OwnerAlertYamlContractTest {

    private val document: EnumerablePropertySource<*> =
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).first()
            as EnumerablePropertySource<*>

    @Test
    fun `routes 하위 key 집합은 OwnerAlertKind 와 같다`() {
        val keys = document.propertyNames
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX).substringBefore('.') }
            .toSet()

        assertThat(keys).containsExactlyInAnyOrderElementsOf(OwnerAlertKind.entries.map { it.key })
    }

    @Test
    fun `각 종류의 email·discord·push 는 종류별 env 로 기본값 없이 연결된다`() {
        OwnerAlertKind.entries.forEach { kind ->
            listOf("email", "discord", "push").forEach { field ->
                val name = "$PREFIX${kind.key}.$field"
                assertThat(document.getProperty(name)?.toString()).`as`(name)
                    .isEqualTo("\${OWNER_ALERT_${kind.name}_${field.uppercase()}:}")
            }
        }
    }

    private companion object {
        const val PREFIX = "owner-alert.routes."
    }
}
