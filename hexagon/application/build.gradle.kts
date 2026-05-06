// hexagon:application — Use case runners (application layer).
// Depends only on hexagon:core and hexagon:ports, never on adapters.

dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:ports"))
    implementation(project(":hexagon:profiling"))

    // Phase D (0.8.0): ICU4J for grapheme counting and Unicode normalization
    implementation("com.ibm.icu:icu4j:76.1")

    // Phase E3 (0.9.6): slf4j-Facade fuer Worker-Thread-Uncaught-Logging im
    // BoundedAsyncJobExecutor. No-op ohne Provider; Tests/Runtime ziehen
    // logback-classic (root build.gradle.kts subprojects-Block).
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    testImplementation(project(":adapters:driven:integrations"))
    testImplementation(testFixtures(project(":hexagon:ports-common")))

    // Phase E0.1 cancel-test fixtures (TestCancellationTokenSource).
    testImplementation(testFixtures(project(":hexagon:core")))
}
