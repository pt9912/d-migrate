package dev.dmigrate.server.core.upload

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * AP 6.22: re-openable, streamable view on a fully assembled upload
 * payload. The streaming finaliser needs the bytes at least twice —
 * once for parse/validate and once for `ArtifactContentStore.write` —
 * so this abstraction must support multiple [openStream] calls without
 * holding the bytes in heap.
 *
 * Implementations:
 * - [fromBytes]: in-process `ByteArray` view for handler-near tests
 *   and small fixtures. NOT for production — the production CLI wires
 *   a file-spool adapter (`FileSpoolAssembledUploadPayload` in
 *   `:adapters:driven:storage-file`).
 * - File-spool: writes to `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`,
 *   opens fresh `Files.newInputStream` on each [openStream], and
 *   deletes the file on [close]. See AP 6.22 §6.21 sweep notes.
 *
 * [close] is idempotent and best-effort. Calling [openStream] after
 * [close] is undefined — implementations may throw `IOException` (the
 * file-spool variant) or return an empty view (no implementation
 * relies on this behaviour, just keeping the contract honest).
 *
 * [sizeBytes] and [sha256] are computed once at assembly time. They
 * are diagnostic / plausibility values; the source of truth for the
 * published artefact remains `WriteArtifactOutcome.Stored.sha256` /
 * `.sizeBytes` returned by `ArtifactContentStore.write`.
 */
interface AssembledUploadPayload : AutoCloseable {

    val sizeBytes: Long

    val sha256: String

    fun openStream(): InputStream

    override fun close()

    companion object {
        /**
         * Test-only convenience: an in-memory payload backed by [bytes].
         * The closed state is a no-op (no file to delete). The
         * production CLI wiring must NOT use this — the file-spool
         * adapter is the only valid production implementation.
         */
        fun fromBytes(bytes: ByteArray, sha256: String): AssembledUploadPayload =
            ByteArrayAssembledUploadPayload(bytes, sha256)
    }
}

private class ByteArrayAssembledUploadPayload(
    private val bytes: ByteArray,
    override val sha256: String,
) : AssembledUploadPayload {

    override val sizeBytes: Long = bytes.size.toLong()

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() = Unit
}
