// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Writes one module's resolved runtime component and dependency-edge inventory. */
abstract class RuntimeDependencyReportTask : DefaultTask() {
    /** Returns deterministic tab-separated component and edge entries. */
    @get:Input
    abstract val entries: ListProperty<String>

    /** Returns the report output file. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /** Writes the sorted dependency report. */
    @TaskAction
    fun writeReport() {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        val lines = entries.get().filter(String::isNotBlank).distinct().sorted()
        target.writeText(lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }
}
