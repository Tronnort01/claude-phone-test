plugins {
    kotlin("jvm") version "2.0.10"
    kotlin("plugin.serialization") version "2.0.10"
    id("io.ktor.plugin") version "2.3.12"
    application
}

group = "com.stealthcalc.server"
version = "1.0.0"

application {
    mainClass.set("com.stealthcalc.server.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("io.ktor:ktor-server-auth:2.3.12")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")

    // Database
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.jetbrains.exposed:exposed-core:0.52.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.52.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.52.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Password hashing for tokens
    implementation("at.favre.lib:bcrypt:0.10.2")
}

ktor {
    fatJar {
        archiveFileName.set("stealthcalc-server.jar")
    }
}
