plugins {
    id("com.microsoft.agents.java-library")
}

description = "Provider-neutral models, options, concurrency primitives, and serialization contracts."

dependencies {
    implementation(libs.jackson.databind)

    testImplementation(project(":agent-framework-conformance"))
}
