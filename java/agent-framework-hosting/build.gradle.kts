import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Protocol-neutral hosting registry, dispatch, codec, isolation, and run lifecycle."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-workflows"))
    api(project(":agent-framework-orchestrations"))

    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework hosting")
            description.set(project.description)
        }
    }
}
