pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin-bignum"

includeBuild("build-logic")

include(":bignum")
include(":bignum-serialization-kotlinx")
