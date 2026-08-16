import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Opt-in embedded developer UI for generic Agent Framework hosting."

dependencies {
    api(project(":agent-framework-hosting"))
    implementation(project(":agent-framework-hosting-http")) {
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-core")
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework developer UI")
            description.set(project.description)
        }
    }
}
