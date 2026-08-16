// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.SigningExtension

internal object BuildVersions {
    const val ASSERTJ = "3.27.7"
    const val CHECKSTYLE = "13.9.0"
    const val JACOCO = "0.8.15"
    const val JUNIT = "5.14.4"
    const val MOCKITO = "5.23.0"
    const val PALANTIR_JAVA_FORMAT = "2.97.0"
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

internal fun Project.configureReleasePublication() {
    val releaseRepositoryUrl =
        providers
            .gradleProperty("releaseRepositoryUrl")
            .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_URL"))
            .getOrElse(
                rootProject.layout.buildDirectory
                    .dir("release-maven-repository")
                    .get()
                    .asFile
                    .toURI()
                    .toString(),
            )
    val releaseRepositoryUsername =
        providers
            .gradleProperty("releaseRepositoryUsername")
            .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_USERNAME"))
            .orNull
    val releaseRepositoryPassword =
        providers
            .gradleProperty("releaseRepositoryPassword")
            .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_PASSWORD"))
            .orNull

    extensions.configure(PublishingExtension::class.java) {
        repositories {
            maven {
                name = "release"
                url = uri(releaseRepositoryUrl)
                if (releaseRepositoryUsername != null || releaseRepositoryPassword != null) {
                    credentials(PasswordCredentials::class.java) {
                        username = releaseRepositoryUsername
                        password = releaseRepositoryPassword
                    }
                }
            }
        }
    }

    pluginManager.apply("signing")
    val signingKey =
        providers
            .gradleProperty("signingKey")
            .orElse(providers.environmentVariable("MAVEN_SIGNING_KEY"))
            .orNull
    val signingPassword =
        providers
            .gradleProperty("signingPassword")
            .orElse(providers.environmentVariable("MAVEN_SIGNING_PASSWORD"))
            .orNull
    val requireSigning =
        providers
            .gradleProperty("requireSigning")
            .map(String::toBoolean)
            .getOrElse(false)
    extensions.configure(SigningExtension::class.java) {
        setRequired(requireSigning)
        if (signingKey != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(extensions.getByType(PublishingExtension::class.java).publications)
    }
}
