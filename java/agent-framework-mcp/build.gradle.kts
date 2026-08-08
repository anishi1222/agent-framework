import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Model Context Protocol client and tool adapter for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-tools"))

    implementation(libs.mcp.sdk)
}

val testedMcpVersion = libs.versions.mcp.get()
val mcpRuntimeClasspath = configurations.named("runtimeClasspath")
val checkMcpSdkConvergence = tasks.register("checkMcpSdkConvergence") {
    group = "verification"
    description = "Fails unless every official MCP Java SDK artifact resolves to the pinned version."
    inputs.property("testedMcpVersion", testedMcpVersion)
    doLast {
        val resolved =
            mcpRuntimeClasspath
                .get()
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { it.moduleVersion }
                .filter { it.group == "io.modelcontextprotocol.sdk" }
                .associate { it.name to it.version }
        if (resolved.isEmpty() || resolved.values.any { it != testedMcpVersion }) {
            throw GradleException(
                "MCP runtimeClasspath must resolve only official SDK version " +
                    "$testedMcpVersion, but resolved $resolved.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkMcpSdkConvergence)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework MCP client")
            description.set(project.description)
        }
    }
}
