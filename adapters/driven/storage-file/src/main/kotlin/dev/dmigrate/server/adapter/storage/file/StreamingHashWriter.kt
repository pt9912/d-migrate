package dev.dmigrate.server.adapter.storage.file

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal data class HashedWrite(val sizeBytes: Long, val sha256: String)

internal object StreamingHashWriter {

    fun copyAndHash(source: InputStream, target: Path): HashedWrite {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { out ->
            total = digestPipe(source, out, digest)
        }
        return HashedWrite(total, digest.digest().toHex())
    }

    private fun digestPipe(source: InputStream, sink: OutputStream, digest: MessageDigest): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            sink.write(buffer, 0, read)
            total += read
        }
        return total
    }

    const val BUFFER_SIZE = 64 * 1024
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF])
        sb.append(HEX_CHARS[b.toInt() and 0xF])
    }
    return sb.toString()
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
