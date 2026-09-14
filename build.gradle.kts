import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
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

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_24
    }
}

// Toolchain JDK is 25 (runtime, needed to load onebusaway-gtfs 12.x Java-25 bytecode),
// but Kotlin caps bytecode output at 24 — pin Java compile target to match.
tasks.withType<JavaCompile> {
    options.release = 24
}
