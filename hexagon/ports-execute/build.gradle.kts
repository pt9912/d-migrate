plugins {
    `java-library`
}

// hexagon:ports-execute — execute-time orchestration ports.
//
// Plan-Doc: docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md
// §5 Phase B. The module is deliberately separate from
// `hexagon:ports-read` (read-only probes) and `hexagon:ports-write`
// (data writers / schema sync) because its concerns — JDBC
// transactions, dialect-specific locks, all-or-nothing rollback —
// are execute-time orchestration, not read or write contracts.
dependencies {
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:core"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Interfaces
                    "dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor",
                    // Pure data carriers
                    "dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch",
                    "dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest",
                    "dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult",
                    "dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult$*",
                    // Sealed result + subtype carriers
                    "dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult",
                    "dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult$*",
                    // Phase C.2 segment carriers (sealed + data classes only)
                    "dev.dmigrate.driver.migration.preserve.ExecutableSegment",
                    "dev.dmigrate.driver.migration.preserve.PlainSqlSegment",
                    "dev.dmigrate.driver.migration.preserve.AtomicPreserveSegment",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
