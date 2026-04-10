plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports"))
    implementation(project(":hexagon:core"))

    // Jackson — bleibt für die Schema-Codecs aus 0.1.0/0.2.0 (typsicheres
    // Mapping zu/von SchemaDefinition, selten aufgerufen).
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${rootProject.properties["jacksonVersion"]}")

    // 0.3.0 Phase D — performance-orientierte Format-Writer für den
    // Daten-Schreibpfad. Siehe implementation-plan-0.3.0.md §11.5 für die
    // Begründung der Wahl gegen die Jackson-Toolchain.
    implementation("com.dslplatform:dsl-json-java8:${rootProject.properties["dslJsonVersion"]}")
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")
    implementation("com.univocity:univocity-parsers:${rootProject.properties["univocityVersion"]}")

    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-mysql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))
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
