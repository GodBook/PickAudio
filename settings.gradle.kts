pluginManagement {
    repositories {
        maven { url = java.net.URI("https://maven.aliyun.com/repository/gradle-plugin/") }
        maven { url = java.net.URI("https://maven.aliyun.com/repository/public/") }
        maven { url = java.net.URI("https://maven.aliyun.com/repository/google/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = java.net.URI("https://maven.aliyun.com/repository/public/") }
        maven { url = java.net.URI("https://maven.aliyun.com/repository/google/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "PickAudio"
include(":app")
