package io.premiumspread.architecture

import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ModuleOutputConsumptionTest {
    @Test
    fun `all architecture target main outputs contain the package under inspection`() {
        assertPackageHasClasses(
            ArchitectureTarget.DOMAIN,
            "io.premiumspread.domain",
            "io.premiumspread.domain.DomainModuleMarker",
        )
        assertPackageHasClasses(
            ArchitectureTarget.INFRASTRUCTURE_COMMON,
            "io.premiumspread.infrastructure.common",
            "io.premiumspread.infrastructure.common.CommonInfrastructureMarker",
        )
        assertPackageHasClasses(
            ArchitectureTarget.INFRASTRUCTURE_API,
            "io.premiumspread.infrastructure.api",
            "io.premiumspread.infrastructure.api.ApiInfrastructureMarker",
        )
        assertPackageHasClasses(
            ArchitectureTarget.INFRASTRUCTURE_BATCH,
            "io.premiumspread.infrastructure.batch",
            "io.premiumspread.infrastructure.batch.BatchInfrastructureMarker",
        )
        assertPackageHasClasses(
            ArchitectureTarget.APPS_API,
            "io.premiumspread.interfaces",
            "io.premiumspread.PremiumSpreadApplication",
        )
        assertPackageHasClasses(
            ArchitectureTarget.APPS_BATCH,
            "io.premiumspread.application",
            "io.premiumspread.PremiumSpreadBatchApplication",
        )
    }

    private fun assertPackageHasClasses(
        target: ArchitectureTarget,
        packagePrefix: String,
        requiredClassName: String,
    ) {
        val importedClasses = target.importClasses()
        val inspectedClassCount = importedClasses.count { it.packageName.startsWith(packagePrefix) }

        assertContains(
            importedClasses.map { it.name },
            requiredClassName,
            "$target must consume its real main output",
        )
        assertTrue(
            inspectedClassCount > 0,
            "$target package guard for $packagePrefix must find at least one class",
        )
    }
}
