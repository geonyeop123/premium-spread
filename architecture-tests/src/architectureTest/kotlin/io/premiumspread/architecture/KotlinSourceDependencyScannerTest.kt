package io.premiumspread.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinSourceDependencyScannerTest {
    @Test
    fun `scanner detects alias star const imports and fully qualified references`() {
        val scanner =
            KotlinSourceDependencyScanner(
                forbiddenPackagePrefixes =
                    setOf(
                        "com.fasterxml.jackson",
                        "io.jsonwebtoken",
                        "io.premiumspread.infrastructure",
                        "java.sql",
                        "org.redisson",
                        "retrofit2",
                    ),
            )
        val source =
            """
            package io.premiumspread.interfaces.api.auth

            import io.jsonwebtoken.Jwts as Tokens
            import io.premiumspread.infrastructure.security.LoginSuccessHandler
            import org.redisson.api.*

            private const val COOKIE = LoginSuccessHandler.REFRESH_TOKEN_COOKIE_NAME
            private const val SQL_TYPE = java.sql.Types.VARCHAR
            private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            private val retrofit = retrofit2.Retrofit()
            """.trimIndent()

        assertEquals(
            setOf(
                "interfaces/api/auth/AuthController.kt -> com.fasterxml.jackson.databind.ObjectMapper",
                "interfaces/api/auth/AuthController.kt -> io.jsonwebtoken.Jwts",
                "interfaces/api/auth/AuthController.kt -> io.premiumspread.infrastructure.security.LoginSuccessHandler",
                "interfaces/api/auth/AuthController.kt -> java.sql.Types.VARCHAR",
                "interfaces/api/auth/AuthController.kt -> org.redisson.api.*",
                "interfaces/api/auth/AuthController.kt -> retrofit2.Retrofit",
            ),
            scanner.scan(
                relativePath = "interfaces/api/auth/AuthController.kt",
                source = source,
            ),
        )
    }
}
