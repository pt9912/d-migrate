// adapters:driven:audit-logging: SLF4J-basierte AuditSink-Implementierung
// (0.9.6 Phase A AP 6.8). Phase A liefert eine produktive
// `LoggingAuditSink`, die jedes Audit-Event als JSON-Zeile in den
// strukturierten Logger `dev.dmigrate.audit` schreibt. Persistente
// Sinks (DB/Datei) sind Phase-B-Thema.
dependencies {
    api(project(":hexagon:ports-common"))

    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

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
