pluginManagement {
    repositories {
        google()
        // مرآة Google لـ Maven Central (أسرع ولا تحدّ عدد الطلبات)
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "whatsapp-archiver-app"
include(":core", ":app")
