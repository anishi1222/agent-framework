// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

internal data class JavaModulePolicy(
    val allowedProjectDependencies: Set<String>,
    val expectedPackageRoot: String,
    val published: Boolean = true,
)

/** Single source of truth for Java module architecture and publication policy. */
internal object JavaModulePolicies {
    const val BOM_MODULE = "agent-framework-bom"

    const val TEST_SUPPORT_MODULE = "agent-framework-conformance"

    val policies =
        mapOf(
            "agent-framework-core" to
                JavaModulePolicy(emptySet(), "com.microsoft.agents.core"),
            "agent-framework-tools" to
                JavaModulePolicy(
                    setOf("agent-framework-core"),
                    "com.microsoft.agents.tools",
                ),
            "agent-framework-agents" to
                JavaModulePolicy(
                    setOf("agent-framework-core", "agent-framework-tools"),
                    "com.microsoft.agents.agents",
                ),
            "agent-framework-workflows" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.workflows",
                ),
            "agent-framework-orchestrations" to
                JavaModulePolicy(
                    setOf("agent-framework-workflows"),
                    "com.microsoft.agents.orchestrations",
                ),
            "agent-framework-observability" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.observability",
                ),
            "agent-framework-reactor-adapter" to
                JavaModulePolicy(
                    setOf("agent-framework-agents"),
                    "com.microsoft.agents.adapters.reactor",
                ),
            TEST_SUPPORT_MODULE to
                JavaModulePolicy(
                    emptySet(),
                    "com.microsoft.agents.conformance",
                    published = false,
                ),
        )

    fun publishedModules(root: org.gradle.api.Project): Set<String> =
        root.subprojects
            .map { it.name }
            .filterTo(sortedSetOf()) { moduleName -> policies[moduleName]?.published == true }
}
