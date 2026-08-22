plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))
    implementation(project(":hexagon:core"))
    implementation(project(":hexagon:profiling"))

    // Jackson — bleibt für die Schema-Codecs aus 0.1.0/0.2.0 (typsicheres
    // Mapping zu/von SchemaDefinition, selten aufgerufen).
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${rootProject.properties["jacksonVersion"]}")

    // LF-009 — performance-orientierte Format-Writer fuer den
    // Daten-Schreibpfad. Siehe docs/archive/implementation-plan-0.3.0.md §11.5 für die
    // Begründung der Wahl gegen die Jackson-Toolchain.
    implementation("com.dslplatform:dsl-json-java8:${rootProject.properties["dslJsonVersion"]}")
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")
    implementation("com.univocity:univocity-parsers:${rootProject.properties["univocityVersion"]}")

    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-mysql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))
    testImplementation(project(":adapters:driven:driver-mssql"))
    // Parquet Cut A S0b: DataChunkWriter.begin(table, columns)-Bridge-Extension.
    testImplementation(testFixtures(project(":hexagon:ports-write")))
    testImplementation(testFixtures(project(":hexagon:ports-common")))
    // LN-046 / ADR 0029 Phase C: geteilter Arb<SchemaDefinition> aus core-Fixtures.
    testImplementation(testFixtures(project(":hexagon:core")))

    // Vertrags-Guard: validiert ein Voll-Feature-Fixture gegen spec/schema.json
    // (SchemaJsonContractTest), damit das handgepflegte JSON-Schema nicht hinter
    // neutral-model-spec.md / dem Parser zurueckfaellt.
    testImplementation("com.networknt:json-schema-validator:1.5.4")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Internal SnakeYAML adapter — pure delegation, no logic
                    "dev.dmigrate.format.data.yaml.StreamDataWriterAdapter",
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
