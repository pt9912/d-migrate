plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

fun normalizedReleaseVersion(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    val normalized = candidate.removePrefix("v")
    val semverLike = Regex("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")
    return normalized.takeIf { semverLike.matches(it) }
}

val defaultProjectVersion = "0.9.4-SNAPSHOT"
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

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
        "testImplementation"("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
        "testImplementation"("io.mockk:mockk:${rootProject.properties["mockkVersion"]}")
        // SLF4J-Provider für Tests, damit Testcontainers-Diagnostics nicht im
        // NOP-Logger verschwinden. Ohne dieses Fragment ist die
        // Strategy-Detection-Fehlermeldung "Could not find a valid Docker
        // environment" nicht diagnostizierbar (siehe Phase F Debug-Session).
        "testRuntimeOnly"("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        val explicitKotestTags = System.getProperty("kotest.tags")
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
        //
        // Perf-Spikes (`perf`) sind ebenfalls opt-in und laufen nur, wenn
        // `-Dkotest.tags=perf` (oder ein anderes explizites Tag-Filter) gesetzt
        // wird. Ein explizit gesetzter `kotest.tags`-Wert gewinnt immer gegen
        // den Default hier.
        if (explicitKotestTags == null && !project.hasProperty("integrationTests")) {
            systemProperty("kotest.tags", "!integration & !perf")
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
    }

    // Ensure koverVerify always runs after test and is never served from
    // build cache — prevents stale coverage data from prior Gradle invocations.
    tasks.matching { it.name == "koverVerify" || it.name == "koverCachedVerify" }.configureEach {
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
    kover(project(":adapters:driven:formats"))
    kover(project(":adapters:driven:streaming"))
    kover(project(":adapters:driving:cli"))
    kover(project(":test:integration-postgresql"))
    kover(project(":test:integration-mysql"))
    kover(project(":test:consumer-read-probe"))
}

// Root-level aggregated koverVerify: when run with -PintegrationTests
// this verifies the FULL codebase (including JDBC-only profiling
// adapters that are excluded from per-module unit-test koverVerify)
// at 90%. The integration CI (.github/workflows/integration.yml and
// scripts/test-integration-docker.sh) runs :koverVerify explicitly.
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

tasks.register("verifyDocRefs") {
    group = "verification"
    description = "Checks that markdown link targets in docs/*.md exist."

    doLast {
        val linkPattern = Regex("""\[.*?]\(((?!https?://)[^)#\s]+)""")
        val docsDir = rootDir.resolve("docs")
        var broken = 0

        docsDir.walk()
            .filter { it.extension == "md" }
            .sorted()
            .forEach { md ->
                linkPattern.findAll(md.readText())
                    .map { it.groupValues[1] }
                    .distinct()
                    .forEach { target ->
                        val resolved = if (target.startsWith("/")) {
                            File(target)
                        } else {
                            md.parentFile.resolve(target)
                        }
                        if (!resolved.exists()) {
                            logger.lifecycle("BROKEN: ${md.relativeTo(rootDir)} -> $target")
                            broken++
                        }
                    }
            }

        if (broken > 0) {
            throw GradleException("$broken broken doc link(s) in docs/")
        }
        logger.lifecycle("All doc links OK.")
    }
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
