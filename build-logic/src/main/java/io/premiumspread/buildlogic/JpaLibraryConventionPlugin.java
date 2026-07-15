package io.premiumspread.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Adds Kotlin JPA no-arg semantics on top of the shared Spring library convention. */
public final class JpaLibraryConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("premiumspread.spring-library");
        project.getPluginManager().apply("org.jetbrains.kotlin.plugin.jpa");
    }
}
