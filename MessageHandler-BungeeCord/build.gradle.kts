description = "MessageHandler implementation for BungeeCord proxy."

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "sonatype-snapshots"
    }
}

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.2.3")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.26.1")
    compileOnly("net.kyori:adventure-text-minimessage:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-ansi:4.26.1")
    implementation("org.yaml:snakeyaml:2.5")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn("shadowJar")
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("messageHandler") {
            artifactId = "messageHandler-bungeecord"
            artifact(tasks.named("shadowJar").get()) {
                classifier = null
            }
            artifact(sourcesJar.get())

            pom {
                name.set("MessageHandler-BungeeCord")
                description.set(project.description)
                url.set("https://github.com/SyntaxDevTeam/MessageHandler")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("WieszczY85")
                        name.set("WieszczY")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "Nexus"
            val releasesRepoUrl = uri("https://nexus.syntaxdevteam.pl/repository/maven-releases/")
            val snapshotsRepoUrl = uri("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = findProperty("nexusUser")?.toString()
                password = findProperty("nexusPassword")?.toString()
            }
        }
    }
}
