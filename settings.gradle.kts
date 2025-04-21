import java.io.FileNotFoundException
import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localPropsFile = file("local.properties")

if (!localPropsFile.exists()) {
    throw FileNotFoundException("local.properties file not found")
}

val props = Properties().apply {
    load(localPropsFile.inputStream())
}

val mavenUsername = props.getProperty("mavenUsername") ?: throw IllegalArgumentException("Missing required property: mavenUsername")
val mavenPassword = props.getProperty("mavenPassword") ?: throw IllegalArgumentException("Missing required property: mavenPassword")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        maven {
            name = "pe-maven"
            isAllowInsecureProtocol = true
            url = uri("https://maven.payengine.co/payengine")
            credentials {
                username = mavenUsername
                password = mavenPassword
            }
        }
    }
}

rootProject.name = "PESoftPOS"
include(":app")
 