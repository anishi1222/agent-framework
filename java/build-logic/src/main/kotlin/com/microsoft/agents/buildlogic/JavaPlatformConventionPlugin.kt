// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

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

            configurePublicationGates()
        }
    }
}
