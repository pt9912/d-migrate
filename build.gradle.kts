plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

allprojects {
    group = "dev.dmigrate"
    version = "0.3.0"

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
        // SLF4J-Provider für Tests, damit Testcontainers-Diagnostics nicht im
        // NOP-Logger verschwinden. Ohne dieses Fragment ist die
        // Strategy-Detection-Fehlermeldung "Could not find a valid Docker
        // environment" nicht diagnostizierbar (siehe Phase F Debug-Session).
        "testRuntimeOnly"("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Per default integration-Tests ausschließen — sie brauchen Docker
        // (Testcontainers) und überschreiten das 5-Minuten-CI-Budget des
        // Default-Workflows. Aktivieren via `./gradlew test -PintegrationTests`
        // (siehe .github/workflows/integration.yml und Plan §6.16).
        //
        // Wir verwenden Kotest's natives Tag-System (System-Property
        // `kotest.tags`), weil JUnit Jupiter's `excludeTags`/`@Tag` Discovery
        // nicht mit Kotest's Spec-Lifecycle zusammenspielt — ohne das hier
        // würden Specs mit @Tags("integration") trotzdem instanziiert und
        // beforeSpec ausgeführt.
        if (!project.hasProperty("integrationTests")) {
            systemProperty("kotest.tags", "!integration")
        }
    }
}

dependencies {
    kover(project(":d-migrate-core"))
    kover(project(":d-migrate-driver-api"))
    kover(project(":d-migrate-driver-postgresql"))
    kover(project(":d-migrate-driver-mysql"))
    kover(project(":d-migrate-driver-sqlite"))
    kover(project(":d-migrate-formats"))
    kover(project(":d-migrate-streaming"))
    kover(project(":d-migrate-cli"))
}
