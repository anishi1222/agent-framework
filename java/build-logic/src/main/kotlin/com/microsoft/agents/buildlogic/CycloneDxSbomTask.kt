// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates the deterministic aggregate CycloneDX document for the Java distribution. */
abstract class CycloneDxSbomTask : DefaultTask() {
    /** Returns the framework version recorded in the SBOM metadata. */
    @get:Input
    abstract val frameworkVersion: Property<String>

    /** Returns the published first-party module names. */
    @get:Input
    abstract val moduleNames: ListProperty<String>

    /** Returns per-module resolved runtime dependency reports. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyReportFiles: ConfigurableFileCollection

    /** Returns the target CycloneDX JSON file. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /** Generates the aggregate SBOM from first-party modules and resolved runtime dependency graphs. */
    @TaskAction
    fun generate() {
        val components = linkedMapOf<String, SbomComponent>()
        val dependencyCoordinates = linkedMapOf<String, MutableSet<String>>()
        moduleNames.get().forEach { moduleName ->
            val component =
                SbomComponent(
                    "com.microsoft.agents",
                    moduleName,
                    frameworkVersion.get(),
                )
            components[component.coordinate] = component
        }
        dependencyReportFiles.files.sortedBy { it.absolutePath }.forEach { report ->
            report.readLines().forEach { source ->
                val line = source.trim()
                if (line.isNotEmpty()) {
                    parseReportEntry(report.name, line, components, dependencyCoordinates)
                }
            }
        }

        val ordered =
            components.values.sortedWith(
                compareBy(SbomComponent::group, SbomComponent::name, SbomComponent::version),
            )
        val rootRef =
            "pkg:maven/com.microsoft.agents/agent-framework-java@" +
                frameworkVersion.get()
        val dependencies = linkedMapOf<String, List<String>>()
        dependencies[rootRef] =
            moduleNames
                .get()
                .map { moduleName ->
                    SbomComponent(
                            "com.microsoft.agents",
                            moduleName,
                            frameworkVersion.get(),
                        )
                        .ref
                }
                .sorted()
        ordered.forEach { component ->
            dependencies[component.ref] =
                dependencyCoordinates[component.coordinate]
                    .orEmpty()
                    .map { coordinate ->
                        components[coordinate]?.ref
                            ?: throw GradleException(
                                "The runtime dependency graph references unknown component $coordinate.",
                            )
                    }
                    .sorted()
        }
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(render(rootRef, ordered, dependencies))
    }

    private fun parseReportEntry(
        reportName: String,
        line: String,
        components: MutableMap<String, SbomComponent>,
        dependencies: MutableMap<String, MutableSet<String>>,
    ) {
        val fields = line.split('\t')
        when (fields.firstOrNull()) {
            "component" -> {
                if (fields.size != 4) {
                    throw malformedReport(reportName, line)
                }
                val component = SbomComponent(fields[1], fields[2], fields[3])
                components[component.coordinate] = component
            }

            "edge" -> {
                if (fields.size != 7) {
                    throw malformedReport(reportName, line)
                }
                val from = SbomComponent(fields[1], fields[2], fields[3])
                val to = SbomComponent(fields[4], fields[5], fields[6])
                components[from.coordinate] = from
                components[to.coordinate] = to
                dependencies
                    .computeIfAbsent(from.coordinate) { linkedSetOf() }
                    .add(to.coordinate)
            }

            else -> throw malformedReport(reportName, line)
        }
    }

    private fun malformedReport(reportName: String, line: String): GradleException =
        GradleException("Malformed runtime dependency report entry in $reportName: $line")

    private fun render(
        rootRef: String,
        components: List<SbomComponent>,
        dependencies: Map<String, List<String>>,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"bomFormat\": \"CycloneDX\",")
            appendLine("  \"specVersion\": \"1.6\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"metadata\": {")
            appendLine("    \"component\": {")
            appendLine("      \"type\": \"framework\",")
            appendLine("      \"group\": \"com.microsoft.agents\",")
            appendLine("      \"name\": \"agent-framework-java\",")
            appendLine("      \"version\": ${json(frameworkVersion.get())},")
            appendLine("      \"bom-ref\": ${json(rootRef)}")
            appendLine("    }")
            appendLine("  },")
            appendLine("  \"components\": [")
            components.forEachIndexed { index, component ->
                appendLine("    {")
                appendLine("      \"type\": \"library\",")
                appendLine("      \"group\": ${json(component.group)},")
                appendLine("      \"name\": ${json(component.name)},")
                appendLine("      \"version\": ${json(component.version)},")
                appendLine("      \"bom-ref\": ${json(component.ref)},")
                appendLine("      \"purl\": ${json(component.ref)}")
                append("    }")
                appendLine(if (index == components.lastIndex) "" else ",")
            }
            appendLine("  ],")
            appendLine("  \"dependencies\": [")
            dependencies.entries.forEachIndexed { index, (reference, dependsOn) ->
                appendLine("    {")
                appendLine("      \"ref\": ${json(reference)},")
                append("      \"dependsOn\": [")
                append(dependsOn.joinToString(", ") { dependency -> json(dependency) })
                appendLine("]")
                append("    }")
                appendLine(if (index == dependencies.size - 1) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun json(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (character.code < 0x20) {
                            append("\\u%04x".format(character.code))
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }

    private data class SbomComponent(
        val group: String,
        val name: String,
        val version: String,
    ) {
        val coordinate: String
            get() = "$group:$name:$version"

        val ref: String
            get() = "pkg:maven/$group/$name@$version"
    }
}
