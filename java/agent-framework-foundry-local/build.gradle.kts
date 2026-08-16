import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Microsoft Foundry Local process-neutral REST provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(project(":agent-framework-mistral"))
    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework Foundry Local provider")
            description.set(project.description)
        }
    }
}
