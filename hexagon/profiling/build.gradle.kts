// hexagon:profiling — Domain model, ports, and rules for data profiling (0.7.5).
// Depends on hexagon:core (schema model) and hexagon:ports (driver types).
// Does NOT extend DatabaseDriver — profiling ports are defined here.

dependencies {
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:ports"))

    // LF-012 / LN-011 / LN-017 / LN-027 cancel-test fixtures (TestCancellationTokenSource).
    testImplementation(testFixtures(project(":hexagon:core")))
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
