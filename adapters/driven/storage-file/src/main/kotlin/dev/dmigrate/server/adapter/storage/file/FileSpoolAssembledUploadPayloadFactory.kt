package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.AssembledUploadPayloadFactory
import dev.dmigrate.server.core.upload.SpoolHandle
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AP 6.22: production [AssembledUploadPayloadFactory] that keeps the
 * assembled upload bytes off-heap under
 * `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`. The caller
 * writes through a buffered [OutputStream]; on [SpoolHandle.publish]
 * the file is closed and a re-openable [FileSpoolAssembledUploadPayload]
 * takes ownership of cleanup.
 *
 * If [SpoolHandle.publish] is never called, [SpoolHandle.close]
 * discards the spool file. Crashed processes leave files behind which
 * the AP-6.21 startup sweep bounds via retention.
 */
class FileSpoolAssembledUploadPayloadFactory(private val stateDir: Path) : AssembledUploadPayloadFactory {

    override fun allocate(uploadSessionId: String): SpoolHandle {
        val path = FileSpoolAssembledUploadPayload.allocate(stateDir, uploadSessionId)
        return FileSpoolHandle(path)
    }
}

private class FileSpoolHandle(private val path: Path) : SpoolHandle {

    private val rawOutput: OutputStream = Files.newOutputStream(path)

    /** 64 KiB buffer matches the AP 6.22 assembly read buffer cap. */
    override val output: OutputStream = BufferedOutputStream(rawOutput, BUFFER_BYTES)

    private val published = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun publish(sizeBytes: Long, sha256: String): AssembledUploadPayload {
        check(published.compareAndSet(false, true)) { "spool $path already published" }
        // Flush + close the writer side so the published payload's
        // openStream() reads complete bytes from disk.
        try {
            output.flush()
        } finally {
            output.close()
        }
        return FileSpoolAssembledUploadPayload(
            path = path,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            output.close()
        } catch (_: java.io.IOException) {
            // best-effort
        }
        if (!published.get()) {
            try { Files.deleteIfExists(path) } catch (_: java.io.IOException) {}
        }
    }

    private companion object {
        const val BUFFER_BYTES: Int = 64 * 1024
    }
}
