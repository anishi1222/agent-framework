// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ArchitectureConventionPluginFunctionalTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `unknown Java module fails with policy registration guidance`() {
        writeSettings("unregistered-module")
        writeRootBuild()
        writeFile(
            "unregistered-module/build.gradle",
            """
            plugins {
                id 'java-library'
            }
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").buildAndFail()

        assertThat(result.output)
            .contains("No Java architecture policy is registered for project ':unregistered-module'.")
            .contains(
                "Add an entry to MODULE_POLICIES with an explicit project dependency allowlist " +
                    "and expected package root.",
            )
    }

    @Test
    fun `registered Java module passes architecture policy`() {
        writeSettings("agent-framework-core")
        writeRootBuild()
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-core/src/main/java/com/microsoft/agents/core/ValidType.java",
            """
            package com.microsoft.agents.core;

            public final class ValidType {}
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").build()

        assertThat(result.task(":checkArchitecture")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `internal Jackson implementation reference is allowed`() {
        writeJacksonFixture()
        writeSettings("agent-framework-core", repository = "repository")
        writeRootBuild()
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }

            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-databind:2.22.1'
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-core/src/main/java/com/microsoft/agents/core/InternalJackson.java",
            """
            package com.microsoft.agents.core;

            import com.fasterxml.jackson.databind.ObjectMapper;

            public final class InternalJackson {
                private final ObjectMapper mapper = new ObjectMapper();

                public String implementationName() {
                    return mapper.getClass().getName();
                }
            }
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").build()

        assertThat(result.task(":checkArchitecture")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `public Jackson signature is rejected`() {
        writeJacksonFixture()
        writeSettings("agent-framework-core", repository = "repository")
        writeRootBuild()
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }

            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-databind:2.22.1'
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-core/src/main/java/com/microsoft/agents/core/LeakyJackson.java",
            """
            package com.microsoft.agents.core;

            import com.fasterxml.jackson.databind.ObjectMapper;

            public final class LeakyJackson {
                public ObjectMapper mapper() {
                    return new ObjectMapper();
                }
            }
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").buildAndFail()

        assertThat(result.output)
            .contains("compiled public/protected signature")
            .contains("com.fasterxml.jackson.")
            .contains("ObjectMapper mapper()")
    }

    @Test
    fun `published module may use conformance support only from tests`() {
        writeSettings("agent-framework-core", "agent-framework-conformance")
        writeRootBuild()
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }

            dependencies {
                testImplementation project(':agent-framework-conformance')
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-conformance/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").build()

        assertThat(result.task(":checkArchitecture")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `published module cannot use conformance support as a production dependency`() {
        writeSettings("agent-framework-core", "agent-framework-conformance")
        writeRootBuild()
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }

            dependencies {
                implementation project(':agent-framework-conformance')
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-conformance/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").buildAndFail()

        assertThat(result.output)
            .contains(
                "agent-framework-core must not use agent-framework-conformance as a production dependency.",
            )
    }

    @Test
    fun `forbidden transitive production dependency is reported from resolved graph`() {
        writeMavenModule(
            group = "io.opentelemetry",
            artifact = "telemetry-api",
            version = "1.0",
        )
        writeMavenModule(
            group = "example",
            artifact = "bridge",
            version = "1.0",
            dependencies =
                listOf(
                    Triple("io.opentelemetry", "telemetry-api", "1.0"),
                ),
        )
        writeSettings("agent-framework-core", repository = "repository")
        writeRootBuild()
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'java-library'
            }

            tasks.withType(JavaCompile).configureEach {
                options.release = 25
            }

            dependencies {
                implementation 'example:bridge:1.0'
            }
            """.trimIndent(),
        )

        val result = runner("checkArchitecture").buildAndFail()

        assertThat(result.output)
            .contains("Module ':agent-framework-core' configuration 'compileClasspath'")
            .contains("io.opentelemetry:telemetry-api:1.0")
    }

    @Test
    fun `BOM conformance constraint fails architecture policy`() {
        writeBomFixture("agent-framework-conformance")

        val result = runner("checkArchitecture").buildAndFail()

        assertThat(result.output)
            .contains(
                "agent-framework-bom must not reference or constrain agent-framework-conformance",
            )
            .contains("constraint")
    }

    @Test
    fun `BOM missing a published module constraint fails architecture policy`() {
        writeBomFixture(
            publishedModules = listOf("agent-framework-core", "agent-framework-tools"),
            constraintTargets = listOf("agent-framework-core"),
        )

        val result = runner("checkArchitecture").buildAndFail()

        assertThat(result.output)
            .contains("constraints must exactly match published Java modules")
            .contains("missing=[agent-framework-tools]")
            .contains("extra=[]")
    }

    @Test
    fun `generated BOM metadata rejects conformance constraint`() {
        writeBomFixture("agent-framework-conformance")

        val result =
            runner(":agent-framework-bom:checkBomPublicationMetadata")
                .buildAndFail()

        assertThat(result.output)
            .contains("must not reference agent-framework-conformance")
    }

    @Test
    fun `valid BOM constraints pass and generated metadata excludes conformance`() {
        writeBomFixture("agent-framework-core")

        val result =
            runner(
                "checkArchitecture",
                ":agent-framework-bom:checkBomPublicationMetadata",
            ).build()

        assertThat(result.task(":checkArchitecture")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":agent-framework-bom:checkBomPublicationMetadata")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)

        val pom =
            Files.readString(
                projectDirectory.resolve(
                    "agent-framework-bom/build/publications/mavenBom/pom-default.xml",
                ),
            )
        val moduleMetadata =
            Files.readString(
                projectDirectory.resolve(
                    "agent-framework-bom/build/publications/mavenBom/module.json",
                ),
            )
        assertThat(pom)
            .contains("<artifactId>agent-framework-core</artifactId>")
            .doesNotContain("agent-framework-conformance")
        assertThat(moduleMetadata)
            .contains("agent-framework-core")
            .doesNotContain("agent-framework-conformance")
    }

    @Test
    fun `all publication entry points include quality and architecture gates`() {
        writeSettings("agent-framework-core", "agent-framework-bom", "agent-framework-conformance")
        writeRootBuild(includePublicationAggregate = true)
        writeFile(
            "config/checkstyle/checkstyle.xml",
            "<module name=\"Checker\"/>",
        )
        writeFile(
            "gradle/libs.versions.toml",
            """
            [versions]
            assertj = "3.27.7"
            checkstyle = "13.9.0"
            jacoco = "0.8.15"
            junit = "5.14.4"
            mockito = "5.23.0"
            palantir-java-format = "2.80.0"
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-core/build.gradle",
            """
            plugins {
                id 'com.microsoft.agents.java-library'
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-bom/build.gradle",
            """
            plugins {
                id 'com.microsoft.agents.java-platform'
            }
            """.trimIndent(),
        )
        writeFile(
            "agent-framework-conformance/build.gradle",
            """
            plugins {
                id 'com.microsoft.agents.java-test-support'
            }
            """.trimIndent(),
        )

        val result =
            runner(
                ":agent-framework-core:publishMavenJavaPublicationToLocalTestRepository",
                ":agent-framework-core:publishMavenJavaPublicationToMavenLocal",
                ":agent-framework-bom:publishMavenBomPublicationToLocalTestRepository",
                ":agent-framework-bom:publishMavenBomPublicationToMavenLocal",
                "publishToTestRepository",
                "--dry-run",
            ).build()

        assertThat(result.output)
            .contains(":agent-framework-core:check SKIPPED")
            .contains(":checkArchitecture SKIPPED")
            .contains(":agent-framework-core:publishMavenJavaPublicationToLocalTestRepository SKIPPED")
            .contains(":agent-framework-core:publishMavenJavaPublicationToMavenLocal SKIPPED")
            .contains(":agent-framework-bom:check SKIPPED")
            .contains(":agent-framework-bom:publishMavenBomPublicationToLocalTestRepository SKIPPED")
            .contains(":agent-framework-bom:publishMavenBomPublicationToMavenLocal SKIPPED")
            .contains(":publishToTestRepository SKIPPED")
            .doesNotContain(":agent-framework-conformance:publish")
    }

    private fun writeBomFixture(constraintTarget: String) {
        writeBomFixture(
            publishedModules = listOf("agent-framework-core"),
            constraintTargets = listOf(constraintTarget),
        )
    }

    private fun writeBomFixture(
        publishedModules: List<String>,
        constraintTargets: List<String>,
    ) {
        writeSettings(
            *publishedModules.toTypedArray(),
            "agent-framework-bom",
            "agent-framework-conformance",
        )
        writeRootBuild()
        (publishedModules + "agent-framework-conformance").forEach { module ->
            writeFile(
                "$module/build.gradle",
                """
                plugins {
                    id 'java-library'
                }

                group = 'com.microsoft.agents'
                version = '0.1.0-test'

                tasks.withType(JavaCompile).configureEach {
                    options.release = 25
                }
                """.trimIndent(),
            )
        }
        writeFile(
            "agent-framework-bom/build.gradle",
            """
            plugins {
                id 'com.microsoft.agents.java-platform'
            }

            dependencies {
                constraints {
                    ${constraintTargets.joinToString(System.lineSeparator()) { target -> "api project(':$target')" }}
                }
            }
            """.trimIndent(),
        )
    }

    private fun writeSettings(
        vararg modules: String,
        repository: String? = null,
    ) {
        val repositoryDeclaration =
            repository?.let {
                "maven { url = uri('$it') }"
            } ?: "mavenCentral()"
        val includes = modules.joinToString(System.lineSeparator()) { "include '$it'" }
        writeFile(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    $repositoryDeclaration
                }
            }

            rootProject.name = 'architecture-test'
            $includes
            """.trimIndent(),
        )
    }

    private fun writeRootBuild(includePublicationAggregate: Boolean = false) {
        val publicationConfiguration =
            if (includePublicationAggregate) {
                """
                tasks.register('publishToTestRepository') {
                    group = 'publishing'
                }

                subprojects {
                    plugins.withId('maven-publish') {
                        rootProject.tasks.named('publishToTestRepository') {
                            dependsOn(tasks.named('publishAllPublicationsToLocalTestRepository'))
                        }
                    }
                }
                """.trimIndent()
            } else {
                ""
            }
        writeFile(
            "build.gradle",
            """
            plugins {
                id 'base'
                id 'com.microsoft.agents.architecture'
            }

            $publicationConfiguration
            """.trimIndent(),
        )
    }

    private fun writeMavenModule(
        group: String,
        artifact: String,
        version: String,
        dependencies: List<Triple<String, String, String>> = emptyList(),
    ) {
        val moduleDirectory =
            projectDirectory.resolve(
                "repository/${group.replace('.', '/')}/$artifact/$version",
            )
        Files.createDirectories(moduleDirectory)
        val dependencyElements =
            dependencies.joinToString(System.lineSeparator()) { (dependencyGroup, dependencyArtifact, dependencyVersion) ->
                """
                <dependency>
                  <groupId>$dependencyGroup</groupId>
                  <artifactId>$dependencyArtifact</artifactId>
                  <version>$dependencyVersion</version>
                </dependency>
                """.trimIndent()
            }
        Files.writeString(
            moduleDirectory.resolve("$artifact-$version.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <dependencies>
                $dependencyElements
              </dependencies>
            </project>
            """.trimIndent(),
        )
        JarOutputStream(
            Files.newOutputStream(moduleDirectory.resolve("$artifact-$version.jar")),
        ).use { }
    }

    private fun writeJacksonFixture() {
        val group = "com.fasterxml.jackson.core"
        val artifact = "jackson-databind"
        val version = "2.22.1"
        writeMavenModule(group, artifact, version)
        val sourceRoot = projectDirectory.resolve("dependency-sources")
        val classesRoot = projectDirectory.resolve("dependency-classes")
        val source = sourceRoot.resolve("com/fasterxml/jackson/databind/ObjectMapper.java")
        Files.createDirectories(source.parent)
        Files.createDirectories(classesRoot)
        Files.writeString(
            source,
            """
            package com.fasterxml.jackson.databind;

            public final class ObjectMapper {}
            """.trimIndent(),
        )
        val result =
            javax.tools.ToolProvider
                .getSystemJavaCompiler()
                .run(null, null, null, "--release", "25", "-d", classesRoot.toString(), source.toString())
        check(result == 0) { "Unable to compile the Jackson API fixture." }

        val jar =
            projectDirectory.resolve(
                "repository/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar",
            )
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classesRoot).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .forEach { classFile ->
                        val entryName =
                            classesRoot
                                .relativize(classFile)
                                .toString()
                                .replace(classFile.fileSystem.separator, "/")
                        output.putNextEntry(JarEntry(entryName))
                        Files.copy(classFile, output)
                        output.closeEntry()
                    }
            }
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ) {
        val path = projectDirectory.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, "$content\n")
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")
}
