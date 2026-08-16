plugins {
    id("com.microsoft.agents.java-library")
}

description =
    "Framework-owned ChatKit wire models, conversion, strict JSON, and response streaming."

dependencies {
    api(project(":agent-framework-core"))
    implementation(libs.jackson.databind)
}

checkstyle {
    configFile = file("../config/checkstyle/checkstyle.xml")
}
