plugins {
    `java-library`
}

// hexagon:ports — Aggregator module that re-exports all port sub-modules.
// Existing consumers can keep depending on this module without changes.
// New read-only consumers should depend on hexagon:ports-read directly.
//
// The aggregator also owns `DatabaseDriverRegistry` (the single
// dialect → driver lookup used by every driving adapter). That class
// has its own unit tests in src/test; the per-module 90% kover gate
// below guards against future regression — without it `:hexagon:ports`
// only contributed via the aggregated root gate, where missing tests
// were diluted by the sibling modules' high coverage.

dependencies {
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))
    api(project(":hexagon:ports-execute"))
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
