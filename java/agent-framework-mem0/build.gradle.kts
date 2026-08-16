import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Mem0 Platform context provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Mem0 integration")
            description.set(project.description)
        }
    }
}
