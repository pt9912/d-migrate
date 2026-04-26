// hexagon:application — Use case runners (application layer).
// Depends only on hexagon:core and hexagon:ports, never on adapters.

dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:ports"))
    implementation(project(":hexagon:profiling"))

    // Phase D (0.8.0): ICU4J for grapheme counting and Unicode normalization
    implementation("com.ibm.icu:icu4j:76.1")

    testImplementation(project(":adapters:driven:integrations"))
    testImplementation(testFixtures(project(":hexagon:ports-common")))
}
