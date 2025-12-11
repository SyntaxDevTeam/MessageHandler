plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
}

group = "pl.syntaxdevteam"
version = "1.0.1"

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
