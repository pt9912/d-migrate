package dev.dmigrate.server.adapter.storage.file

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal data class Sidecar(val sha256: String, val sizeBytes: Long) {

    fun serialize(): ByteArray =
        """{"sha256":"$sha256","sizeBytes":$sizeBytes}""".toByteArray(Charsets.UTF_8)

    companion object {

        private val SHA_REGEX = Regex(""""sha256"\s*:\s*"([0-9a-f]{64})"""")
        private val SIZE_REGEX = Regex(""""sizeBytes"\s*:\s*(\d+)""")

        fun parse(bytes: ByteArray): Sidecar {
            val text = bytes.toString(Charsets.UTF_8)
            val sha = SHA_REGEX.find(text)?.groupValues?.get(1)
                ?: throw IOException("sidecar missing sha256: $text")
            val size = SIZE_REGEX.find(text)?.groupValues?.get(1)?.toLong()
                ?: throw IOException("sidecar missing sizeBytes: $text")
            return Sidecar(sha, size)
        }

        fun read(path: Path): Sidecar = parse(Files.readAllBytes(path))

        fun readOrRecover(finalBin: Path, finalMeta: Path): Sidecar {
            if (Files.exists(finalMeta)) return read(finalMeta)
            val recovered = rehash(finalBin)
            writeAtomically(finalMeta, recovered)
            return recovered
        }

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

        private fun rehash(path: Path): Sidecar {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(StreamingHashWriter.BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    total += n
                }
            }
            return Sidecar(digest.digest().toHex(), total)
        }
    }
}
