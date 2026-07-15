package io.premiumspread.buildlogic;

import java.io.File;
import java.time.Duration;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.gradle.testing.jacoco.tasks.JacocoReport;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;

public final class KotlinLibraryConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("org.jetbrains.kotlin.jvm");
        project.getPluginManager().apply("jacoco");

        project.getDependencies().add(
            "testRuntimeOnly",
            project.files(pluginRuntimeArtifact())
        );

        project.getExtensions().configure(JacocoPluginExtension.class, jacoco ->
            jacoco.setToolVersion(project.getProviders().gradleProperty("jacocoVersion").get())
        );

        project.getExtensions().configure(JavaPluginExtension.class, java ->
            java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(21))
        );
        project.getExtensions().configure(KotlinJvmProjectExtension.class, kotlin -> {
            kotlin.jvmToolchain(21);
            kotlin.getCompilerOptions().getFreeCompilerArgs().add("-Xjsr305=strict");
            kotlin.getCompilerOptions().getAllWarningsAsErrors().set(true);
        });

        project.getTasks().withType(Test.class).configureEach(test -> {
            test.setMaxParallelForks(1);
            test.getTimeout().set(Duration.ofMinutes(
                test.getName().toLowerCase().contains("integration") ? 30 : 15
            ));
            test.useJUnitPlatform();
            test.systemProperty("api.version", "1.44");
            test.systemProperty("user.timezone", "Asia/Seoul");
            test.systemProperty("spring.profiles.active", "test");
            test.systemProperty("testcontainers.reuse.enable", "false");
            test.systemProperty("premiumspread.test.leak-detection", "true");
            test.systemProperty("junit.jupiter.execution.timeout.default", "5m");
            test.systemProperty("junit.jupiter.execution.timeout.lifecycle.method.default", "2m");
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
    }

    private static File pluginRuntimeArtifact() {
        try {
            return new File(
                KotlinLibraryConventionPlugin.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot locate test leak detector runtime artifact", exception);
        }
    }
}
