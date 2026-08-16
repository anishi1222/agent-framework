import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "AG-UI Agent, Workflow, and Orchestration hosting over bounded HTTP and SSE."

dependencies {
    api(project(":agent-framework-agui"))
    api(project(":agent-framework-hosting"))
    api(project(":agent-framework-hosting-http")) {
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-core")
        exclude(group = "org.apache.tomcat.embed", module = "tomcat-embed-websocket")
    }
    api(project(":agent-framework-orchestrations"))

    testImplementation(project(":agent-framework-conformance"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework AG-UI hosting")
            description.set(project.description)
        }
    }
}
