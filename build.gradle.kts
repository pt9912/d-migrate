import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

fun normalizedReleaseVersion(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    val normalized = candidate.removePrefix("v")
    val semverLike = Regex("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")
    return normalized.takeIf { semverLike.matches(it) }
}

val defaultProjectVersion = "0.9.7-SNAPSHOT"
val resolvedProjectVersion =
    normalizedReleaseVersion(findProperty("releaseVersion")?.toString())
        ?: normalizedReleaseVersion(System.getenv("DMIGRATE_VERSION"))
        ?: defaultProjectVersion

allprojects {
    group = "dev.dmigrate"
    version = resolvedProjectVersion

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        ignoreFailures = false
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = project.file("detekt-baseline.xml")
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }

    tasks.named("check") {
        dependsOn("detekt")
    }

    dependencies {
        "testImplementation"("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
        "testImplementation"("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
        "testImplementation"("io.mockk:mockk:${rootProject.properties["mockkVersion"]}")
        // SLF4J-Provider für Tests, damit Testcontainers-Diagnostics nicht im
        // NOP-Logger verschwinden. Ohne dieses Fragment ist die
        // Strategy-Detection-Fehlermeldung "Could not find a valid Docker
        // environment" nicht diagnostizierbar (siehe LF-008 / LF-009 / LF-013 Debug-Session).
        "testRuntimeOnly"("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    }

    // Sub-Projekte unter :test:integration-* + :test:e2e-cli starten ihre
    // Test-Tasks nur unter -PintegrationTests. Strukturell aequivalent zum
    // Kotest-Tag-Filter weiter unten, aber spart Test-JVM-Startup und
    // Test-Discovery, wenn die Property fehlt.
    val isIntegrationProject = path.startsWith(":test:integration-") || path == ":test:e2e-cli"

    tasks.withType<Test> {
        if (isIntegrationProject) {
            onlyIf("requires -PintegrationTests") { project.hasProperty("integrationTests") }
        }
        useJUnitPlatform()
        val explicitKotestTags = System.getProperty("kotest.tags")
        // Perf-Spikes (`perf`) sind opt-in und laufen nur, wenn
        // `-Dkotest.tags=perf` (oder ein anderes explizites Tag-Filter) gesetzt
        // wird. Ein explizit gesetzter `kotest.tags`-Wert gewinnt immer gegen
        // den Default hier.
        //
        // Integration-Tests sind nicht mehr per Kotest-Tag gefiltert —
        // sie leben strukturell in :test:integration-* und :test:e2e-cli
        // und werden via Sub-Projekt-onlyIf nur unter -PintegrationTests
        // ausgefuehrt (siehe oben).
        if (explicitKotestTags == null) {
            systemProperty("kotest.tags", "!perf")
        } else {
            systemProperty("kotest.tags", explicitKotestTags)
        }

        // Forked Test-JVM Heap: Default ~512 MB reicht fuer die schnellen
        // Unit-Specs, nicht aber fuer den Integrations-Pfad (Testcontainers +
        // JDBC-Treiber + parallele Kotest-Specs). Wer eigene Grenzen setzen
        // will, uebergibt `-PtestMaxHeapSize=Xg`.
        val integrationHeap = (project.findProperty("testMaxHeapSize") as String?)
            ?: if (project.hasProperty("integrationTests")) "4g" else null
        if (integrationHeap != null) {
            maxHeapSize = integrationHeap
        }

        // Kover consumes execution data produced by the actual test run.
        // Restoring test outputs from the build cache can leave coverage
        // verification with stale or incomplete counters on CI.
        outputs.cacheIf { false }

        // Forward UPDATE_GOLDEN to the forked test JVM so golden-pinned
        // tests (McpToolSchemasGoldenTest, AP-6.24-Goldens, …)
        // regenerate via `gradle -DUPDATE_GOLDEN=true ...` without manual
        // env wiring per task.
        val updateGolden = System.getProperty("UPDATE_GOLDEN") ?: System.getenv("UPDATE_GOLDEN")
        if (updateGolden != null) {
            systemProperty("UPDATE_GOLDEN", updateGolden)
        }

        // Quality-Coverage-Expansion Phase A: forward the `perfGate`
        // Gradle project property (from `make docker-perf PERF_GATE=true`
        // / `-PperfGate=true`) into the forked test JVM as the system
        // property `d-migrate.perf.gate`. PerfReport.write reads this
        // property and turns baselineMs into a hard assertion. Without
        // this bridge the operator-visible PERF_GATE switch is a
        // silent no-op — review finding #1.
        if (project.hasProperty("perfGate")) {
            systemProperty("d-migrate.perf.gate", project.property("perfGate").toString())
        }

        // Surface full assertion messages on failure across every test
        // task in the project. Default Gradle test logging only prints
        // "AssertionFailedError at File.kt:NN" without the message,
        // which makes Kotest `withClue { }` text and any structured
        // diagnostic carried in an exception body opaque — F.4's
        // SQLite-rebuild round-trip drift was the forcing function for
        // wiring this up. `events("failed")` keeps passing runs quiet.
        testLogging {
            events("failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<Test>().configureEach {
        dependsOn("detekt")
    }

    // Ensure kover verification and artifact tasks always run after test
    // and are never served from build cache — prevents stale coverage
    // data from prior Gradle invocations. Excludes koverFindJar (needed
    // by test itself) to avoid circular dependency.
    val koverNoCacheTasks = setOf(
        "koverVerify", "koverCachedVerify",
        "koverGenerateArtifact", "koverGenerateArtifactJvm",
    )
    tasks.matching { it.name in koverNoCacheTasks }.configureEach {
        mustRunAfter(tasks.named("test"))
        outputs.cacheIf { false }
    }
}

dependencies {
    kover(project(":hexagon:ports-common"))
    kover(project(":hexagon:ports-read"))
    kover(project(":hexagon:ports-write"))
    kover(project(":hexagon:ports"))
    kover(project(":hexagon:application"))
    kover(project(":hexagon:core"))
    kover(project(":hexagon:profiling"))
    kover(project(":adapters:driven:driver-common"))
    kover(project(":adapters:driven:driver-postgresql"))
    kover(project(":adapters:driven:driver-postgresql-profiling"))
    kover(project(":adapters:driven:driver-mysql"))
    kover(project(":adapters:driven:driver-mysql-profiling"))
    kover(project(":adapters:driven:driver-sqlite"))
    kover(project(":adapters:driven:driver-sqlite-profiling"))
    kover(project(":adapters:driven:audit-logging"))
    kover(project(":adapters:driven:formats"))
    kover(project(":adapters:driven:persistence-jdbc"))
    kover(project(":adapters:driven:storage-file"))
    kover(project(":adapters:driven:streaming"))
    kover(project(":adapters:driven:text-icu"))
    kover(project(":adapters:driving:cli"))
    kover(project(":adapters:driving:mcp"))
    kover(project(":test:integration-postgresql"))
    kover(project(":test:integration-mysql"))
    kover(project(":test:integration-sqlite"))
    kover(project(":test:integration-server-state"))
    kover(project(":test:integration-persistence-jdbc"))
    kover(project(":test:integration-integrations"))
    kover(project(":test:consumer-read-probe"))
    kover(project(":test:e2e-cli"))
    // Quality-Coverage-Expansion Sub-Slice E.3:
    // :test:cross-dialect-matrix, :test:integration-concurrency und
    // :test:perf-large-schema sind bewusst NICHT aggregiert
    // (file-mode sweep / opt-in concurrency / tag-gated perf — kein
    // produktiver Code, nur Regressions-/Reproducer-Pfade). Begruendung
    // im `aggregate-carveout:`-Block von
    // `docs/coverage/excludes-ledger.md`.
}

tasks.register("resolveAllDependencies") {
    group = "build setup"
    description = "Resolves all resolvable configurations across all projects to warm the Gradle dependency cache."

    doLast {
        allprojects.forEach { project ->
            project.configurations
                .filter { it.isCanBeResolved }
                .forEach { configuration ->
                    logger.lifecycle("Resolving ${project.path}:${configuration.name}")
                    configuration.resolve()
                }
        }
    }
}
