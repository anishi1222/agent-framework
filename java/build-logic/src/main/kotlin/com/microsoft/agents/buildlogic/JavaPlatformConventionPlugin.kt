// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata

class JavaPlatformConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("java-platform")
            pluginManager.apply("maven-publish")

            group = "com.microsoft.agents"
            version = providers.gradleProperty("frameworkVersion").getOrElse("0.1.0-SNAPSHOT")

            extensions.configure(PublishingExtension::class.java) {
                publications {
                    create("mavenBom", MavenPublication::class.java) {
                        from(components.getByName("javaPlatform"))
                        pom {
                            name.set("Microsoft Agent Framework Java BOM")
                            description.set(
                                "Dependency constraints for Microsoft Agent Framework Java artifacts.",
                            )
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

            val pomTask =
                tasks.named(
                    "generatePomFileForMavenBomPublication",
                    GenerateMavenPom::class.java,
                )
            val moduleMetadataTask =
                tasks.named(
                    "generateMetadataFileForMavenBomPublication",
                    GenerateModuleMetadata::class.java,
                )
            val metadataCheck =
                tasks.register("checkBomPublicationMetadata") {
                    group = "verification"
                    description =
                        "Checks that generated BOM metadata exactly constrains every published Java module."
                    dependsOn(pomTask, moduleMetadataTask)
                    inputs.file(pomTask.map { task -> task.destination })
                    inputs.file(moduleMetadataTask.flatMap { task -> task.outputFile })

                    doLast {
                        val expectedModules = JavaModulePolicies.publishedModules(rootProject)
                        val generatedMetadata =
                            mapOf(
                                "Maven POM" to pomTask.get().destination,
                                "Gradle module metadata" to
                                    moduleMetadataTask.get().outputFile.get().asFile,
                            )
                        generatedMetadata.forEach { (format, file) ->
                            if (!file.isFile) {
                                throw GradleException(
                                    "$format was not generated at ${file.absolutePath}.",
                                )
                            }
                            if (file.readText().contains(CONFORMANCE_MODULE)) {
                                throw GradleException(
                                    "$format must not reference $CONFORMANCE_MODULE.",
                                )
                            }
                            val text = file.readText()
                            val actualModules =
                                when (format) {
                                    "Maven POM" ->
                                        Regex("""<artifactId>([^<]+)</artifactId>""")
                                            .findAll(text)
                                            .map { match -> match.groupValues[1] }
                                            .filterNot { artifactId -> artifactId == project.name }
                                            .toSortedSet()
                                    else ->
                                        Regex(""""module"\s*:\s*"([^"]+)"""")
                                            .findAll(text)
                                            .map { match -> match.groupValues[1] }
                                            .filterNot { module -> module == project.name }
                                            .toSortedSet()
                                }
                            val missing = expectedModules - actualModules
                            val extra = actualModules - expectedModules
                            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                                throw GradleException(
                                    "$format BOM constraints must exactly match published Java modules; " +
                                        "missing=${missing.sorted()}, extra=${extra.sorted()}.",
                                )
                            }
                        }
                    }
                }
            tasks.named("check") {
                dependsOn(metadataCheck)
            }

            configurePublicationGates()
        }
    }

    private companion object {
        const val CONFORMANCE_MODULE = JavaModulePolicies.TEST_SUPPORT_MODULE
    }
}
