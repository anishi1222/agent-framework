import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Azure AI Agents Persistent adapter for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-azure-authentication"))

    implementation(libs.azure.ai.agents.persistent)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure AI Agents Persistent adapter")
            description.set(project.description)
        }
    }
}
