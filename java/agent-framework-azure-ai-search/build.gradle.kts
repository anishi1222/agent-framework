import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Azure AI Search retrieval context provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-azure-authentication"))

    implementation(libs.azure.search.documents)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Azure AI Search integration")
            description.set(project.description)
        }
    }
}
