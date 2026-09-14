import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.onebusaway:onebusaway-gtfs:14.2.3")
    implementation("org.apache.commons:commons-csv:1.14.1")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
}

application {
    mainClass = "mobilityfeeds.MainKt"
}
