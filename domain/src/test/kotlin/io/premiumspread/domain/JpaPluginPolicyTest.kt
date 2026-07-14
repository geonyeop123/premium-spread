package io.premiumspread.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@Entity
class JpaPluginProbe(
    @Id val id: Long,
)

class JpaPluginPolicyTest {
    @Test
    fun `JPA entity is open and has a synthetic no-arg constructor`() {
        assertThat(Modifier.isFinal(JpaPluginProbe::class.java.modifiers)).isFalse()
        assertThat(JpaPluginProbe::class.java.getDeclaredConstructor()).isNotNull()
    }
}
