plugins {
    id("com.microsoft.agents.java-test-support")
}

description = "Non-published conformance fixtures and test-support APIs."

dependencies {
    implementation(libs.jackson.databind)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "conformance.matrix.path",
        rootProject.layout.projectDirectory.file("../docs/java/feature-parity-matrix.md").asFile.absolutePath,
    )
    systemProperty(
        "conformance.fixture.source.dir",
        layout.projectDirectory.dir("src/main/resources").asFile.absolutePath,
    )
}
