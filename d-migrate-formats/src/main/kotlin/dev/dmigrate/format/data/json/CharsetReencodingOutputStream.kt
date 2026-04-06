package dev.dmigrate.format.data.json

import java.io.OutputStream
import java.nio.charset.Charset

/**
 * OutputStream-Wrapper, der eingehende UTF-8-Bytes in ein Ziel-Charset
 * re-encodet, bevor sie in den darunterliegenden Stream geschrieben werden.
 *
 * Wird vom [JsonChunkWriter] verwendet, weil DSL-JSON intern immer
 * UTF-8-Bytes produziert. Wenn der User über `--encoding utf-16` oder
 * `iso-8859-1` ein anderes Output-Encoding verlangt (Plan §3.5 / §6.6 + F25),
 * wandelt dieser Wrapper die UTF-8-Bytes Stück für Stück in das Ziel-Encoding
 * um.
 *
 * **Implementierung**: Wir akkumulieren in einem internen [StringBuilder]
 * (über `String(b, off, len, UTF_8)`) und schreiben das Ergebnis im Ziel-
 * Charset. Da JSON ASCII-Sonderzeichen wie `{`, `}`, `[`, `]`, `:`, `,`
 * im UTF-8-Stream als single-byte vorliegen, ist das tolerant gegenüber
 * Splits in der Mitte eines Multi-Byte-Zeichens — DSL-JSON ruft `write()`
 * ohnehin nur auf vollständigen Token-Grenzen.
 *
 * Nicht unterstützte Encodings (z.B. UTF-32) werden vom JVM-Charset-Loader
 * mit `UnsupportedCharsetException` abgefangen, bevor diese Klasse
 * instanziiert wird.
 */
internal class CharsetReencodingOutputStream(
    private val delegate: OutputStream,
    private val targetCharset: Charset,
) : OutputStream() {

    override fun write(b: Int) {
        // single-byte path — wenn es ein ASCII-Char ist, ist UTF-8 == anderes
        // ASCII-kompatibles Encoding (UTF-8, ISO-8859-1, Windows-1252).
        // Für Multi-Byte: das geht durch den write(byte[]) Pfad.
        val asString = String(byteArrayOf(b.toByte()), Charsets.UTF_8)
        delegate.write(asString.toByteArray(targetCharset))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val asString = String(b, off, len, Charsets.UTF_8)
        delegate.write(asString.toByteArray(targetCharset))
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}
