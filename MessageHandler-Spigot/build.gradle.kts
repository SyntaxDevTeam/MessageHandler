description = "MessageHandler implementation for Bukkit and Spigot servers."

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "spigotmc-repo"
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:26.1-R0.1-SNAPSHOT")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("net.kyori:adventure-text-serializer-legacy:5.0.0")
    implementation("net.kyori:adventure-text-minimessage:5.0.0")
    implementation("net.kyori:adventure-text-serializer-plain:5.0.0")
    implementation("net.kyori:adventure-text-serializer-ansi:5.0.0")
    implementation("net.kyori:adventure-api:5.0.0")
    implementation("net.kyori:adventure-key:5.0.0")
    implementation("net.kyori:adventure-platform-api:4.4.1")
    implementation("net.kyori:adventure-platform-bukkit:4.4.1")
    implementation("net.kyori:adventure-platform-facet:4.4.1")
    implementation("net.kyori:adventure-text-minimessage:5.0.0")
    implementation("net.kyori:adventure-text-serializer-json:5.0.0")
    implementation("net.kyori:adventure-text-serializer-gson:5.0.0")
    implementation("net.kyori:adventure-text-serializer-legacy:5.0.0")
    implementation("net.kyori:adventure-text-serializer-plain:5.0.0")
    implementation("net.kyori:adventure-text-serializer-ansi:5.0.0")
    implementation("net.kyori:examination-api:1.3.0")
    implementation("net.kyori:examination-string:1.3.0")
    implementation("net.kyori:option:1.1.0")
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
            artifactId = "messageHandler-spigot"
            artifact(tasks.named("shadowJar").get()) {
                classifier = null
            }
            artifact(sourcesJar.get())

            pom {
                name.set("MessageHandler-Spigot")
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
