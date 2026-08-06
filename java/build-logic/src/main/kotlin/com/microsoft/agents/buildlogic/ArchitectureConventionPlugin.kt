// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider
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
        val publicSignatureChecks =
            project.subprojects
                .filter { it.name in PUBLIC_SIGNATURE_ISOLATED_MODULES }
                .map { module ->
                    module.tasks.register("checkArchitecturePublicSignatures") {
                        group = "verification"
                        description =
                            "Checks compiled public and protected signatures for shared-runtime API isolation."
                        dependsOn("${module.path}:classes")
                        doLast {
                            validateCompiledPublicSignatures(module)
                        }
                    }
                }
        architectureCheck.configure {
            dependsOn(resolvedDependencyChecks)
            dependsOn(publicSignatureChecks)
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

    }

    private fun validateCompiledPublicSignatures(module: Project) {
        val classesDirectory =
            module.layout.buildDirectory
                .dir("classes/java/main")
                .get()
                .asFile
        if (!classesDirectory.isDirectory) {
            return
        }
        val javap =
            ToolProvider.findFirst("javap").orElseThrow {
                GradleException("The JDK javap tool is required for compiled public-signature isolation.")
            }
        classesDirectory
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
            .forEach { classFile ->
                val className =
                    classFile
                        .relativeTo(classesDirectory)
                        .path
                        .removeSuffix(".class")
                        .replace(java.io.File.separatorChar, '.')
                val output = StringWriter()
                val errors = StringWriter()
                val result =
                    javap.run(
                        PrintWriter(output),
                        PrintWriter(errors),
                        "-protected",
                        "-classpath",
                        classesDirectory.absolutePath,
                        className,
                    )
                if (result != 0) {
                    throw GradleException(
                        "javap failed for ${module.path}:$className: ${errors.toString().trim()}",
                    )
                }
                val lines = output.toString().lineSequence().map(String::trim).toList()
                val declaration = lines.firstOrNull { it.endsWith("{") } ?: return@forEach
                if (!declaration.startsWith("public ") && !declaration.startsWith("protected ")) {
                    return@forEach
                }
                val offending =
                    lines.firstOrNull { line ->
                        val checkedLine =
                            if (module.name in AZURE_PROVIDER_MODULES) {
                                ALLOWED_AZURE_PROVIDER_PUBLIC_TYPES.fold(line) { value, allowed ->
                                    value.replace(allowed, "")
                                }
                            } else {
                                line
                            }
                        (line.startsWith("public ") || line.startsWith("protected ")) &&
                            FORBIDDEN_PUBLIC_API_PREFIXES.any(checkedLine::contains)
                    }
                if (offending != null) {
                    val forbidden = requireNotNull(FORBIDDEN_PUBLIC_API_PREFIXES.firstOrNull(offending::contains))
                    throw GradleException(
                        "Module '${module.path}' compiled public/protected signature '$offending' " +
                            "exposes forbidden API namespace $forbidden.",
                    )
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
        root.findProject(":$TEST_SUPPORT_MODULE")?.let { testSupport ->
            if (testSupport.pluginManager.hasPlugin("maven-publish")) {
                throw GradleException("$TEST_SUPPORT_MODULE is test support and must not apply maven-publish.")
            }
        }

        val bom = root.findProject(":$BOM_MODULE") ?: return
        val references = sortedSetOf<String>()
        bom.configurations.forEach { configuration ->
            configuration.allDependencies
                .filter { dependency -> dependency.name == TEST_SUPPORT_MODULE }
                .forEach { dependency ->
                    references +=
                        "${configuration.name} dependency " +
                        "${dependency.group ?: "<project>"}:${dependency.name}"
                }
            configuration.allDependencyConstraints
                .filter { constraint -> constraint.name == TEST_SUPPORT_MODULE }
                .forEach { constraint ->
                    references +=
                        "${configuration.name} constraint " +
                        "${constraint.group ?: "<project>"}:${constraint.name}"
                }
        }
        if (references.isNotEmpty()) {
            throw GradleException(
                "$BOM_MODULE must not reference or constrain $TEST_SUPPORT_MODULE; found: " +
                    references.joinToString(),
            )
        }

        val expectedConstraints = JavaModulePolicies.publishedModules(root)
        val declaredConstraints =
            bom.configurations
                .getByName("api")
                .dependencyConstraints
                .mapTo(sortedSetOf()) { constraint -> constraint.name }
        val missing = expectedConstraints - declaredConstraints
        val extra = declaredConstraints - expectedConstraints
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw GradleException(
                "$BOM_MODULE constraints must exactly match published Java modules; " +
                    "missing=${missing.sorted()}, extra=${extra.sorted()}.",
            )
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
        const val BOM_MODULE = JavaModulePolicies.BOM_MODULE

        val SHARED_MODULES =
            setOf(
                "agent-framework-core",
                "agent-framework-tools",
                "agent-framework-agents",
                "agent-framework-workflows",
                "agent-framework-orchestrations",
            )

        val PUBLIC_SIGNATURE_ISOLATED_MODULES =
            SHARED_MODULES +
                setOf(
                    "agent-framework-openai",
                    "agent-framework-azure-openai",
                    "agent-framework-foundry",
                )

        val AZURE_PROVIDER_MODULES =
            setOf(
                "agent-framework-azure-openai",
                "agent-framework-foundry",
            )

        val ALLOWED_AZURE_PROVIDER_PUBLIC_TYPES =
            setOf(
                "com.azure.core.credential.TokenCredential",
            )

        val MODULE_POLICIES = JavaModulePolicies.policies

        val PRODUCTION_CLASSPATHS = listOf("compileClasspath", "runtimeClasspath")
        const val TEST_SUPPORT_MODULE = JavaModulePolicies.TEST_SUPPORT_MODULE

        val FORBIDDEN_PUBLIC_API_PREFIXES =
            listOf(
                "com.azure.",
                "com.anthropic.",
                "com.fasterxml.jackson.",
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
