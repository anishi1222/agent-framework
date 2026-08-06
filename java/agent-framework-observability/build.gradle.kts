plugins {
    id("com.microsoft.agents.java-library")
}

description = "Optional observability decorators for Agent Framework Java."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-workflows"))
    api(libs.opentelemetry.api)

    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
}
