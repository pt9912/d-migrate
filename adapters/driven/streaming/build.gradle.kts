// adapters:driven:streaming: Pull-basierte Streaming-Pipeline für den Datenexport.
//
// Phase C (0.3.0): nur StreamingExporter mit Reader → Writer-Glue.
// Checkpoint, Parallel-Executor und Delta-Detector folgen in 0.5.0/1.0.0.
//
// Hängt an:
// - hexagon:core                   für DataChunk, ColumnDescriptor, DataFilter
// - adapters:driven:driver-common  für DataReader, TableLister, ConnectionPool
// - adapters:driven:formats        für DataChunkWriter (Phase D — bis dahin
//                                   gibt es nur das Interface, Tests
//                                   verwenden Fakes)
dependencies {
    api(project(":hexagon:core"))
    api(project(":adapters:driven:driver-common"))
    api(project(":adapters:driven:formats"))

    // 0.9.0 Phase B (docs/ImpPlan-0.9.0-B.md §4.6): dateibasierter
    // CheckpointStore-Adapter schreibt das Manifest als YAML. Dieselbe
    // Library wird bereits in :adapters:driven:formats verwendet; wir
    // binden sie hier direkt ein, weil formats sie nur intern exponiert.
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

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
