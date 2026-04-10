plugins {
    application
    id("com.google.cloud.tools.jib") version "3.4.5"
}

application {
    mainClass.set("dev.dmigrate.cli.MainKt")
}

dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:application"))
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":adapters:driven:driver-postgresql"))
    implementation(project(":adapters:driven:driver-mysql"))
    implementation(project(":adapters:driven:driver-sqlite"))
    implementation(project(":adapters:driven:formats"))
    implementation(project(":adapters:driven:streaming"))
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
                // 90% wie die übrigen Module. Die ursprüngliche 60%-Schwelle
                // (implementation-plan-0.3.0.md §11) begründete sich mit
                // "CLI-Code ist I/O-Glue und nur durch Integration-Tests
                // abdeckbar" — das war eine Bequemlichkeits-Ausrede. Der
                // eigentliche Glue (Hikari, File-I/O, Driver-Registry) ist
                // ≈10% des Moduls; der Rest sind Verzweigungen, Validierungen
                // und Exit-Code-Mappings, die über das Runner-Pattern
                // (DataExportRunner / SchemaGenerateRunner) mit Fakes
                // unit-testbar sind. Siehe `*HelpersTest`, `*RunnerTest`.
                minBound(90)
            }
        }
    }
}
