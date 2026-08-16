// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConventionPluginsTest {
    @TempDir
    lateinit var projectDirectory: java.io.File

    @Test
    fun `java library convention fixes the Java language level and publication path`() {
        val project =
            ProjectBuilder.builder()
                .withProjectDir(projectDirectory)
                .withName("agent-framework-test")
                .build()
        project.file("config/checkstyle").mkdirs()
        project.file("config/checkstyle/checkstyle.xml").writeText("<module name=\"Checker\"/>")

        project.pluginManager.apply(JavaLibraryConventionPlugin::class.java)

        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val compileJava = project.tasks.named("compileJava", JavaCompile::class.java).get()
        assertThat(java.toolchain.languageVersion.get()).isEqualTo(JavaLanguageVersion.of(25))
        assertThat(compileJava.options.release.get()).isEqualTo(25)
        assertThat(compileJava.options.compilerArgs).doesNotContain("--enable-" + "preview")
        assertThat(project.tasks.names).contains("publishAllPublicationsToLocalTestRepository")
        assertThat(project.tasks.names).contains("publishAllPublicationsToReleaseRepository")
        assertThat(project.tasks.names).contains("signMavenJavaPublication")
        assertThat(project.tasks.names).contains("writeRuntimeDependencyReport")
        assertThat(project.pluginManager.hasPlugin("signing")).isTrue()
    }

    @Test
    fun `java test support convention keeps quality gates without publication`() {
        val project =
            ProjectBuilder.builder()
                .withProjectDir(projectDirectory)
                .withName("agent-framework-conformance")
                .build()
        project.file("config/checkstyle").mkdirs()
        project.file("config/checkstyle/checkstyle.xml").writeText("<module name=\"Checker\"/>")

        project.pluginManager.apply(JavaTestSupportConventionPlugin::class.java)

        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        assertThat(java.toolchain.languageVersion.get()).isEqualTo(JavaLanguageVersion.of(25))
        assertThat(project.pluginManager.hasPlugin("maven-publish")).isFalse()
        assertThat(project.extensions.findByType(PublishingExtension::class.java)).isNull()
        assertThat(project.tasks.names).doesNotContain("publishAllPublicationsToLocalTestRepository")
    }

    @Test
    fun `architecture convention registers the repository quality gate`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        project.pluginManager.apply("base")

        project.pluginManager.apply(ArchitectureConventionPlugin::class.java)

        assertThat(project.tasks.names).contains("checkArchitecture")
        assertThat(project.tasks.named("check").get().taskDependencies.getDependencies(null))
            .extracting<String> { it.name }
            .contains("checkArchitecture")
    }
}
