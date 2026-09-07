// Profiling-Adapter fuer Oracle (ADR 0052). Getrennt vom Treibermodul wie bei
// den vier anderen Dialekten: `data profile` haengt an eigenen Ports
// (hexagon:profiling) und soll den Migrationspfad nicht mitziehen.

dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
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
