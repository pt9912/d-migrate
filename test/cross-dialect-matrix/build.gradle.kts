// test:cross-dialect-matrix — Sweep-Modul fuer die Cross-Dialekt-
// Regressionsmatrix aus `diffresult-migration-plan-2.md` §11.2.
//
// Reines Test-Modul: kein Production-Code, keine Testcontainers. Der
// Sweep instanziiert SchemaMigrateRunner im File-Mode mit den echten
// Diff-Renderern (PG/MySQL/SQLite) und prueft pro
// (Workstream × Dialekt × Kind)-Zelle den Exit-Code gegen das Pinning.
//
// Plan-Doc: docs/planning/done/quality-coverage-expansion-plan.md
// §5.0, §5.2, §6 (Sub-Slice B).

dependencies {
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-mysql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))
    testImplementation(project(":adapters:driven:formats"))

    testImplementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")
}

kover {
    reports {
        verify {
            // Reines Sweep-Modul ohne Production-Code — minBound(0)
            // dokumentiert die bewusste Entscheidung, dass das Kover-
            // Aggregat dieses Modul nicht zaehlen muss. Plan-Doc §5.0.
            rule {
                minBound(0)
            }
        }
    }
}
