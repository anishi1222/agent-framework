import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Embedded HTTP, SSE, and WebSocket transport for Agent Framework hosting."

dependencies {
    api(project(":agent-framework-hosting"))

    implementation(libs.tomcat.embed.core)
    implementation(libs.tomcat.embed.websocket)

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework HTTP hosting")
            description.set(project.description)
        }
    }
}
