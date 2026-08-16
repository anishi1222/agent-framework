pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url =
                uri(
                    providers
                        .gradleProperty("agentFrameworkRepository")
                        .getOrElse("../build/test-maven-repository"),
                )
            val repositoryUsername =
                providers
                    .gradleProperty("releaseRepositoryUsername")
                    .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_USERNAME"))
                    .orNull
            val repositoryPassword =
                providers
                    .gradleProperty("releaseRepositoryPassword")
                    .orElse(providers.environmentVariable("MAVEN_RELEASE_REPOSITORY_PASSWORD"))
                    .orNull
            if (repositoryUsername != null || repositoryPassword != null) {
                credentials {
                    username = repositoryUsername
                    password = repositoryPassword
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "agent-framework-java-consumer-smoke"
