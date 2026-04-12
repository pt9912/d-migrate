package dev.dmigrate.streaming

/**
 * Konfiguration für die [StreamingExporter]-Pipeline.
 *
 * Plan §2.1 / §6.13: enthält **nur** [chunkSize] als user-tunable
 * Parameter. `fetchSize` ist treiberintern und gehört nicht hierher
 * (Plan §6.13). Parallelism, Retry, Checkpoint folgen in 0.5.0/1.0.0,
 * wenn sie tatsächlich gebraucht werden.
 */
data class PipelineConfig(
    /**
     * Anzahl Rows pro Chunk, die der [DataReader][dev.dmigrate.driver.data.DataReader]
     * pro Iteration zurückgeben soll. Default 10 000.
     */
    val chunkSize: Int = 10_000,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
    }
}
