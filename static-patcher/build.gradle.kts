plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation("org.onebusaway:onebusaway-gtfs:14.2.3")
    implementation("org.apache.commons:commons-csv:1.14.1")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.5")
}

application {
    mainClass = "mobilityfeeds.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir // data/ in, download/ and output/ out, at the repository root
}
