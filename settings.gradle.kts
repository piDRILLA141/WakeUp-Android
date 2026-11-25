pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        google { content { excludeGroup("com.google.devtools.ksp") } }
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { excludeGroup("com.google.devtools.ksp") } }
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}
rootProject.name = "Podyom"
include(":core", ":app")
