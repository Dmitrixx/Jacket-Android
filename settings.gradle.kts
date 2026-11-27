pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Lampa"
include(":app")
include(":proot-engine")
project(":proot-engine").projectDir = file("proot-engine")

include(":byedpi-integration")
project(":byedpi-integration").projectDir = file("byedpi-integration")
