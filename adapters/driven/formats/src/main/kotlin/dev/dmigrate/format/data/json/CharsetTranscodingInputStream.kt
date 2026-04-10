package dev.dmigrate.format.data.json

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * InputStream-Wrapper, der eingehende Bytes in einem Quell-Charset liest
 * und als UTF-8-Bytes zurückgibt.
 *
 * Inverse zum [CharsetReencodingOutputStream]: dort geht UTF-8 rein und
 * Ziel-Encoding raus; hier geht Quell-Encoding rein und UTF-8 raus.
 *
 * Wird vom [JsonChunkReader] verwendet, weil DSL-JSON intern immer
 * UTF-8-Bytes erwartet. Wenn die Eingabedatei z.B. UTF-16 mit BOM ist,
 * wandelt dieser Wrapper die Bytes Stück für Stück in UTF-8 um, damit
 * DSL-JSONs Pull-Parser korrekt arbeiten kann.
 *
 * Für UTF-8-Eingaben (der Regelfall) wird dieser Wrapper NICHT
 * instanziiert — der Stream geht direkt an DSL-JSON.
 */
internal class CharsetTranscodingInputStream(
    delegate: InputStream,
    sourceCharset: Charset,
) : InputStream() {

    private val reader = BufferedReader(InputStreamReader(delegate, sourceCharset))
    private var buf = ByteArray(0)
    private var pos = 0

    override fun read(): Int {
        if (pos >= buf.size) {
            if (!refill()) return -1
        }
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= buf.size && !refill()) return -1

        var copied = 0
        while (copied < len) {
            if (pos >= buf.size) {
                if (!refill()) break
            }
            val available = buf.size - pos
            val toCopy = minOf(available, len - copied)
            System.arraycopy(buf, pos, b, off + copied, toCopy)
            pos += toCopy
            copied += toCopy
        }
        return if (copied == 0) -1 else copied
    }

    private fun refill(): Boolean {
        val chars = CharArray(8192)
        val n = reader.read(chars)
        if (n < 0) return false
        buf = String(chars, 0, n).toByteArray(Charsets.UTF_8)
        pos = 0
        return true
    }

    override fun close() {
        reader.close()
    }
}
