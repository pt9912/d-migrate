plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

allprojects {
    group = "dev.dmigrate"
    version = "0.2.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
        "testImplementation"("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

dependencies {
    kover(project(":d-migrate-core"))
    kover(project(":d-migrate-formats"))
    kover(project(":d-migrate-cli"))
}
