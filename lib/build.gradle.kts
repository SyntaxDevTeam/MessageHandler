plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.adventure.api)

    implementation(libs.adventure.minimessage)
    implementation(libs.adventure.serializer.legacy)
    implementation(libs.adventure.serializer.plain)
    implementation(libs.caffeine)
    implementation(libs.coroutines)
    implementation(libs.snakeyaml)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
