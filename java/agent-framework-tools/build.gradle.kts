plugins {
    id("com.microsoft.agents.java-library")
}

description = "Tool metadata, argument binding, approvals, and tool invocation runtime."

dependencies {
    api(project(":agent-framework-core"))

    testImplementation(project(":agent-framework-conformance"))
}
