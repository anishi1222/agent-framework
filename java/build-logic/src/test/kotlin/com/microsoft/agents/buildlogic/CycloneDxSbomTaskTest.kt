// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CycloneDxSbomTaskTest {
    @TempDir
    lateinit var projectDirectory: java.io.File

    @Test
    fun `task emits deterministic resolved components and dependency edges`() {
        val project =
            ProjectBuilder.builder()
                .withProjectDir(projectDirectory)
                .build()
        val report = project.file("runtime-dependencies.tsv")
        report.writeText(
            """
            component	com.fasterxml.jackson.core	jackson-core	2.22.1
            component	com.fasterxml.jackson.core	jackson-databind	2.22.1
            edge	com.microsoft.agents	agent-framework-core	1.2.3	com.fasterxml.jackson.core	jackson-databind	2.22.1
            edge	com.fasterxml.jackson.core	jackson-databind	2.22.1	com.fasterxml.jackson.core	jackson-core	2.22.1
            """.trimIndent(),
        )
        val output = project.file("build/test.cdx.json")
        val task =
            project.tasks
                .register("generateSbom", CycloneDxSbomTask::class.java)
                .get()
        task.frameworkVersion.set("1.2.3")
        task.moduleNames.set(listOf("agent-framework-core"))
        task.dependencyReportFiles.from(report)
        task.outputFile.set(output)

        task.generate()
        val first = output.readText()
        task.generate()

        assertThat(output.readText())
            .isEqualTo(first)
            .contains("\"specVersion\": \"1.6\"")
            .contains("\"name\": \"agent-framework-core\"")
            .contains("\"name\": \"jackson-databind\"")
            .contains("\"version\": \"2.22.1\"")
            .contains("\"dependencies\": [")
            .contains(
                "\"ref\": \"pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.22.1\"",
            )
            .contains(
                "\"dependsOn\": [\"pkg:maven/com.fasterxml.jackson.core/jackson-core@2.22.1\"]",
            )
    }
}
