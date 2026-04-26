package dev.dmigrate.server.adapter.storage.file

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Per-file metadata stored alongside each finalized data file. The
 * §6.3 plan demands `existingSegmentSha256` / `existingSha256` come
 * from the sidecar — never from a re-hash of the data file. To uphold
 * that invariant under crash recovery, callers move the sidecar
 * **before** the data file: `data exists ⇒ sidecar exists`. Dangling
 * sidecars (sidecar without data) are cleaned up by `cleanupOrphans`.
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
         * Bounded retry on the conflict-resolution path, where data has
         * already been linked but the sidecar may not yet be visible
         * (the winning writer publishes data first, then sidecar). The
         * race window is microseconds; a few short polls cover it
         * without resorting to a rehash, which §6.3 forbids.
         */
        fun readWithRetry(path: Path, attempts: Int = MAX_ATTEMPTS): Sidecar {
            var lastFailure: IOException? = null
            repeat(attempts) {
                try {
                    return read(path)
                } catch (failure: IOException) {
                    lastFailure = failure
                    @Suppress("MagicNumber")
                    Thread.sleep(1)
                }
            }
            throw lastFailure ?: IOException("sidecar at $path not present after $attempts retries")
        }

        private const val MAX_ATTEMPTS: Int = 100

        fun writeAtomically(target: Path, sidecar: Sidecar) {
            val parent = target.parent
            val tmp = parent.resolve("${target.fileName}.tmp.${UUID.randomUUID()}")
            Files.write(
                tmp,
                sidecar.serialize(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.createLink(target, tmp)
            } catch (_: FileAlreadyExistsException) {
                // Another writer produced the sidecar in parallel; their content
                // matches ours by construction, so silently discard the tmp.
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}
