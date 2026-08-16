plugins {
    base
    alias(libs.plugins.spotless)
    id("com.microsoft.agents.architecture")
}

group = "com.microsoft.agents"
version = providers.gradleProperty("frameworkVersion").get()

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "build-logic/**/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("metadata") {
        target("*.properties", "gradle/*.toml", "config/**/*.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val moduleBuildTasks =
    subprojects
        .filterNot { it.name == "agent-framework-bom" }
        .map { "${it.path}:build" }

tasks.named("build") {
    dependsOn(moduleBuildTasks)
    dependsOn(":agent-framework-bom:build")
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}

val externalProviderSdkVersions =
    mapOf(
        "agent-framework-anthropic" to ("com.anthropic" to "2.53.0"),
        "agent-framework-bedrock" to ("software.amazon.awssdk" to "2.51.3"),
        "agent-framework-gemini" to ("com.google.genai" to "1.65.0"),
        "agent-framework-mistral" to null,
        "agent-framework-ollama" to null,
        "agent-framework-foundry-local" to null,
        "agent-framework-github-copilot" to ("com.github" to "1.0.9"),
        "agent-framework-valkey" to ("io.valkey" to "2.5.1"),
        "agent-framework-mem0" to null,
    )

val externalProviderConvergenceChecks =
    externalProviderSdkVersions.map { (module, sdk) ->
        val providerProject = project(":$module")
        val convergenceCheck = providerProject.tasks.register("checkExternalProviderDependencyConvergence") {
            group = "verification"
            description = "Verifies $module SDK and transitive dependency convergence."

            doLast {
                val components =
                    providerProject
                        .configurations
                        .getByName("runtimeClasspath")
                        .incoming
                        .resolutionResult
                        .allComponents

                fun versions(group: String): Map<String, Set<String>> =
                    components
                        .mapNotNull { component ->
                            val version = component.moduleVersion ?: return@mapNotNull null
                            if (version.group != group) return@mapNotNull null
                            version.name to version.version
                        }.groupBy({ it.first }, { it.second })
                        .mapValues { (_, values) -> values.toSet() }

                if (sdk != null) {
                    val resolvedSdk = versions(sdk.first)
                    check(resolvedSdk.isNotEmpty() && resolvedSdk.values.flatten().toSet() == setOf(sdk.second)) {
                        "$module must converge on ${sdk.first}:${sdk.second}, resolved $resolvedSdk."
                    }
                }

                if (module in setOf("agent-framework-anthropic", "agent-framework-gemini")) {
                    listOf(
                            "com.fasterxml.jackson.core",
                            "com.fasterxml.jackson.datatype",
                            "com.fasterxml.jackson.module",
                        )
                        .forEach { group ->
                            versions(group).forEach { (artifact, resolvedVersions) ->
                                check(resolvedVersions.size == 1) {
                                    "$module resolves conflicting $group:$artifact versions $resolvedVersions."
                                }
                            }
                        }
                }

                if (module == "agent-framework-bedrock") {
                    val nettyVersions = versions("io.netty").values.flatten().toSet()
                    check(nettyVersions.size <= 1) {
                        "$module resolves conflicting Netty versions $nettyVersions."
                    }
                }

                check(versions("io.projectreactor").isEmpty()) {
                    "$module must not depend on Reactor."
                }
            }
        }
        providerProject.tasks.matching { it.name == "check" }.configureEach {
            dependsOn(convergenceCheck)
        }
        convergenceCheck
    }

val checkExternalProviderDependencyConvergence =
    tasks.register("checkExternalProviderDependencyConvergence") {
        group = "verification"
        description =
            "Verifies external-provider SDK, Jackson, Netty, and Reactor dependency convergence."
        dependsOn(externalProviderConvergenceChecks)
    }

tasks.named("check") {
    dependsOn(checkExternalProviderDependencyConvergence)
}

val azureSdkVersions =
    mapOf(
        "agent-framework-azure-openai" to
            mapOf(
                ("com.azure" to "azure-ai-openai") to "1.0.0-beta.16",
                ("com.azure" to "azure-identity") to "1.18.4",
                ("com.openai" to "openai-java") to "4.50.0",
            ),
        "agent-framework-azure-authentication" to
            mapOf(
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
        "agent-framework-azure-ai-persistent" to
            mapOf(
                ("com.azure" to "azure-ai-agents-persistent") to "1.0.0-beta.2",
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
        "agent-framework-foundry" to
            mapOf(
                ("com.azure" to "azure-ai-agents") to "2.3.0",
                ("com.azure" to "azure-ai-projects") to "2.3.0",
                ("com.azure" to "azure-identity") to "1.18.4",
                ("com.openai" to "openai-java") to "4.50.0",
            ),
        "agent-framework-foundry-evaluations" to
            mapOf(
                ("com.azure" to "azure-ai-agents") to "2.3.0",
                ("com.azure" to "azure-ai-projects") to "2.3.0",
                ("com.azure" to "azure-identity") to "1.18.4",
                ("com.openai" to "openai-java") to "4.50.0",
            ),
        "agent-framework-azure-content-understanding" to
            mapOf(
                ("com.azure" to "azure-ai-contentunderstanding") to "1.0.0",
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
        "agent-framework-azure-cosmos" to
            mapOf(
                ("com.azure" to "azure-cosmos") to "4.81.0",
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
        "agent-framework-azure-cosmos-memory" to
            mapOf(
                ("com.azure" to "azure-cosmos") to "4.81.0",
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
        "agent-framework-azure-ai-search" to
            mapOf(
                ("com.azure" to "azure-search-documents") to "12.0.1",
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
        "agent-framework-purview" to
            mapOf(
                ("com.azure" to "azure-identity") to "1.18.4",
            ),
    )

val azureSdkConvergenceChecks =
    azureSdkVersions.map { (module, expectedVersions) ->
        val azureProject = project(":$module")
        val convergenceCheck = azureProject.tasks.register("checkAzureSdkDependencyConvergence") {
            group = "verification"
            description = "Verifies $module Azure, OpenAI, Jackson, Reactor, and Netty dependency convergence."

            doLast {
                val components =
                    azureProject
                        .configurations
                        .getByName("runtimeClasspath")
                        .incoming
                        .resolutionResult
                        .allComponents
                        .mapNotNull { component -> component.moduleVersion }

                expectedVersions.forEach { (coordinate, expectedVersion) ->
                    val resolved =
                        components
                            .filter { version ->
                                version.group == coordinate.first && version.name == coordinate.second
                            }.mapTo(sortedSetOf()) { version -> version.version }
                    check(resolved == setOf(expectedVersion)) {
                        "$module must resolve ${coordinate.first}:${coordinate.second}:$expectedVersion, " +
                            "resolved $resolved."
                    }
                }

                listOf(
                        "com.azure",
                        "com.fasterxml.jackson.core",
                        "com.fasterxml.jackson.datatype",
                        "com.fasterxml.jackson.module",
                        "com.openai",
                        "io.netty",
                        "io.projectreactor",
                    )
                    .forEach { group ->
                        components
                            .filter { version -> version.group == group }
                            .groupBy { version -> version.name }
                            .forEach { (artifact, versions) ->
                                val resolved = versions.mapTo(sortedSetOf()) { version -> version.version }
                                check(resolved.size == 1) {
                                    "$module resolves conflicting $group:$artifact versions $resolved."
                                }
                            }
                    }
            }
        }
        azureProject.tasks.matching { it.name == "check" }.configureEach {
            dependsOn(convergenceCheck)
        }
        convergenceCheck
    }

val checkAzureSdkDependencyConvergence =
    tasks.register("checkAzureSdkDependencyConvergence") {
        group = "verification"
        description =
            "Verifies exact Azure/OpenAI SDK versions and selected transitive dependency convergence."
        dependsOn(azureSdkConvergenceChecks)
    }

tasks.named("check") {
    dependsOn(checkAzureSdkDependencyConvergence)
}

tasks.named("spotlessApply") {
    dependsOn(
        subprojects
            .filterNot { it.name == "agent-framework-bom" }
            .map { "${it.path}:spotlessApply" },
    )
}

tasks.named("spotlessCheck") {
    dependsOn(
        subprojects
            .filterNot { it.name == "agent-framework-bom" }
            .map { "${it.path}:spotlessCheck" },
    )
}

tasks.named("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}

val sbomOutput = layout.buildDirectory.file("reports/sbom/agent-framework-java.cdx.json")
val sbomRuntimeProjects =
    subprojects.filterNot {
        it.name in
            setOf(
                "agent-framework-bom",
                "agent-framework-conformance",
                "samples",
            )
    }

val generateSbom =
    tasks.register<com.microsoft.agents.buildlogic.CycloneDxSbomTask>("generateSbom") {
        group = "reporting"
        description = "Generates a deterministic aggregate CycloneDX 1.6 SBOM."
        dependsOn(subprojects.map { "${it.path}:assemble" })
        dependsOn(sbomRuntimeProjects.map { "${it.path}:writeRuntimeDependencyReport" })
        frameworkVersion.set(project.version.toString())
        moduleNames.set(
            subprojects
                .map { it.name }
                .filterNot { it in setOf("agent-framework-conformance", "samples") }
                .sorted(),
        )
        dependencyReportFiles.from(
            sbomRuntimeProjects.map {
                it.layout.buildDirectory.file(
                    "reports/sbom/runtime-dependencies.tsv",
                )
            },
        )
        outputFile.set(sbomOutput)
    }

val verifySbom =
    tasks.register("verifySbom") {
        group = "verification"
        description = "Verifies the aggregate Java SBOM shape and module coverage."
        dependsOn(generateSbom)
        inputs.file(sbomOutput)

        doLast {
            val text = sbomOutput.get().asFile.readText()
            check(text.contains("\"bomFormat\": \"CycloneDX\"")) {
                "The aggregate SBOM must use CycloneDX."
            }
            check(text.contains("\"specVersion\": \"1.6\"")) {
                "The aggregate SBOM must use CycloneDX 1.6."
            }
            check(text.contains("\"dependencies\": [")) {
                "The aggregate SBOM must contain resolved dependency relationships."
            }
            check(text.contains("\"name\": \"azure-core\"")) {
                "The aggregate SBOM must contain transitive runtime dependencies."
            }
            check(!text.contains("\"name\": \"spotbugs-annotations\"")) {
                "The aggregate SBOM must exclude compile-only dependencies."
            }
            subprojects
                .filterNot { it.name in setOf("agent-framework-conformance", "samples") }
                .forEach { project ->
                    check(text.contains("\"name\": \"${project.name}\"")) {
                        "The aggregate SBOM is missing ${project.name}."
                    }
                }
        }
    }

tasks.named("check") {
    dependsOn(verifySbom)
}

val publishToTestRepository = tasks.register("publishToTestRepository") {
    group = "publishing"
    description = "Publishes every Java artifact to build/test-maven-repository."
}

val releaseMode =
    providers
        .gradleProperty("release")
        .map(String::toBoolean)
        .getOrElse(false)
val releaseVersion = version.toString()

val verifyReleaseConfiguration =
    tasks.register("verifyReleaseConfiguration") {
        group = "verification"
        description = "Verifies credentials, signing, and versioning for an external Java release."

        doLast {
            if (!releaseMode) {
                return@doLast
            }
            check(!releaseVersion.endsWith("-SNAPSHOT")) {
                "External releases require a non-SNAPSHOT frameworkVersion."
            }
            val requiredSettings =
                mapOf(
                    "releaseRepositoryUrl" to
                        providers
                            .gradleProperty("releaseRepositoryUrl")
                            .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_URL"))
                            .orNull,
                    "releaseRepositoryUsername" to
                        providers
                            .gradleProperty("releaseRepositoryUsername")
                            .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_USERNAME"))
                            .orNull,
                    "releaseRepositoryPassword" to
                        providers
                            .gradleProperty("releaseRepositoryPassword")
                            .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_PASSWORD"))
                            .orNull,
                    "signingKey" to
                        providers
                            .gradleProperty("signingKey")
                            .orElse(providers.environmentVariable("MAVEN_SIGNING_KEY"))
                            .orNull,
                    "signingPassword" to
                        providers
                            .gradleProperty("signingPassword")
                            .orElse(providers.environmentVariable("MAVEN_SIGNING_PASSWORD"))
                            .orNull,
                )
            val missing = requiredSettings.filterValues { it.isNullOrBlank() }.keys
            check(missing.isEmpty()) {
                "External release configuration is missing: ${missing.sorted().joinToString()}."
            }
        }
    }

val publishRelease =
    tasks.register("publishRelease") {
        group = "publishing"
        description =
            "Publishes every Java artifact to the configured release repository and generates the SBOM."
        dependsOn(verifyReleaseConfiguration, generateSbom)
    }

subprojects.forEach { subproject ->
    subproject.plugins.withId("maven-publish") {
        rootProject.tasks.named("publishToTestRepository") {
            dependsOn(subproject.tasks.named("publishAllPublicationsToLocalTestRepository"))
        }
        rootProject.tasks.named("publishRelease") {
            dependsOn(subproject.tasks.named("publishAllPublicationsToReleaseRepository"))
        }
    }
}
