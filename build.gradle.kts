plugins {
    kotlin("jvm") version "2.2.20"
    `java-library`
    `maven-publish`
}

group = "pl.syntaxdevteam"
version = "1.0.0-SNAPSHOT"
description = "Standalone MessageHandler library extracted from SyntaxCore."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
    api("net.kyori:adventure-text-serializer-legacy:4.24.0")
    api("net.kyori:adventure-text-minimessage:4.24.0")
    api("net.kyori:adventure-text-serializer-plain:4.24.0")
    api("net.kyori:adventure-text-serializer-ansi:4.24.0")
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("messageHandler") {
            from(components["java"])
            pom {
                name.set("Syntax MessageHandler")
                description.set(project.description)
            }
        }
    }
}
