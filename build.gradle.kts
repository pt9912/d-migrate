plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

fun normalizedReleaseVersion(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    val normalized = candidate.removePrefix("v")
    val semverLike = Regex("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")
    return normalized.takeIf { semverLike.matches(it) }
}

val defaultProjectVersion = "0.6.0-SNAPSHOT"
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
    }
}

dependencies {
    kover(project(":hexagon:ports"))
    kover(project(":hexagon:application"))
    kover(project(":hexagon:core"))
    kover(project(":adapters:driven:driver-common"))
    kover(project(":adapters:driven:driver-postgresql"))
    kover(project(":adapters:driven:driver-mysql"))
    kover(project(":adapters:driven:driver-sqlite"))
    kover(project(":adapters:driven:formats"))
    kover(project(":adapters:driven:streaming"))
    kover(project(":adapters:driving:cli"))
}
