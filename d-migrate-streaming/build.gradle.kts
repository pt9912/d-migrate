// d-migrate-streaming: Pull-basierte Streaming-Pipeline für den Datenexport.
//
// Phase C (0.3.0): nur StreamingExporter mit Reader → Writer-Glue.
// Checkpoint, Parallel-Executor und Delta-Detector folgen in 0.5.0/1.0.0.
//
// Hängt an:
// - d-migrate-core            für DataChunk, ColumnDescriptor, DataFilter
// - d-migrate-driver-api      für DataReader, TableLister, ConnectionPool
// - d-migrate-formats         für DataChunkWriter (Phase D — bis dahin gibt
//                              es nur das Interface, Tests verwenden Fakes)
dependencies {
    api(project(":d-migrate-core"))
    api(project(":d-migrate-driver-api"))
    api(project(":d-migrate-formats"))
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
