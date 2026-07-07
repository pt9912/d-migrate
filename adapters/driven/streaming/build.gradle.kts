// adapters:driven:streaming: Pull-basierte Streaming-Pipeline für den Datenexport.
//
// LF-008 / LF-009: Pull-basierte Export-Pipeline mit Reader → Writer-Glue.
// Checkpointing deckt LF-013 / LN-012 ab.
//
// Hängt an:
// - hexagon:core                   für DataChunk, ColumnDescriptor, DataFilter
// - hexagon:ports-common           für ConnectionPool und gemeinsame Format-Typen
// - hexagon:ports-read             für DataReader, TableLister und Chunk-Reader-Ports
// - hexagon:ports-write            für DataWriter und Import-/Writer-Ports
dependencies {
    api(project(":hexagon:core"))
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))

    // LF-013 / LN-012 / LN-013: dateibasierter
    // CheckpointStore-Adapter schreibt das Manifest als YAML. Dieselbe
    // Library wird bereits in :adapters:driven:formats verwendet; wir
    // binden sie hier direkt ein, weil formats sie nur intern exponiert.
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

    testImplementation(project(":adapters:driven:formats"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-sqlite"))

    // Quality-Coverage-Expansion Phase A: PerfMeasure/PerfReport for the
    // migrated StreamingImporterReorderPerfTest (Sub-Slice A-
    // Vervollständigung). formats's profiling dep is `implementation`
    // and not transitively exposed, so streaming declares its own
    // test-time dep on the lib.
    testImplementation(project(":hexagon:profiling"))

    // LN-011: cancel-test fixtures (TestCancellationTokenSource).
    testImplementation(testFixtures(project(":hexagon:core")))
    // Parquet Cut A S0b: DataChunkWriter.begin(table, columns)-Bridge-Extension.
    testImplementation(testFixtures(project(":hexagon:ports-write")))
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
