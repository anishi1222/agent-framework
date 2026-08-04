// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile

class ArchitectureConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "The architecture convention must be applied to the root Java project."
        }

        val architectureCheck =
            project.tasks.register("checkArchitecture") {
                group = "verification"
                description =
                    "Checks Java 25/no-preview policy, module direction, package ownership, and API isolation."

                doLast {
                    validateRegisteredPolicies(project)
                    validateCompilerOptions(project)
                    validateSourcePolicy(project)
                    validateDependencyDirection(project)
                    validatePackageOwnership(project)
                    validatePublicationPolicy(project)
                }
            }

        val resolvedDependencyChecks =
            project.subprojects
                .filter { it.name in SHARED_MODULES }
                .map { module ->
                    module.tasks.register("checkArchitectureDependencies") {
                        group = "verification"
                        description =
                            "Checks resolved production dependencies for shared-runtime isolation."
                        doLast {
                            validateResolvedProductionDependencies(module)
                        }
                    }
                }
        architectureCheck.configure {
            dependsOn(resolvedDependencyChecks)
        }

        project.pluginManager.withPlugin("base") {
            project.tasks.named("check").configure {
                dependsOn(architectureCheck)
            }
        }

        project.tasks.matching { it.name == "publishToTestRepository" }.configureEach {
            dependsOn(project.tasks.named("check"))
            dependsOn(architectureCheck)
        }
    }

    private fun validateRegisteredPolicies(root: Project) {
        root.subprojects
            .filterNot { it.name == BOM_MODULE }
            .forEach { subproject ->
                if (MODULE_POLICIES[subproject.name] == null) {
                    throw GradleException(
                        "No Java architecture policy is registered for project '${subproject.path}'. " +
                            "Add an entry to MODULE_POLICIES with an explicit project dependency " +
                            "allowlist and expected package root.",
                    )
                }
            }
    }

    private fun validateCompilerOptions(root: Project) {
        root.allprojects.forEach { candidate ->
            candidate.tasks.withType(JavaCompile::class.java).forEach { compileTask ->
                if (compileTask.options.release.orNull != 25) {
                    throw GradleException(
                        "${compileTask.path} must compile with --release 25.",
                    )
                }
                if (compileTask.options.compilerArgs.any { it == PREVIEW_FLAG }) {
                    throw GradleException("${compileTask.path} enables Java preview features.")
                }
            }
        }
    }

    private fun validateSourcePolicy(root: Project) {
        val policyFiles =
            root.fileTree(root.rootDir) {
                include("*.gradle.kts", "gradle.properties", "*/build.gradle.kts")
                exclude("build/**", "**/.gradle/**")
            }
        policyFiles.forEach { file ->
            if (file.readText().contains(PREVIEW_FLAG)) {
                throw GradleException("${file.relativeTo(root.rootDir)} enables Java preview features.")
            }
        }

        root.subprojects.forEach { subproject ->
            subproject.fileTree("src") {
                include("**/*.java")
            }.forEach { source ->
                val text = source.readText()
                if (text.contains("jdk.incubator.concurrent") || text.contains("StructuredTaskScope")) {
                    throw GradleException(
                        "${source.relativeTo(root.rootDir)} references preview structured concurrency.",
                    )
                }
            }
        }

        SHARED_MODULES.forEach { moduleName ->
            val module = root.findProject(":$moduleName") ?: return@forEach
            module.fileTree("src/main/java") {
                include("**/*.java")
            }.forEach { source ->
                val text = source.readText()
                FORBIDDEN_PUBLIC_API_PREFIXES.firstOrNull { text.contains(it) }?.let { forbidden ->
                    throw GradleException(
                        "${source.relativeTo(root.rootDir)} exposes or imports forbidden API namespace $forbidden.",
                    )
                }
            }
        }
    }

    private fun validateDependencyDirection(root: Project) {
        root.subprojects.forEach { source ->
            if (source.name == BOM_MODULE) {
                return@forEach
            }
            val policy = requireNotNull(MODULE_POLICIES[source.name])
            val allowedTargets = policy.allowedProjectDependencies
            val configuredProjectDependencies =
                source.configurations.flatMap { configuration ->
                    configuration.dependencies.withType(ProjectDependency::class.java).map {
                        configuration.name to it.path.substringAfterLast(':')
                    }
                }
            if (
                policy.published &&
                    configuredProjectDependencies.any { (configuration, target) ->
                        target == TEST_SUPPORT_MODULE && !configuration.startsWith("test", ignoreCase = true)
                    }
            ) {
                throw GradleException(
                    "${source.name} must not use $TEST_SUPPORT_MODULE as a production dependency. " +
                        "Conformance support is permitted only on test configurations.",
                )
            }
            val invalidTargets =
                configuredProjectDependencies
                    .filterNot { (configuration, target) ->
                        target in allowedTargets ||
                            (target == TEST_SUPPORT_MODULE && configuration.startsWith("test", ignoreCase = true))
                    }
                    .map { it.second }
                    .toSet()
            if (invalidTargets.isNotEmpty()) {
                throw GradleException(
                    "${source.name} has invalid project dependencies: ${invalidTargets.sorted()}; " +
                        "allowed: ${allowedTargets.sorted()}.",
                )
            }

            if (source.name == TEST_SUPPORT_MODULE) {
                val allProjectDependencies =
                    source.configurations
                        .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                        .map { it.path.substringAfterLast(':') }
                        .toSet()
                if (allProjectDependencies.isNotEmpty()) {
                    throw GradleException(
                        "$TEST_SUPPORT_MODULE must not depend on framework implementation projects: " +
                            "${allProjectDependencies.sorted()}.",
                    )
                }
            }
        }
    }

    private fun validatePublicationPolicy(root: Project) {
        val testSupport = root.findProject(":$TEST_SUPPORT_MODULE") ?: return
        if (testSupport.pluginManager.hasPlugin("maven-publish")) {
            throw GradleException("$TEST_SUPPORT_MODULE is test support and must not apply maven-publish.")
        }
    }

    private fun validateResolvedProductionDependencies(module: Project) {
        PRODUCTION_CLASSPATHS.forEach { configurationName ->
            val configuration = module.configurations.findByName(configurationName) ?: return@forEach
            if (!configuration.isCanBeResolved) {
                return@forEach
            }

            val forbiddenComponents =
                configuration.incoming.resolutionResult.allComponents
                    .mapNotNull { component ->
                        val identifier = component.id as? ModuleComponentIdentifier
                            ?: return@mapNotNull null
                        if (FORBIDDEN_DEPENDENCY_GROUPS.any {
                                identifier.group == it || identifier.group.startsWith("$it.")
                            }
                        ) {
                            identifier.displayName
                        } else {
                            null
                        }
                    }
                    .toSortedSet()

            if (forbiddenComponents.isNotEmpty()) {
                throw GradleException(
                    "Module '${module.path}' configuration '$configurationName' resolves forbidden " +
                        "shared-runtime component(s): ${forbiddenComponents.joinToString()}.",
                )
            }
        }
    }

    private fun validatePackageOwnership(root: Project) {
        root.subprojects
            .filterNot { it.name == BOM_MODULE }
            .forEach { module ->
                val packageRoot = requireNotNull(MODULE_POLICIES[module.name]).expectedPackageRoot
                module.fileTree("src/main/java") {
                    include("**/*.java")
                }.forEach { source ->
                    val packageName = PACKAGE_PATTERN.find(source.readText())?.groupValues?.get(1)
                    if (packageName == null ||
                        !(packageName == packageRoot || packageName.startsWith("$packageRoot."))
                    ) {
                        throw GradleException(
                            "${source.relativeTo(root.rootDir)} must belong to $packageRoot.",
                        )
                    }
                }
            }

        val core = root.findProject(":agent-framework-core") ?: return
        core.fileTree("src/main/java") {
            include("**/*.java")
        }.forEach { source ->
            if (source.nameWithoutExtension in CORE_FORBIDDEN_TYPE_NAMES) {
                throw GradleException(
                    "${source.relativeTo(root.rootDir)} belongs in a higher-level module, not core.",
                )
            }
        }
    }

    private companion object {
        const val PREVIEW_FLAG = "--enable-" + "preview"
        const val BOM_MODULE = "agent-framework-bom"

        data class ModulePolicy(
            val allowedProjectDependencies: Set<String>,
            val expectedPackageRoot: String,
            val published: Boolean = true,
        )

        val SHARED_MODULES =
            setOf(
                "agent-framework-core",
                "agent-framework-tools",
                "agent-framework-agents",
                "agent-framework-workflows",
                "agent-framework-orchestrations",
            )

        val MODULE_POLICIES =
            mapOf(
                "agent-framework-core" to
                    ModulePolicy(emptySet(), "com.microsoft.agents.core"),
                "agent-framework-tools" to
                    ModulePolicy(
                        setOf("agent-framework-core"),
                        "com.microsoft.agents.tools",
                    ),
                "agent-framework-agents" to
                    ModulePolicy(
                        setOf("agent-framework-core", "agent-framework-tools"),
                        "com.microsoft.agents.agents",
                    ),
                "agent-framework-workflows" to
                    ModulePolicy(
                        setOf("agent-framework-agents"),
                        "com.microsoft.agents.workflows",
                    ),
                "agent-framework-orchestrations" to
                    ModulePolicy(
                        setOf("agent-framework-workflows"),
                        "com.microsoft.agents.orchestrations",
                    ),
                "agent-framework-observability" to
                    ModulePolicy(
                        setOf("agent-framework-agents"),
                        "com.microsoft.agents.observability",
                    ),
                "agent-framework-reactor-adapter" to
                    ModulePolicy(
                        setOf("agent-framework-agents"),
                        "com.microsoft.agents.adapters.reactor",
                    ),
                TEST_SUPPORT_MODULE to
                    ModulePolicy(
                        emptySet(),
                        "com.microsoft.agents.conformance",
                        published = false,
                    ),
            )

        val PRODUCTION_CLASSPATHS = listOf("compileClasspath", "runtimeClasspath")
        const val TEST_SUPPORT_MODULE = "agent-framework-conformance"

        val FORBIDDEN_PUBLIC_API_PREFIXES =
            listOf(
                "com.azure.",
                "com.anthropic.",
                "com.google.genai.",
                "com.openai.",
                "dev.langchain4j.",
                "io.opentelemetry.",
                "org.springframework.ai.",
                "reactor.core.",
                "software.amazon.awssdk.",
            )

        val FORBIDDEN_DEPENDENCY_GROUPS =
            listOf(
                "com.anthropic",
                "com.azure",
                "com.google.genai",
                "com.openai",
                "dev.langchain4j",
                "io.opentelemetry",
                "io.projectreactor",
                "org.springframework.ai",
                "software.amazon.awssdk",
            )

        val CORE_FORBIDDEN_TYPE_NAMES =
            setOf(
                "Agent",
                "AgentMiddleware",
                "AgentSession",
                "ChatClient",
                "ContextProvider",
                "FunctionTool",
                "HistoryProvider",
                "SessionStore",
                "Tool",
                "Workflow",
            )

        val PACKAGE_PATTERN = Regex("""(?m)^\s*package\s+([a-zA-Z0-9_.]+)\s*;""")
    }
}
