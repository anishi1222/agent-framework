import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "GitHub Copilot provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.github.copilot.sdk)
    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework GitHub Copilot provider")
            description.set(project.description)
        }
    }
}
