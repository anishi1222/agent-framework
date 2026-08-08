import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "Model Context Protocol server and hosting adapter for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-mcp"))

    implementation(libs.mcp.sdk)
    implementation(libs.tomcat.embed.core)
}

tasks.withType<Test>().configureEach {
    systemProperty("mcp.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("stdio-smoke")
    }
}

val stdioTest = tasks.register<Test>("stdioTest") {
    group = "verification"
    description = "Runs the isolated child-JVM MCP stdio smoke test."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stdio-smoke")
    }
}

gradle.projectsEvaluated {
    stdioTest {
        // Keep the child-JVM stdio smoke isolated from other parallel Gradle test workers.
        mustRunAfter(
            rootProject.subprojects
                .filter { it != project }
                .mapNotNull { it.tasks.findByName("test") },
        )
        mustRunAfter(tasks.named("test"))
    }
}

tasks.named("check") {
    dependsOn(stdioTest)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework MCP hosting")
            description.set(project.description)
        }
    }
}
