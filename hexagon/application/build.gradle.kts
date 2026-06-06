// hexagon:application — Use case runners (application layer).
// Depends only on hexagon:core and hexagon:ports, never on adapters.

plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:ports"))
    implementation(project(":hexagon:profiling"))

    // LF-012 / LN-011 / LN-017 / LN-027: slf4j-Facade fuer Worker-Thread-Uncaught-Logging im
    // BoundedAsyncJobExecutor. No-op ohne Provider; Tests/Runtime ziehen
    // logback-classic (root build.gradle.kts subprojects-Block).
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    testImplementation(project(":adapters:driven:integrations"))
    testImplementation(project(":adapters:driven:formats"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:streaming"))
    testImplementation(testFixtures(project(":hexagon:ports-common")))
    // Parquet Cut A S0b: DataChunkWriter.begin(table, columns)-Bridge-Extension.
    testImplementation(testFixtures(project(":hexagon:ports-write")))

    // LF-012 / LN-011 / LN-017 / LN-027 cancel-test fixtures (TestCancellationTokenSource).
    testImplementation(testFixtures(project(":hexagon:core")))

    // LF-012 / LN-011 / LN-017 / LN-027: ILoggingEvent fuer LogbackCapture.events. Logback ist
    // im subprojects-Block bereits testRuntimeOnly fuer alle Module —
    // hier erweitert auf testImplementation, damit der Test-Code den
    // Event-Typ direkt referenzieren kann.
    testImplementation("ch.qos.logback:logback-classic:${rootProject.properties["logbackVersion"]}")

    // LF-012 / LN-011 / LN-017 / LN-027: Contract-Test-Fixture fuer QuotaReservationOwnerStore
    // braucht Kotest fuer abstract FunSpec-Definition.
    testFixturesApi(project(":hexagon:ports-common"))
    // Migrate Phase F.2/F.3: `executeAgainstPool` test-helper signature uses
    // ConnectionPool (ports-common, already above) + MigrationDdlStatement
    // (ports-read), and returns ExecutionTrace (this module). The ports-read
    // dep is required because Gradle's testFixtures source set does not
    // inherit `implementation` deps' transitive `api` exports automatically.
    testFixturesApi(project(":hexagon:ports-read"))
    // Atomic-Preserve Phase C.5: `executeSegmentsAgainstPool` test-helper
    // signature uses ExecutableSegment, AtomicSequencePreserveBatch,
    // AtomicSequencePreserveExecutor, and AtomicSequencePreserveResult
    // from ports-execute. Same testFixtures gradle-quirk as ports-read
    // above.
    testFixturesApi(project(":hexagon:ports-execute"))
    testFixturesApi("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
    testFixturesApi("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
