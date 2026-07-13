// test:perf-data-path — LN-005 Heap-Cap-Akzeptanztest für den Streaming-DATENpfad.
//
// Plan-Doc: docs/planning/in-progress/ln005-streaming-oom-hardening.md (Phase D).
//
// Beweist die LN-005-Kernaussage „>10 TB ohne OOM": ein synthetischer, lazy
// DataReader generiert N Mio. breite Zeilen (kein Backing-DB) und wird durch den
// echten Streaming-Pfad (Reader-Chunk-Loop + realer CsvChunkWriter) in einen
// verwerfenden Sink getrieben. Das synthetische Gesamtvolumen liegt bewusst weit
// über dem -Xmx-Cap: eine „hält-alles"-Regression sprengt den Heap (OOM +
// HeapDump), ein bounded Pfad hält nur ~chunkSize Zeilen und läuft durch.
//
// Der `perf`-Kotest-Tag hält das Spec aus dem Standard-Unit-Sweep heraus
// (kotest.tags=!perf); Ausführung opt-in via `make docker-perf` (setzt
// -Dkotest.tags=perf), z.B. `make docker-perf MODULES=":test:perf-data-path"`.

dependencies {
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    testImplementation(project(":hexagon:ports-common"))
    testImplementation(project(":hexagon:ports-read"))
    testImplementation(project(":hexagon:ports-write"))
    testImplementation(project(":adapters:driven:formats"))
}

// Enger Heap-Cap macht die Bounded-Memory-Aussage erst beweisbar: bei einer
// Buffer-all-Regression OOMt der Lauf unter diesem -Xmx (der HeapDump dient der
// Forensik). Der Wert hat genug Headroom für den Bounded-Fall (Peak ~ chunkSize
// Zeilen) plus Test-Framework, liegt aber weit unter dem synthetischen
// Gesamtvolumen (siehe DataPathHeapBoundSpec).
tasks.named<Test>("test") {
    maxHeapSize = "256m"
    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=${layout.buildDirectory.dir("test-heap-dumps").get().asFile.absolutePath}",
    )
}

kover {
    reports {
        verify {
            // Reines Scale-/Akzeptanz-Testmodul ohne Production-Code (analog
            // test/perf-large-schema).
            rule {
                minBound(0)
            }
        }
    }
}
