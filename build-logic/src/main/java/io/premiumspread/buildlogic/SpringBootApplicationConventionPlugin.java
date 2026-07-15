package io.premiumspread.buildlogic;

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;
import org.springframework.boot.gradle.tasks.bundling.BootJar;

public final class SpringBootApplicationConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("premiumspread.spring-library");
        project.getPluginManager().apply("org.springframework.boot");
        // The Boot plugin imports its default BOM after the library convention when
        // dependency-management is already present. Re-import the reviewed overrides
        // last so application runtime classpaths cannot silently fall back to BOM defaults.
        project.getExtensions().configure(
            DependencyManagementExtension.class,
            dependencyManagement -> SpringLibraryConventionPlugin.importSpringBootBom(
                project,
                dependencyManagement
            )
        );

        project.getTasks().named("bootJar", BootJar.class).configure(task -> {
            task.setEnabled(true);
            task.getArchiveFileName().set("app.jar");
        });
        TaskProvider<Jar> thinJar = project.getTasks().named("jar", Jar.class);
        thinJar.configure(task -> {
            task.setEnabled(true);
            task.getArchiveClassifier().set("plain");
        });

        Configuration architectureElements =
            project.getConfigurations().create("architectureTestElements", configuration -> {
                configuration.setDescription(
                    "Thin application jar consumed by the independent architecture test module"
                );
                configuration.setCanBeConsumed(true);
                configuration.setCanBeResolved(false);
                configuration.attributes(attributes -> {
                    attributes.attribute(
                        Usage.USAGE_ATTRIBUTE,
                        project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME)
                    );
                    attributes.attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.getObjects().named(Category.class, Category.LIBRARY)
                    );
                    attributes.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        project.getObjects().named(LibraryElements.class, LibraryElements.JAR)
                    );
                });
            });

        project.getArtifacts().add(architectureElements.getName(), thinJar);
    }
}
