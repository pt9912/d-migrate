package dev.dmigrate.server.core.upload

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AP 6.22: factory for streaming-assembled upload payloads.
 *
 * The streaming `ArtifactUploadHandler` allocates one [SpoolHandle]
 * per finalising session, writes the segment bytes through
 * [SpoolHandle.output] in a small fixed buffer, and then either
 * [SpoolHandle.publish]es a re-openable [AssembledUploadPayload] for
 * the finaliser to read twice (parse + write), or drops the spool
 * via [SpoolHandle.close] if assembly fails.
 *
 * Two implementations:
 * - [inMemory]: ByteArray-backed convenience for handler-near tests.
 *   NOT for production — heap-grows with payload size.
 * - File-spool: writes under `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`,
 *   keeps the bytes off-heap. Production CLI wires this.
 */
interface AssembledUploadPayloadFactory {

    fun allocate(uploadSessionId: String): SpoolHandle

    companion object {
        /**
         * Heap-resident factory for handler-near tests. Production
         * wiring must NOT use this — the AP-6.21 file-spool factory is
         * the only valid CLI implementation. Tests / `developmentPhaseCWiring`
         * may use it because the assembled bytes there are small
         * fixtures.
         */
        fun inMemory(): AssembledUploadPayloadFactory = InMemoryAssembledUploadPayloadFactory
    }
}

/**
 * Single-use write handle for the assembly spool. Implementations are
 * NOT thread-safe — one handle per finalising call. Caller flow:
 *
 * ```
 * factory.allocate(session).use { spool ->
 *     spool.output.use { stream -> writeSegmentsInto(stream) }
 *     val payload = spool.publish(sizeBytes, sha256)
 *     useThenClose(payload)
 * }
 * ```
 *
 * If [publish] is never called, [close] discards the spool.
 */
interface SpoolHandle : AutoCloseable {

    /**
     * Write-side stream. Implementations may wrap I/O in a buffer;
     * the caller still owns the lifecycle and must `close()` it
     * before calling [publish].
     */
    val output: OutputStream

    /**
     * Hands ownership of the assembled bytes to a re-openable
     * [AssembledUploadPayload]. After publish, the spool's [close] is
     * a no-op — the payload's `close()` is the new disposer.
     *
     * Throws [IllegalStateException] if called twice.
     */
    fun publish(sizeBytes: Long, sha256: String): AssembledUploadPayload

    /**
     * Discards the spool if [publish] was never called. Idempotent.
     */
    override fun close()
}

private object InMemoryAssembledUploadPayloadFactory : AssembledUploadPayloadFactory {
    override fun allocate(uploadSessionId: String): SpoolHandle = InMemorySpoolHandle()
}

private class InMemorySpoolHandle : SpoolHandle {

    private val buffer = ByteArrayOutputStream()
    private val published = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val output: OutputStream get() = buffer

    override fun publish(sizeBytes: Long, sha256: String): AssembledUploadPayload {
        check(published.compareAndSet(false, true)) { "spool already published" }
        return AssembledUploadPayload.fromBytes(buffer.toByteArray(), sha256)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // ByteArrayOutputStream.close is a no-op; nothing else to do.
    }
}
