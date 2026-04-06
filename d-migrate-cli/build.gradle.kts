plugins {
    application
    id("com.google.cloud.tools.jib") version "3.4.5"
}

application {
    mainClass.set("dev.dmigrate.cli.MainKt")
}

dependencies {
    implementation(project(":d-migrate-core"))
    implementation(project(":d-migrate-driver-api"))
    implementation(project(":d-migrate-driver-postgresql"))
    implementation(project(":d-migrate-driver-mysql"))
    implementation(project(":d-migrate-driver-sqlite"))
    implementation(project(":d-migrate-formats"))
    implementation(project(":d-migrate-streaming"))
    implementation("com.github.ajalt.clikt:clikt:${rootProject.properties["cliktVersion"]}")
    implementation("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")
    // .d-migrate.yaml-Loader (Plan §6.14 — minimaler NamedConnectionResolver)
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

    // Phase F (0.3.0): Testcontainers-basierte E2E-Tests für `data export`
    // gegen PostgreSQL und MySQL. Markiert mit Kotest's NamedTag("integration"),
    // läuft nur mit `-PintegrationTests` (siehe Plan §6.16).
    // 2.0.0 hat alle Module umbenannt: `org.testcontainers:postgresql` →
    // `org.testcontainers:testcontainers-postgresql` etc.
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
}

jib {
    from {
        image = "eclipse-temurin:21-jre-noble"
    }
    to {
        image = "dmigrate/d-migrate"
        tags = setOf("latest", project.version.toString())
    }
    container {
        mainClass = "dev.dmigrate.cli.MainKt"
        jvmFlags = listOf("-XX:+UseZGC", "-XX:+ZGenerational")
        workingDirectory = "/work"
        volumes = listOf("/work")
        labels = mapOf(
            "org.opencontainers.image.title" to "d-migrate",
            "org.opencontainers.image.description" to "Database-agnostic CLI tool for schema migration and data management",
            "org.opencontainers.image.source" to "https://github.com/pt9912/d-migrate",
            "org.opencontainers.image.licenses" to "MIT"
        )
    }
}

kover {
    reports {
        verify {
            rule {
                // TODO: Increase to 90% once CLI integration tests are added
                minBound(50)
            }
        }
    }
}
