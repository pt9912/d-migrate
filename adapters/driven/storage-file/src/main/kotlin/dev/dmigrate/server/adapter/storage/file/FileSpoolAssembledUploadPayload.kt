package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.AssembledUploadPayload
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AP 6.22: file-backed [AssembledUploadPayload] under
 * `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`. The bytes live
 * on disk; [openStream] returns a fresh `Files.newInputStream` so the
 * finaliser can read the payload twice (parse + write) without
 * holding it in heap.
 *
 * [close] removes the spool file (idempotent, best-effort). The
 * surrounding session directory is left in place for the AP-6.21
 * startup sweep to bound, in case the JVM crashes between [close]
 * and the next allocation under the same session id.
 */
class FileSpoolAssembledUploadPayload internal constructor(
    val path: Path,
    override val sizeBytes: Long,
    override val sha256: String,
) : AssembledUploadPayload {

    private val closed = AtomicBoolean(false)

    /**
     * Throws [IOException] if the spool file has been [close]d (the
     * payload is single-life — re-open is an error). Each call returns
     * a fresh stream so multiple readers can consume the bytes back
     * to back.
     */
    override fun openStream(): InputStream {
        if (closed.get()) throw IOException("spool $path has been closed")
        return try {
            Files.newInputStream(path)
        } catch (failure: NoSuchFileException) {
            throw IOException("spool $path is missing", failure)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // best effort — AP-6.21 startup sweep bounds the
            // <stateDir>/assembly/... layout if a crash leaks files.
        }
    }

    companion object {

        const val ASSEMBLY_DIR_NAME: String = "assembly"

        /**
         * Allocates `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`
         * and creates the parent directories if needed. The returned
         * [Path] is empty — the caller (AP-6.22 streaming assembly)
         * writes the segment bytes into it via standard `Files.newOutputStream`
         * with a small buffer.
         */
        fun allocate(stateDir: Path, uploadSessionId: String): Path {
            PathSafety.requireSafeId(uploadSessionId, "uploadSessionId")
            val sessionDir = stateDir.resolve(ASSEMBLY_DIR_NAME).resolve(uploadSessionId)
            Files.createDirectories(sessionDir)
            return sessionDir.resolve("${UUID.randomUUID()}${FileLayout.BIN}")
        }
    }
}
