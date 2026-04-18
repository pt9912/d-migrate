// Read-only consumer probe: verifies that a consumer can use
// d-migrate's schema reading and data reading capabilities without
// pulling write-, CLI-, or profiling-specific dependencies.
//
// This module intentionally depends only on read-oriented modules.
// If this module compiles, external read consumers can integrate.

dependencies {
    testImplementation(project(":hexagon:ports-read"))
    testImplementation(project(":hexagon:ports-common"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:formats"))
}
