// hexagon:application — Use case runners (application layer).
// Depends only on hexagon:core and hexagon:ports, never on adapters.

dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:ports"))
    implementation(project(":hexagon:profiling"))

    testImplementation(project(":adapters:driven:integrations"))
}
