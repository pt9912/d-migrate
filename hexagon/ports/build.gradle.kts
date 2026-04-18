plugins {
    `java-library`
}

// hexagon:ports — Aggregator module that re-exports all port sub-modules.
// Existing consumers can keep depending on this module without changes.
// New read-only consumers should depend on hexagon:ports-read directly.

dependencies {
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))
}
