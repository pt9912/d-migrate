// test:perf-large-schema — Large-Schema scale tests for the
// SchemaMigrateRenderPipeline.
//
// Plan-Doc: docs/planning/done/quality-coverage-expansion-plan.md
// §5.4 (Sub-Slice D).
//
// Pinned scales:
//   - N=100  (Standard-Opt-in via `make docker-perf`)
//   - N=1000 (Standard-Opt-in via `make docker-perf`)
//
// N=10000 is deferred to Sub-Slice D-N10k as a nightly-only opt-in
// (separate spec class so the standard perf run does not pull a
// multi-minute scale into every PR's opt-in budget).
//
// The Kotest tag `perf` keeps this module out of the default unit-
// test sweep — same convention as `hexagon:application/...PerfSpec.kt`
// and `adapters/driven/{formats,streaming}/...PerfTest.kt`.

dependencies {
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":hexagon:profiling"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
}

kover {
    reports {
        verify {
            // Reines Scale-Test-Modul ohne Production-Code —
            // Plan-Doc §5.0.
            rule {
                minBound(0)
            }
        }
    }
}
