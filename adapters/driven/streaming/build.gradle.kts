// adapters:driven:streaming: Pull-basierte Streaming-Pipeline für den Datenexport.
//
// LF-008 / LF-009: Pull-basierte Export-Pipeline mit Reader → Writer-Glue.
// Checkpointing deckt LF-013 / LN-012 ab.
//
// Hängt an:
// - hexagon:core                   für DataChunk, ColumnDescriptor, DataFilter
// - adapters:driven:driver-common  für DataReader, TableLister, ConnectionPool
// - adapters:driven:formats        für DataChunkWriter
dependencies {
    api(project(":hexagon:core"))
    api(project(":adapters:driven:driver-common"))
    api(project(":adapters:driven:formats"))

    // LF-013 / LN-012 / LN-013: dateibasierter
    // CheckpointStore-Adapter schreibt das Manifest als YAML. Dieselbe
    // Library wird bereits in :adapters:driven:formats verwendet; wir
    // binden sie hier direkt ein, weil formats sie nur intern exponiert.
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

    testImplementation(project(":adapters:driven:driver-sqlite"))

    // Quality-Coverage-Expansion Phase A: PerfMeasure/PerfReport for the
    // migrated StreamingImporterReorderPerfTest (Sub-Slice A-
    // Vervollständigung). formats's profiling dep is `implementation`
    // and not transitively exposed, so streaming declares its own
    // test-time dep on the lib.
    testImplementation(project(":hexagon:profiling"))

    // LN-011: cancel-test fixtures (TestCancellationTokenSource).
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
