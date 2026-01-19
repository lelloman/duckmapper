pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "duckmapper"

include(":annotations")
include(":ksp")
include(":test-domain")
include(":test-ui")
include(":test-app")
