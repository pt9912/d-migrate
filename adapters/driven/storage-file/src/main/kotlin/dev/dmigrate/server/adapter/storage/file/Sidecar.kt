package dev.dmigrate.server.adapter.storage.file

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Per-file metadata stored alongside each finalized data file. The
 * §6.3 plan demands `existingSegmentSha256` / `existingSha256` come
 * from the sidecar — never from a re-hash of the data file. The
 * stores publish the **sidecar before the data file**, so a crash
 * between the two atomic-move steps leaves only a dangling sidecar
 * (no visible target without sidecar). `cleanupOrphans` removes
 * dangling sidecars and any leftover tmp files.
 *
 * `segmentOffset` is required for upload segments (Resume + Finalize
 * need to know where each segment fits in the session) and absent for
 * artifact sidecars.
 */
internal data class Sidecar(
    val sha256: String,
    val sizeBytes: Long,
    val segmentOffset: Long? = null,
) {

    fun serialize(): ByteArray {
        val offsetField = if (segmentOffset != null) ""","segmentOffset":$segmentOffset""" else ""
        return """{"sha256":"$sha256","sizeBytes":$sizeBytes$offsetField}""".toByteArray(Charsets.UTF_8)
    }

    companion object {

        private val SHA_REGEX = Regex(""""sha256"\s*:\s*"([0-9a-f]{64})"""")
        private val SIZE_REGEX = Regex(""""sizeBytes"\s*:\s*(\d+)""")
        private val OFFSET_REGEX = Regex(""""segmentOffset"\s*:\s*(\d+)""")

        fun parse(bytes: ByteArray): Sidecar {
            val text = bytes.toString(Charsets.UTF_8)
            val sha = SHA_REGEX.find(text)?.groupValues?.get(1)
                ?: throw IOException("sidecar missing sha256: $text")
            val size = SIZE_REGEX.find(text)?.groupValues?.get(1)?.toLong()
                ?: throw IOException("sidecar missing sizeBytes: $text")
            val offset = OFFSET_REGEX.find(text)?.groupValues?.get(1)?.toLong()
            return Sidecar(sha, size, offset)
        }

        fun read(path: Path): Sidecar {
            return try {
                parse(Files.readAllBytes(path))
            } catch (failure: NoSuchFileException) {
                throw IOException(
                    "sidecar missing at $path — re-hashing the data file is forbidden by §6.3; " +
                        "run cleanupOrphans to remove stale data before retrying",
                    failure,
                )
            }
        }

        /**
         * Writes the sidecar atomically via `Files.move(... ATOMIC_MOVE)`
         * per §6.3. A pre-`exists` check delivers the plan's
         * "fail-on-existing" semantics on Linux, where `rename(2)`
         * (which `ATOMIC_MOVE` invokes) silently overwrites by default;
         * the TOCTOU window between the check and the move is tiny and
         * accepted in Phase A. If the target already holds the sidecar
         * (e.g. another writer published it), this method is a no-op.
         */
        fun writeAtomically(target: Path, sidecar: Sidecar) {
            if (Files.exists(target)) return
            val parent = target.parent
            val tmp = parent.resolve("${target.fileName}.tmp.${UUID.randomUUID()}")
            Files.write(
                tmp,
                sidecar.serialize(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                if (!Files.exists(target)) {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                }
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}
