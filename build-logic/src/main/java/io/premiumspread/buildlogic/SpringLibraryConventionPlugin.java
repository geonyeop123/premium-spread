package io.premiumspread.buildlogic;

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension;

public final class SpringLibraryConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("premiumspread.kotlin-library");
        project.getPluginManager().apply("org.jetbrains.kotlin.plugin.spring");
        project.getPluginManager().apply("io.spring.dependency-management");

        project.getExtensions().configure(AllOpenExtension.class, allOpen -> {
            allOpen.annotation("jakarta.persistence.Entity");
            allOpen.annotation("jakarta.persistence.MappedSuperclass");
            allOpen.annotation("jakarta.persistence.Embeddable");
        });

        project.getExtensions().configure(DependencyManagementExtension.class, dependencyManagement ->
            dependencyManagement.imports(imports -> {
                imports.mavenBom(
                    "org.springframework.boot:spring-boot-dependencies:"
                        + project.property("springBootVersion"),
                    bom -> {
                        bom.bomProperty(
                            "kotlin.version",
                            project.property("kotlinVersion").toString()
                        );
                        bom.bomProperty(
                            "tomcat.version",
                            project.property("tomcatVersion").toString()
                        );
                        bom.bomProperty(
                            "netty.version",
                            project.property("nettyVersion").toString()
                        );
                        bom.bomProperty(
                            "log4j2.version",
                            project.property("log4j2Version").toString()
                        );
                    }
                );
            })
        );
    }
}
