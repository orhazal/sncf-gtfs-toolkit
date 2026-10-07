plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("org.mobilitydata:gtfs-realtime-bindings:0.2.0")
    implementation("org.apache.commons:commons-csv:1.14.1")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "transitdatalab.rtbridge.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
