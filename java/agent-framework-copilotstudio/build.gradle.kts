import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Microsoft Copilot Studio Direct-to-Engine provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Copilot Studio provider")
            description.set(project.description)
        }
    }
}
