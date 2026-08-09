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
    // GraalVM Native Image (docs/planning/done/graalvm-native-image-distribution.md).
    // Nur `nativeCompile`/`nativeRun` brauchen eine GraalVM-Toolchain; der normale Build (JDK 21) ist
    // unberührt. Bis Phase D (GraalVM in CI) wird `nativeCompile` nur lokal ausgeführt.
    id("org.graalvm.buildtools.native") version "1.1.5"
}

application {
    applicationName = "d-migrate"
    mainClass.set("dev.dmigrate.cli.MainKt")
}

// Native-Image-Entrypoint: die VOLLE CLI `dev.dmigrate.cli.MainKt` — derselbe Einstieg wie der
// JVM-Fat-JAR (application.mainClass oben). Bis Phase F.1 gab es hier einen `-PnativeEntrypoint`-
// Schalter (core = reduzierter NativeMain, full = MainKt); F.1 hat ihn zurueckgebaut, seither ist
// `full` der einzige Bau (docs/planning/done/graalvm-native-image-distribution.md).

// Diagnose-Schalter fuer Phase F.0: `-PnativeMissingRegistrationMode=Warn`.
//
// BEFUND F.0-Runde 2: WIRKUNGSLOS fuer den hier auftretenden Fall. Die Option wird vom Builder
// akzeptiert (Build-Log: "origin(s): command line"), aendert aber nichts an den geworfenen
// `MissingReflectionRegistrationError`s aus `forQueriedOnlyExecutable`. Die Hoffnung, damit alle
// Luecken in EINEM Lauf zu erheben statt Schicht fuer Schicht, hat sich nicht erfuellt.
//
// Der Schalter bleibt, weil er fuer andere Fehlerklassen greifen kann und der Messapparat sonst
// wieder von Hand zusammengesetzt werden muesste. Ausdruecklich NUR fuer Messlaeufe: im
// ausgelieferten Binary muss eine fehlende Registrierung hart scheitern, nicht stillschweigend
// weiterlaufen. Deshalb opt-in: im ausgelieferten Binary (`MainKt`) muss eine fehlende
// Registrierung hart scheitern, nicht stillschweigend weiterlaufen.
val nativeMissingRegistrationMode = providers.gradleProperty("nativeMissingRegistrationMode").orNull

// Bau-Ressourcen (Begruendung an der Verwendungsstelle).
val nativeMaxRamPercentage = providers.gradleProperty("nativeMaxRamPercentage").getOrElse("80.0")
// Bewusst OHNE Default: `--parallelism` fest zu setzen hilft nur der Zeitreproduzierbarkeit, die
// hier niemand braucht — auf CI-Runnern (~4 Kerne) wuerde ein fixes 8 ueberzeichnen. Ohne die
// Option waehlt native-image die Kernzahl der jeweiligen Maschine, was ueberall das Richtige ist.
val nativeParallelism = providers.gradleProperty("nativeParallelism").orNull

