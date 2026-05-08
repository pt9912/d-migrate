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
    testImplementation(testFixtures(project(":hexagon:ports-common")))

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
    testFixturesApi("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
    testFixturesApi("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
}
