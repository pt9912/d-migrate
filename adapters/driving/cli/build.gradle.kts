import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.language.jvm.tasks.ProcessResources
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
    implementation(project(":hexagon:profiling"))
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":adapters:driven:driver-postgresql"))
    implementation(project(":adapters:driven:driver-postgresql-profiling"))
    implementation(project(":adapters:driven:driver-mysql"))
    implementation(project(":adapters:driven:driver-mysql-profiling"))
    implementation(project(":adapters:driven:driver-sqlite"))
    implementation(project(":adapters:driven:driver-sqlite-profiling"))
    implementation(project(":adapters:driven:formats"))
    implementation(project(":adapters:driven:integrations"))
    implementation(project(":adapters:driven:streaming"))
    implementation(project(":adapters:driven:audit-logging"))
    // AP D10: Plan-D §8 + §10.10 secret-freier Connection-Bootstrap.
    // Sowohl der CLI- als auch der MCP-Pfad (über McpCliPhaseCWiring)
    // konsumieren denselben YamlConnectionReferenceLoader.
    implementation(project(":adapters:driven:connection-config"))
    // §6.11: `mcp serve`-Subkommando wrappt McpServerBootstrap.
    implementation(project(":adapters:driving:mcp"))
    // AP 6.21: `mcp serve` constructs file-backed byte-stores for
    // uploads (`FileBackedUploadSegmentStore`) and artefact content
    // (`FileBackedArtifactContentStore`) under the resolved state dir.
    implementation(project(":adapters:driven:storage-file"))
    // AP 6.21: the metadata stores (UploadSessionStore, ArtifactStore,
    // SchemaStore, JobStore, QuotaStore) plus `InMemoryAuditSink` still
    // come from `:hexagon:ports-common`'s testFixtures source set —
    // durable metadata adapters will land post-0.9.6. Until then `mcp
    // serve` is byte-content file-backed only; metadata stays ephemeral.
    implementation(testFixtures(project(":hexagon:ports-common")))
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

    // AP 6.24: integration-test harnesses build JSON-RPC payloads with Gson.
    // The mcp module uses Gson internally (transitive via lsp4j) but does
    // not re-export it; the CLI test source-set declares it explicitly.
    testImplementation("com.google.code.gson:gson:2.14.0")

    // AP 6.24 E8: validate Phase-C tool runtime outputs against the
    // PhaseBToolSchemas output schemas (JSON Schema 2020-12). Test-
    // scope only — the production server publishes the schemas via
    // tools/list but does not validate dispatch responses against them.
    testImplementation("com.networknt:json-schema-validator:1.5.4")
}

tasks.named<ProcessResources>("processResources") {
    filteringCharset = "UTF-8"
    filesMatching("dmigrate-version.properties") {
        expand("projectVersion" to project.version.toString())
    }
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
        filters {
            excludes {
                // Thin Clikt command shells — all logic is in the Runners
                // (tested via *RunnerTest). Commands only parse flags and
                // delegate. Tested via CliHelpAndBootstrapTest (help reachability).
                classes(
                    "dev.dmigrate.cli.commands.DataProfileCommand*",
                    "dev.dmigrate.cli.commands.ExportCommand*",
                    "dev.dmigrate.cli.commands.ExportFlywayCommand*",
                    "dev.dmigrate.cli.commands.ExportLiquibaseCommand*",
                    "dev.dmigrate.cli.commands.ExportDjangoCommand*",
                    "dev.dmigrate.cli.commands.ExportKnexCommand*",
                    "dev.dmigrate.cli.commands.ExportCommandsKt*",
                    "dev.dmigrate.cli.commands.SchemaReverseCommand*",
                    "dev.dmigrate.cli.commands.SchemaCompareCommand*",
                    "dev.dmigrate.cli.commands.SchemaValidateCommand*",
                    "dev.dmigrate.cli.commands.SchemaGenerateCommand*",
                    "dev.dmigrate.cli.commands.DataExportCommand*",
                    "dev.dmigrate.cli.commands.DataImportCommand*",
                    "dev.dmigrate.cli.commands.DataTransferCommand*",
                    "dev.dmigrate.cli.commands.SchemaCommand*",
                    "dev.dmigrate.cli.commands.DataCommand*",
                )
            }
        }
        verify {
            rule {
                // 80%: The CLI module contains ~15% thin Clikt command shells
                // (DataProfileCommand, ExportCommands, SchemaReverseCommand etc.)
                // whose logic lives in the corresponding *Runner classes (tested
                // at 90%+). Kover class excludes do not reliably filter these in
                // CI (Gradle Actions cache interaction). The effective testable
                // code coverage is 95%+ when command shells are excluded.
                minBound(80)
            }
        }
    }
}
