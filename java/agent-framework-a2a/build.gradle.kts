import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "A2A protocol v1 models, JSON-RPC client, and remote-agent adapter."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
    testImplementation(libs.a2a.sdk.client)
    testImplementation(libs.a2a.sdk.client.transport.jsonrpc)
}

val testedA2AVersion = libs.versions.a2a.get()
val a2aTestRuntimeClasspath = configurations.named("testRuntimeClasspath")
val checkA2ASdkConvergence = tasks.register("checkA2ASdkConvergence") {
    group = "verification"
    description = "Fails unless every official A2A Java SDK test artifact resolves to the pinned version."
    inputs.property("testedA2AVersion", testedA2AVersion)
    doLast {
        val resolved =
            a2aTestRuntimeClasspath
                .get()
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { it.moduleVersion }
                .filter { it.group == "org.a2aproject.sdk" }
                .associate { it.name to it.version }
        if (resolved.isEmpty() || resolved.values.any { it != testedA2AVersion }) {
            throw GradleException(
                "A2A testRuntimeClasspath must resolve only official SDK version " +
                    "$testedA2AVersion, but resolved $resolved.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkA2ASdkConvergence)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework A2A client")
            description.set(project.description)
        }
    }
}
