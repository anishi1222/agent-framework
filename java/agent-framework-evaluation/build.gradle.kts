plugins {
    id("com.microsoft.agents.java-library")
}

description = "Provider-neutral local evaluation contracts, checks, conversation splitting, and runtime adapters."

dependencies {
    api(project(":agent-framework-agents"))
    api(project(":agent-framework-workflows"))
}

checkstyle {
    configFile = file("../config/checkstyle/checkstyle.xml")
}
