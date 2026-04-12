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
