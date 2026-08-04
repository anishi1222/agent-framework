// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class JavaTestSupportConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configureJavaLibraryConvention(published = false)
    }
}
