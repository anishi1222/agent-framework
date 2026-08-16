// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

internal fun componentCoordinate(
    identifier: ComponentIdentifier,
    frameworkVersion: String,
): RuntimeCoordinate? =
    when (identifier) {
        is ModuleComponentIdentifier ->
            RuntimeCoordinate(
                identifier.group,
                identifier.module,
                identifier.version,
            )

        is ProjectComponentIdentifier ->
            RuntimeCoordinate(
                "com.microsoft.agents",
                identifier.projectName,
                frameworkVersion,
            )

        else -> null
    }

internal data class RuntimeCoordinate(
    val group: String,
    val name: String,
    val version: String,
) {
    fun componentEntry(): String = listOf("component", group, name, version).joinToString("\t")

    fun edgeEntry(dependency: RuntimeCoordinate): String =
        listOf(
                "edge",
                group,
                name,
                version,
                dependency.group,
                dependency.name,
                dependency.version,
            )
            .joinToString("\t")
}
