import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Mistral Chat Completions provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Mistral provider")
            description.set(project.description)
        }
    }
}
