plugins {
    `java-library`
}

// adapters:driven:persistence-memory — in-memory (heap) implementations of the
// server-state store ports (JobStore, QuotaStore, IdempotencyStore, AuditSink,
// …). These are the production defaults when `server.state.*` is not configured
// (the JDBC-backed opt-in lives in :adapters:driven:persistence-jdbc).
//
// Befund 17 (Security-Audit 2026-07-17): these impls previously lived in
// :hexagon:ports-common testFixtures, so any production consumer that pulled the
// fixtures (the CLI) dragged kotest/JUnit/byte-buddy/JNA into the distribution
// artifact. As a real adapter module they carry no test framework.

dependencies {
    api(project(":hexagon:ports-common"))

    // The contract-test suites (generic, port-based) live in ports-common
    // testFixtures; the in-memory impls are exercised against them here.
    testImplementation(testFixtures(project(":hexagon:ports-common")))
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
