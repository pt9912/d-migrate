import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import java.io.File
import java.security.MessageDigest

plugins {
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.cloud.tools.jib") version "3.4.5"
}

application {
    applicationName = "d-migrate"
    mainClass.set("dev.dmigrate.cli.MainKt")
}

val releaseVersion = project.version.toString()
val releaseZipName = "d-migrate-$releaseVersion.zip"
val releaseTarName = "d-migrate-$releaseVersion.tar"
val releaseJarName = "d-migrate-$releaseVersion-all.jar"
val releaseShaName = "d-migrate-$releaseVersion.sha256"
val releaseDir = layout.buildDirectory.dir("release")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
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

tasks.named<Zip>("distZip") {
    archiveFileName.set(releaseZipName)
}

tasks.named<Tar>("distTar") {
    archiveFileName.set(releaseTarName)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set(releaseJarName)
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

val stageReleaseAssets = tasks.register<Sync>("stageReleaseAssets") {
    group = "distribution"
    description = "Collect canonical release assets in build/release."
    dependsOn("distZip", "distTar", "shadowJar")
    into(releaseDir)
    from(tasks.named<Zip>("distZip").flatMap { it.archiveFile })
    from(tasks.named<Tar>("distTar").flatMap { it.archiveFile })
    from(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
}

val writeReleaseChecksums = tasks.register("writeReleaseChecksums") {
    group = "distribution"
    description = "Write SHA256 checksums for the staged release assets."
    dependsOn(stageReleaseAssets)
    outputs.file(releaseDir.map { it.file(releaseShaName) })
    doLast {
        val directory = releaseDir.get().asFile
        val assets = directory.listFiles()
            ?.filter { it.isFile && it.name != releaseShaName }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (assets.isEmpty()) {
            throw GradleException("No staged release assets found in ${directory.absolutePath}")
        }
        val checksumFile = directory.resolve(releaseShaName)
        val content = assets.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) {
            "${sha256(it)}  ${it.name}"
        }
        checksumFile.writeText(content)
    }
}

tasks.register("assembleReleaseAssets") {
    group = "distribution"
    description = "Build ZIP, TAR, fat JAR, and SHA256 into build/release."
    dependsOn(writeReleaseChecksums)
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
                // (docs/archive/implementation-plan-0.3.0.md §11) begründete sich mit
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
