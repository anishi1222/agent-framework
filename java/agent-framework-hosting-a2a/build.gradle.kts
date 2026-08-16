import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "A2A protocol v1 hosting adapters and embedded JSON-RPC/SSE server."

dependencies {
    api(project(":agent-framework-a2a"))
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-workflows"))

    testImplementation(project(":agent-framework-conformance"))
    testImplementation(libs.a2a.sdk.client)
    testImplementation(libs.a2a.sdk.client.transport.jsonrpc)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework A2A hosting")
            description.set(project.description)
        }
    }
}
