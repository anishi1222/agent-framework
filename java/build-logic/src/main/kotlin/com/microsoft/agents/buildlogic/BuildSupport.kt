// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

internal object BuildVersions {
    const val ASSERTJ = "3.27.7"
    const val CHECKSTYLE = "13.9.0"
    const val JACOCO = "0.8.15"
    const val JUNIT = "5.14.4"
    const val MOCKITO = "5.23.0"
    const val PALANTIR_JAVA_FORMAT = "2.80.0"
}

internal fun Project.catalogVersion(alias: String, fallback: String): String =
    extensions
        .findByType(VersionCatalogsExtension::class.java)
        ?.named("libs")
        ?.findVersion(alias)
        ?.orElse(null)
        ?.requiredVersion
        ?: fallback

internal fun Project.configurePublicationGates() {
    val qualityGate = tasks.named("check")
    val architectureGate = rootProject.tasks.matching { it.name == "checkArchitecture" }

    tasks.withType(PublishToMavenRepository::class.java).configureEach {
        dependsOn(qualityGate)
        dependsOn(architectureGate)
    }
    tasks.withType(PublishToMavenLocal::class.java).configureEach {
        dependsOn(qualityGate)
        dependsOn(architectureGate)
    }
}
