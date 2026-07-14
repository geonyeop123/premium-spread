package io.premiumspread.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.gradle.testing.jacoco.tasks.JacocoReport;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;
import org.jlleitschuh.gradle.ktlint.KtlintExtension;

public final class KotlinLibraryConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("org.jetbrains.kotlin.jvm");
        project.getPluginManager().apply("jacoco");
        project.getPluginManager().apply("org.jlleitschuh.gradle.ktlint");

        project.getExtensions().configure(JacocoPluginExtension.class, jacoco ->
            jacoco.setToolVersion(project.getProviders().gradleProperty("jacocoVersion").get())
        );

        project.getExtensions().configure(JavaPluginExtension.class, java ->
            java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(21))
        );
        project.getExtensions().configure(KotlinJvmProjectExtension.class, kotlin -> {
            kotlin.jvmToolchain(21);
            kotlin.getCompilerOptions().getFreeCompilerArgs().add("-Xjsr305=strict");
        });

        project.getTasks().withType(Test.class).configureEach(test -> {
            test.setMaxParallelForks(1);
            test.useJUnitPlatform();
            test.systemProperty("api.version", "1.44");
            test.systemProperty("user.timezone", "Asia/Seoul");
            test.systemProperty("spring.profiles.active", "test");
            test.jvmArgs("-Xshare:off");
        });

        project.getTasks().withType(JacocoReport.class).configureEach(report -> {
            report.mustRunAfter(project.getTasks().withType(Test.class));
            report.getExecutionData().setFrom(
                project.fileTree(project.getLayout().getBuildDirectory()).include("jacoco/*.exec")
            );
            report.getReports().getXml().getRequired().set(true);
            report.getReports().getCsv().getRequired().set(false);
            report.getReports().getHtml().getRequired().set(false);
        });

        project.getExtensions().configure(KtlintExtension.class, ktlint ->
            ktlint.getVersion().set(project.getProviders().gradleProperty("ktLintVersion"))
        );
    }
}