graalvmNative {
    // Keine GraalVM-Toolchain-Suche zur Konfigurationszeit — hält den JDK-21-Build (ohne GraalVM) grün.
    toolchainDetection.set(false)

    // GraalVM Reachability Metadata Repository: gepflegte Metadaten fuer verbreitete Bibliotheken.
    //
    // BEFUND: zeigte mit Plugin 0.10.3 KEINE messbare Wirkung — kein GRMR-Hinweis im Build-Log,
    // weder ohne noch mit gepinnter `version`. **Die naheliegende Erklaerung war falsch:** GRMR
    // deckt unsere HikariCP-Version (6.2.1) sehr wohl ab, und seine Metadaten enthalten genau den
    // Eintrag `com.zaxxer.hikari.HikariConfig -> methods`, der den Blocker behoben haette.
    //
    // Aufgeklaerte Kette: Plugin 0.10.3 (2024) tat gar nichts mit dem Repository. Plugin 1.1.5 loest
    // es auf und meldete zuerst "Requires GraalVM Reachability Metadata 0.3.33 or newer" (der Pin
    // 0.3.15 war unbesehen aus einem Vergleichsprojekt uebernommen), dann mit GRMR 1.0.7
    // "provides a reachability-metadata schema, but your GraalVM installation does not" — daher
    // GraalVM 25.
    //
    // ERGEBNIS trotzdem ernuechternd: unter GraalVM 25 laeuft GRMR fehlerfrei, steuert fuer uns aber
    // NICHTS bei. Gemessen mit erzwungenem DEBUG-Logging (das den reflektiven Hikari-Pfad ausloest):
    // ohne unsere handgepflegte Registrierung bricht das Binary weiterhin mit
    // "Cannot reflectively invoke method HikariConfig.isAllowPoolSuspension()". Die
    // GRMR-Metadaten enthalten den passenden Eintrag zwar, er wird aber nicht wirksam.
    // Bleibt aktiviert (kostet nichts), ersetzt jedoch KEINE eigene Konfiguration.
    metadataRepository {
        version.set("1.0.7")
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("d-migrate")
            mainClass.set("dev.dmigrate.cli.MainKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("--initialize-at-build-time=ch.qos.logback,org.slf4j")
            // i18n-Bundles der CLI (MessageResolver -> ResourceBundle.getBundle("messages.messages")).
            // Ohne diese Registrierung stirbt JEDES Subkommando in Phase F.0 an
            // MissingResourceException, noch im Clikt-Dispatch — der Blocker maskiert alle weiteren.
            // Belegt durch Messlauf 29722018906 (identisch auf Linux/macOS/Windows).
            // Vorlaeufig als buildArg: haelt das Fat-JAR unberuehrt. Die dauerhafte Form
            // (committetes reachability-metadata vs. buildArg) entscheidet Phase F.2.
            // Experimentelle -H:-Optionen freischalten. Ohne das warnt der Builder ("must be enabled
            // via -H:+UnlockExperimentalVMOptions in the future") und wendet sie moeglicherweise
            // nicht an — ein Verdacht fuer die Wirkungslosigkeit von MissingRegistrationReportingMode
            // in F.0-Runde 2.
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:IncludeResourceBundles=messages.messages")

            // d-migrate liest und schreibt Schemata mit expliziter `encoding:`-Angabe und
            // transferiert Daten zwischen Dialekten. Ohne diese Option enthaelt das Image nur die
            // Default-Charsets, und eine Nicht-UTF-8-Quelle scheitert erst zur Laufzeit.
            buildArgs.add("-H:+AddAllCharsets")

            // http/https-URL-Protokolle einschalten. native-image aktiviert per Default nur
            // file/resource ("available on demand: http,https" im Build-Output). Der AWS-SDK-
            // S3-Endpoint-Provider parst die Endpoint-URL ueber java.net.URL und scheitert sonst
            // nativ mit "Custom endpoint http://... was not a valid URI" — der `data`-/`mcp`-Pfad
            // mit `artifacts.store: s3` bricht dann bei der ERSTEN echten S3-Operation ab.
            // (Belegt 2026-07-20 ueber McpS3SubprocessE2ETest gegen das Native-Binary.)
            buildArgs.add("--enable-url-protocols=http,https")

            // Speicherbudget des Builders. Ohne die Option nimmt native-image einen konservativen
            // Anteil (lokal 8,62 GB von 31 GB) und GCt sich dumm: 319 GCs / 41,5 s gegen 134 GCs /
            // 11,3 s mit Deckel; der native-image-Schritt fiel lokal von ~5 min auf 1m40s.
            //
            // 80 %, NICHT 60 %: ein erster Anlauf mit 60 % war auf meiner 31-GB-Maschine grosszuegig
            // (16,57 GB), auf dem macOS-Runner aber schaedlich — der hat 7 GB und 3 Kerne, 60 %
            // ergaben 3,74 GB, und der Bau verbrachte **45,9 % seiner Gesamtzeit in 253 GCs**
            // (Lauf 29727572204, 26m20s, Peak RSS nur 2,21 GB). GraalVM bat im Log ausdruecklich um
            // mehr Speicher. Ein fester Prozentsatz wirkt auf kleinen Maschinen ganz anders als auf
            // grossen — deshalb hoch genug, dass auch der kleinste Runner atmen kann.
            buildArgs.add("-J-XX:MaxRAMPercentage=$nativeMaxRamPercentage")
            if (nativeParallelism != null) {
                buildArgs.add("--parallelism=$nativeParallelism")
            }

            if (nativeMissingRegistrationMode != null) {
                buildArgs.add("-H:MissingRegistrationReportingMode=$nativeMissingRegistrationMode")
            }
        }
    }
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
    // Parquet Cut A S6: CLI wires ParquetSeekableDataChunkReaderFactory into
    // StreamingImporter and ParquetChunkWriterFactory into the export
    // composite (AP12 §5.1, §5.2). CLI is the production consumer of
    // Parquet; MCP stays parquet-free until a dedicated milestone.
    implementation(project(":adapters:driven:formats-parquet"))
    implementation(project(":adapters:driven:integrations"))
    // LF-012 / LN-011 / LN-017 / LN-027: persistent MCP server-state adapters for production
    // metadata (IdempotencyStore, JobStore, JobStartTransaction, Quota).
    implementation(project(":adapters:driven:persistence-jdbc"))
    implementation(project(":adapters:driven:streaming"))
    implementation(project(":adapters:driven:audit-logging"))
    implementation(project(":adapters:driven:text-icu"))
    // LF-012 / LN-038: secret-freier Connection-Bootstrap.
    // Sowohl der CLI- als auch der MCP-Pfad (über McpCliRuntimeWiring)
    // konsumieren denselben YamlConnectionReferenceLoader.
    implementation(project(":adapters:driven:connection-config"))
    // §6.11: `mcp serve`-Subkommando wrappt McpServerBootstrap.
    implementation(project(":adapters:driving:mcp"))
    // AP 6.21: `mcp serve` constructs file-backed byte-stores for
    // uploads (`FileBackedUploadSegmentStore`) and artefact content
    // (`FileBackedArtifactContentStore`) under the resolved state dir.
    implementation(project(":adapters:driven:storage-file"))
    // ImpPlan-0.9.8-object-storage-s3 S3.4b: `artifacts.store: s3` in der
    // `.d-migrate.yaml` selektiert die S3-Byte-Stores im MCP-Wiring
    // (ArtifactsConfigLoader + S3ClientFactory + die beiden S3-Stores).
    implementation(project(":adapters:driven:storage-s3"))
    // AP 6.21 + LF-012 / LN-011 / LN-017 / LN-027: default (in-memory) metadata stores;
    // `server.state.*` opt-in switches server-state Job/Quota/Idempotency metadata to JDBC.
    // Befund 17: bezogen aus dem echten Adapter-Modul (kein testFixtures-/kotest-Leak
    // in den Distributions-Shadow-Jar).
    implementation(project(":adapters:driven:persistence-memory"))
    implementation("com.github.ajalt.clikt:clikt:${rootProject.properties["cliktVersion"]}") {
        // JNA aus dem nativen Binary heraushalten (Akzeptanzkriterium "JNA unerreichbar"): clikt zieht
        // mordant-omnibus, das den JNA-Terminal-Provider (mordant-jvm-jna) per ServiceLoader
        // registriert. native-image macht alle ServiceLoader-Provider reachable → 40 com.sun.jna.*-
        // Klassen landen im Binary, obwohl mordant auf JDK 21 den FFM-/graal-ffi-Provider wählt und JNA
        // nie aufruft (verifiziert 2026-07-21 per -H:+PrintAnalysisCallTree). Ausschluss des
        // JNA-Providers + der jna-Bibliothek macht JNA truly unreachable (Akzeptanzkriterium); die
        // Binärgröße ändert sich dabei nur marginal (~0,9 MB), der Wert ist die Unreachability. Die
        // FFM-/graal-ffi-Backends bleiben, die Terminal-Ausgabe ist unverändert.
        exclude(group = "net.java.dev.jna", module = "jna")
        exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-jna")
    }
    implementation("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")
    // .d-migrate.yaml-Loader (LF-012 / LN-038 — minimaler NamedConnectionResolver)
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")
    testImplementation("com.google.code.gson:gson:2.14.0")
    // Test-only ports-common Fakes (z. B. FakeUnicodeTextService in
    // OutputFormatterTest). Bewusst test-scope — kein testFixtures-/kotest-Leak
    // in den Distributions-Shadow-Jar (Befund 17).
    testImplementation(testFixtures(project(":hexagon:ports-common")))

    // Testcontainers-, Gson- und JSON-Schema-Validator-Test-Dependencies
    // wurden mit den E2E- und MCP-Scenario-Specs nach :test:e2e-cli
    // ausgelagert (Phase C des Specs-Move).
}

