// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

class JavaLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configureJavaLibraryConvention(published = true)
    }
}

internal fun Project.configureJavaLibraryConvention(published: Boolean) {
    with(this) {
            pluginManager.apply("java-library")
            pluginManager.apply("checkstyle")
            pluginManager.apply("jacoco")
            pluginManager.apply("com.diffplug.spotless")
            if (published) {
                pluginManager.apply("maven-publish")
            }

            group = "com.microsoft.agents"
            version = providers.gradleProperty("frameworkVersion").getOrElse("0.1.0-SNAPSHOT")

            extensions.configure(JavaPluginExtension::class.java) {
                toolchain.languageVersion.set(JavaLanguageVersion.of(25))
                withJavadocJar()
                withSourcesJar()
            }

            tasks.withType(JavaCompile::class.java).configureEach {
                options.release.set(25)
                options.encoding = "UTF-8"
                options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
            }

            tasks.withType(Javadoc::class.java).configureEach {
                options.encoding = "UTF-8"
                (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
                onlyIf("the module contains a public or protected API type") {
                    source.files.any { sourceFile ->
                        Regex(
                            """(?m)^\s*(public|protected)\s+""" +
                                """(class|interface|record|enum|@interface)\s+""",
                        ).containsMatchIn(sourceFile.readText())
                    }
                }
            }

            dependencies.add(
                "testImplementation",
                dependencies.platform(
                    "org.junit:junit-bom:${catalogVersion("junit", BuildVersions.JUNIT)}",
                ),
            )
            dependencies.add(
                "testImplementation",
                "org.junit.jupiter:junit-jupiter",
            )
            dependencies.add(
                "testImplementation",
                "org.assertj:assertj-core:${catalogVersion("assertj", BuildVersions.ASSERTJ)}",
            )
            dependencies.add(
                "testImplementation",
                "org.mockito:mockito-core:${catalogVersion("mockito", BuildVersions.MOCKITO)}",
            )
            dependencies.add(
                "testRuntimeOnly",
                "org.junit.jupiter:junit-jupiter-engine",
            )
            dependencies.add(
                "testRuntimeOnly",
                "org.junit.platform:junit-platform-launcher",
            )

            tasks.withType(Test::class.java).configureEach {
                useJUnitPlatform()
                testLogging {
                    events("failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }

            extensions.configure(CheckstyleExtension::class.java) {
                toolVersion = catalogVersion("checkstyle", BuildVersions.CHECKSTYLE)
                configFile = rootProject.file("config/checkstyle/checkstyle.xml")
                isIgnoreFailures = false
                maxWarnings = 0
            }

            extensions.configure(JacocoPluginExtension::class.java) {
                toolVersion = catalogVersion("jacoco", BuildVersions.JACOCO)
            }

            tasks.withType(JacocoReport::class.java).configureEach {
                dependsOn(tasks.withType(Test::class.java))
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                    csv.required.set(false)
                }
            }

            extensions.configure(SpotlessExtension::class.java) {
                java {
                    target("src/**/*.java")
                    palantirJavaFormat(
                        catalogVersion("palantir-java-format", BuildVersions.PALANTIR_JAVA_FORMAT),
                    )
                    licenseHeader("// Copyright (c) Microsoft. All rights reserved.\n\n")
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }

            tasks.named("check") {
                dependsOn(tasks.named("spotlessCheck"))
                dependsOn(tasks.withType(JacocoReport::class.java))
            }

            if (published) {
                extensions.configure(PublishingExtension::class.java) {
                    publications {
                        create("mavenJava", MavenPublication::class.java) {
                            from(components.getByName("java"))
                            pom {
                                name.set(project.name)
                                description.set(project.description ?: "Microsoft Agent Framework Java module.")
                                url.set("https://github.com/microsoft/agent-framework")
                                licenses {
                                    license {
                                        name.set("MIT License")
                                        url.set("https://opensource.org/license/mit")
                                        distribution.set("repo")
                                    }
                                }
                                scm {
                                    connection.set("scm:git:https://github.com/microsoft/agent-framework.git")
                                    developerConnection.set(
                                        "scm:git:ssh://git@github.com/microsoft/agent-framework.git",
                                    )
                                    url.set("https://github.com/microsoft/agent-framework")
                                }
                            }
                        }
                    }
                    repositories {
                        maven {
                            name = "localTest"
                            url = rootProject.layout.buildDirectory.dir("test-maven-repository").get().asFile.toURI()
                        }
                    }
                }

                configurePublicationGates()
            }
        }
}
