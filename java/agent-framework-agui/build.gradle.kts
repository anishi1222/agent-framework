import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Framework-owned AG-UI protocol model, strict codecs, converters, and JDK HTTP/SSE client."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
    testImplementation(libs.agui.community.core)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework AG-UI protocol")
            description.set(project.description)
        }
    }
}