tasks.named<Zip>("distZip") {
    archiveFileName.set(releaseZipName)
}

tasks.named<Tar>("distTar") {
    archiveFileName.set(releaseTarName)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set(releaseJarName)
    // Parquet/Hadoop-Transitive bringen den Fat-Jar ueber das 65535-
    // Entries-Limit des klassischen ZIP-Headers. Zip64 ist von JDK 7+
    // out-of-the-box lesbar; kein Kompat-Risiko fuer unsere
    // Java-17-Min-Baseline.
    isZip64 = true
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


kover {
    reports {
        filters {
            excludes {
                // Thin Clikt command shells — all logic is in Runners and/or
                // Clikt-free Wiring objects. Commands only parse flags and
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
                    "dev.dmigrate.cli.commands.SchemaMigrateCommand*",
                    "dev.dmigrate.cli.commands.SchemaRollbackCommand*",
                    // `mcp` / `mcp serve` shells — same thin-shell rationale:
                    // option parsing + McpServeOptions mapping, all logic in
                    // McpServeRunner (unit-tested) and DefaultMcpServeLauncher
                    // (integration-bound, excluded below). Real wiring is
                    // smoke-exercised by CliMcpServeSmokeTest.
                    "dev.dmigrate.cli.commands.McpCommand*",
                    "dev.dmigrate.cli.commands.McpServeCommand*",
                    // Phase E.6: thin wiring helpers — Hikari + JDBC integration-bound;
                    // tested via :test:integration-server-state in Phase F.
                    "dev.dmigrate.cli.commands.JdbcMigrationExecutor*",
                    "dev.dmigrate.cli.commands.MigrateRendererRegistry*",
                    "dev.dmigrate.cli.commands.DataExportCommand*",
                    "dev.dmigrate.cli.commands.DataImportCommand*",
                    "dev.dmigrate.cli.commands.DataTransferCommand*",
                    "dev.dmigrate.cli.commands.SchemaCommand*",
                    "dev.dmigrate.cli.commands.DataCommand*",
                    // `config` / `config show` / `config credentials set`/`list`
                    // shells — flag parsing + console I/O (readPassword) only. The
                    // pure confirm/mismatch logic is extracted to the unit-tested
                    // `confirmedSecret` (MasterSecretResolver.kt); the set/list
                    // behaviour lives in the Clikt-free ConfigCredentialsWiring +
                    // CredentialCommandRunner (both unit-tested); the `config show`
                    // rendering lives in the Clikt-free ConfigShowRenderer (unit-
                    // tested); the top-level `rootConfigPath` (ConfigCommandsKt) is
                    // thin Clikt-context glue.
                    "dev.dmigrate.cli.commands.ConfigCommand*",
                    "dev.dmigrate.cli.commands.ConfigShowCommand*",
                    "dev.dmigrate.cli.commands.ConfigCredentialsCommand*",
                    "dev.dmigrate.cli.commands.ConfigCredentialsSetCommand*",
                    "dev.dmigrate.cli.commands.ConfigCredentialsListCommand*",
                    // Hikari/Flyway/Postgres-Default — pro Definition
                    // integrationstest-bound (Hikari validiert beim
                    // Konstruktor mit `initializationFailTimeout=1ms`).
                    // Tests substituieren diese Factory mit Fakes via
                    // `McpServeWiring(serverStateFactory = ...)`. Real-Coverage
                    // entsteht im :test:integration-server-state-Modul.
                    "dev.dmigrate.cli.commands.DefaultServerStateFactory*",
                    // Blocking, multi-threaded server start (in-process MCP
                    // server + retention/finalisation sweep loops). Its line
                    // coverage was produced only by the in-process
                    // CliMcpServeSmokeTest run, whose multi-fork registration
                    // is timing-sensitive under CI's parallel execution and
                    // intermittently dropped the cli module below 90%
                    // (koverVerify flake, 2026-06-18). McpServeRunner takes it
                    // as an injectable seam so the lifecycle orchestration
                    // stays deterministically unit-covered; the launcher's real
                    // coverage lives in :test:integration-server-state.
                    "dev.dmigrate.cli.commands.DefaultMcpServeLauncher*",
                    // SQLite live probes open real JDBC/Hikari connections;
                    // runner-stage behaviour is unit-tested in :hexagon:application,
                    // connection behaviour belongs to integration coverage.
                    "dev.dmigrate.cli.commands.SqliteLiveCatalogProbeRunner*",
                    "dev.dmigrate.cli.commands.SqliteCastPreflightProbeRunner*",
                    // 0.9.7 preserve-current-value Sub-Slice D: thin
                    // dialect-dispatcher that opens a Hikari pool per
                    // probe call and routes to PG/MySQL probe adapters.
                    // Routing logic + skip behaviour is unit-tested in
                    // :hexagon:application (SequencePreserveStageTest /
                    // SchemaMigrateRunnerSequencePreserveTest); the
                    // pool + dialect dispatch itself belongs to
                    // integration coverage like its SQLite analogues.
                    "dev.dmigrate.cli.commands.SequenceCurrentValueProbeRunner*",
                    // Private data class for the excluded ExportCommand*
                    // shells; carries no behaviour beyond field accessors.
                    "dev.dmigrate.cli.commands.ExportParams*",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
