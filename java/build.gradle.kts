plugins {
    base
    alias(libs.plugins.spotless)
    id("com.microsoft.agents.architecture")
}

group = "com.microsoft.agents"
version = providers.gradleProperty("frameworkVersion").get()

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "build-logic/**/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("metadata") {
        target("*.properties", "gradle/*.toml", "config/**/*.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val moduleBuildTasks =
    subprojects
        .filterNot { it.name == "agent-framework-bom" }
        .map { "${it.path}:build" }

tasks.named("build") {
    dependsOn(moduleBuildTasks)
    dependsOn(":agent-framework-bom:build")
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.named("spotlessApply") {
    dependsOn(
        subprojects
            .filterNot { it.name == "agent-framework-bom" }
            .map { "${it.path}:spotlessApply" },
    )
}

tasks.named("spotlessCheck") {
    dependsOn(
        subprojects
            .filterNot { it.name == "agent-framework-bom" }
            .map { "${it.path}:spotlessCheck" },
    )
}

tasks.named("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}

val publishToTestRepository = tasks.register("publishToTestRepository") {
    group = "publishing"
    description = "Publishes every Java artifact to build/test-maven-repository."
}

subprojects.forEach { subproject ->
    subproject.plugins.withId("maven-publish") {
        rootProject.tasks.named("publishToTestRepository") {
            dependsOn(subproject.tasks.named("publishAllPublicationsToLocalTestRepository"))
        }
    }
}
