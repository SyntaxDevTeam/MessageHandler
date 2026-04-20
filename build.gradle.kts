plugins {
    kotlin("jvm") version "2.4.0-Beta1" apply false
    id("com.gradleup.shadow") version "9.4.1" apply false
    id("org.jetbrains.dokka-javadoc") version "2.2.0" apply false
}

group = "pl.syntaxdevteam"
version = "1.2.0-R0.2-SNAPSHOT"

tasks.wrapper {
    distributionType = Wrapper.DistributionType.BIN
}

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc-repo"
        }
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            name = "spigotmc-repo"
        }
        maven("https://oss.sonatype.org/content/groups/public/") {
            name = "sonatype"
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka-javadoc")

    val javadocOutputDir = layout.buildDirectory.dir("dokka/javadoc")
    extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
        dokkaPublications.named("javadoc") {
            outputDirectory.set(javadocOutputDir)
        }
    }

    val dokkaJavadoc by tasks.named("dokkaGeneratePublicationJavadoc")
    val javadocJar by tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        dependsOn(dokkaJavadoc)
        from(javadocOutputDir)
    }

    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            artifact(javadocJar)
        }
    }
}
