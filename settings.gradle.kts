rootProject.name = "sncf-gtfs-toolkit"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("static-patcher", "rt-bridge")
