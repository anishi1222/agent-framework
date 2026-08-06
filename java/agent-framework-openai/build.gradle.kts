import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.microsoft.agents.java-library")
}

description = "OpenAI Responses API provider for Microsoft Agent Framework."

dependencies {
    api(project(":agent-framework-agents"))

    implementation(libs.openai.java)
    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}

val liveTestSourceSet = sourceSets.create("liveTest")
liveTestSourceSet.compileClasspath += sourceSets.main.get().output
liveTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations[liveTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[liveTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs opt-in live OpenAI integration tests."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    doFirst {
        val apiKey = System.getenv("OPENAI_API_KEY")
        val model = System.getenv("OPENAI_MODEL")
        if (apiKey.isNullOrBlank() || model.isNullOrBlank()) {
            throw GradleException(
                "liveTest requires non-blank OPENAI_API_KEY and OPENAI_MODEL environment variables.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(liveTestSourceSet.compileJavaTaskName)
}

tasks.named("jacocoTestReport") {
    setDependsOn(listOf(tasks.named("test")))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Microsoft Agent Framework OpenAI provider")
            description.set(project.description)
        }
    }
}
