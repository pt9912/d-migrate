package dev.dmigrate.format.data

import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * BOM-basierte Auto-Detection für Input-Encodings.
 *
 * Plan: implementation-plan-0.4.0.md §6.9 (`--encoding auto`).
 *
 * **Bewusst kein universeller Charset-Detector**: Der Detector erkennt
 * ausschließlich BOM-markierte UTF-Streams (UTF-8, UTF-16 BE/LE). Alles
 * andere (ISO-8859-1, Windows-1252, CP-437, …) muss der Nutzer per
 * explizitem `--encoding <charset>` angeben. Heuristische Detektoren
 * (chardet, ICU) sind bewusst out-of-scope für 0.4.0 (§11 / F45).
 *
 * Verhalten:
 *
 * - `--encoding utf-8` / `utf-16le` / … (explizit): die Auto-Detection
 *   läuft NICHT. Ein vorhandenes BOM wird nur dann konsumiert, wenn es
 *   zum angeforderten Charset passt; bei Mismatch bleibt es im Stream
 *   stehen und wird nicht still „weginterpretiert". Dieser Pfad ist in
 *   [wrapWithExplicit] implementiert.
 * - `--encoding auto` (Default): der Detector liest die ersten 4 Bytes
 *   per [PushbackInputStream], entscheidet anhand der Byte-Signatur und
 *   legt die nicht zum BOM gehörigen Bytes per `unread()` wieder
 *   zurück. Das eigentliche Parsen sieht die Datei ohne BOM. Dieser
 *   Pfad ist in [detectOrFallback] implementiert.
 *
 * BOM-Tabelle:
 *
 * | Bytes         | Encoding       | Verhalten                          |
 * |---------------|----------------|------------------------------------|
 * | EF BB BF      | UTF-8 (BOM)    | erkannt, BOM wird übersprungen     |
 * | FE FF         | UTF-16 BE      | erkannt, BOM wird übersprungen     |
 * | FF FE         | UTF-16 LE      | erkannt, BOM wird übersprungen     |
 * | 00 00 FE FF   | UTF-32 BE      | NICHT unterstützt, wirft           |
 * | FF FE 00 00   | UTF-32 LE      | NICHT unterstützt, wirft           |
 * | sonstige      | (keine BOM)    | Fallback UTF-8, Bytes bleiben      |
 *
 * UTF-32 BOMs werden explizit als Fehler behandelt, damit ein
 * versehentlich als UTF-32 gespeicherter Input nicht still als UTF-8
 * durchläuft und danach lauter NUL-Bytes im Parser produziert. Die
 * Exception liefert das CLI auf Exit 2 mit Hinweis auf `--encoding`.
 */
object EncodingDetector {

    /**
     * Ergebnis von [detectOrFallback]: das erkannte Charset plus der
     * ggf. umwickelte InputStream, aus dem die BOM-Bytes bereits
     * konsumiert wurden (für UTF-*-BOMs) oder in dem die gelesenen
     * Bytes zurückgelegt sind (Fallback-Pfad). Der Caller liest
     * ausschließlich aus [stream], nicht aus dem Original-Stream.
     */
    data class Detected(val charset: Charset, val stream: InputStream)

    /**
     * `--encoding auto`-Pfad: liest bis zu 4 Bytes vom [raw]-Stream per
     * [PushbackInputStream], entscheidet anhand der Byte-Signatur und
     * liefert [Detected] mit dem ausgewählten Charset plus einem
     * wrapper-Stream, aus dem der eigentliche Reader liest.
     *
     * UTF-*-BOMs werden konsumiert (der Reader sieht sie nicht).
     * Fallback ist UTF-8, ohne dass Bytes verworfen werden.
     *
     * @throws UnsupportedFileEncodingException für UTF-32-BOMs
     */
    fun detectOrFallback(raw: InputStream): Detected {
        val pb = PushbackInputStream(raw, 4)
        val first4 = ByteArray(4)
        val read = readFully(pb, first4)

        rejectUtf32Bom(first4, read)

        val detected = detectUtf8Bom(first4, read, pb)
            ?: detectUtf16BeBom(first4, read, pb)
            ?: detectUtf16LeBom(first4, read, pb)

        if (detected != null) return detected

        if (read > 0) pb.unread(first4, 0, read)
        return Detected(StandardCharsets.UTF_8, pb)
    }

    private fun rejectUtf32Bom(bytes: ByteArray, read: Int) {
        if (hasUtf32BeBom(bytes, read)) {
            throw UnsupportedFileEncodingException(
                "UTF-32 BE BOM detected — UTF-32 is not supported in 0.4.0. " +
                    "Set --encoding explicitly (e.g. UTF-16LE) or convert the file."
            )
        }
        if (hasUtf32LeBom(bytes, read)) {
            throw UnsupportedFileEncodingException(
                "UTF-32 LE BOM detected — UTF-32 is not supported in 0.4.0. " +
                    "Set --encoding explicitly (e.g. UTF-16LE) or convert the file."
            )
        }
    }

    private fun detectUtf8Bom(bytes: ByteArray, read: Int, pb: PushbackInputStream): Detected? {
        if (!hasUtf8Bom(bytes, read)) return null
        if (read == 4) pb.unread(bytes, 3, 1)
        return Detected(StandardCharsets.UTF_8, pb)
    }

    private fun detectUtf16BeBom(bytes: ByteArray, read: Int, pb: PushbackInputStream): Detected? {
        if (!hasUtf16BeBom(bytes, read)) return null
        if (read > 2) pb.unread(bytes, 2, read - 2)
        return Detected(StandardCharsets.UTF_16BE, pb)
    }

    private fun detectUtf16LeBom(bytes: ByteArray, read: Int, pb: PushbackInputStream): Detected? {
        if (!hasUtf16LeBom(bytes, read)) return null
        if (read > 2) pb.unread(bytes, 2, read - 2)
        return Detected(StandardCharsets.UTF_16LE, pb)
    }

    /**
     * `--encoding <explicit>`-Pfad: kein Auto-Detect. Konsumiert aber
     * ein vorhandenes BOM, wenn es zum angeforderten Charset passt —
     * sonst bleibt es im Stream stehen.
     *
     * @param raw Ungewrappter Input-Stream.
     * @param explicit Expliziter Charset aus `--encoding <value>`.
     * @return Input-Stream, der entweder ohne BOM oder exakt wie das
     *   Original weiterläuft (je nach Match).
     */
    fun wrapWithExplicit(raw: InputStream, explicit: Charset): InputStream {
        val pb = PushbackInputStream(raw, 4)
        val first4 = ByteArray(4)
        val read = readFully(pb, first4)
        if (read == 0) return pb

        val matchedBomLen = matchBomFor(explicit, first4, read)
        if (matchedBomLen > 0) {
            // Consume the BOM, push back the tail.
            if (read > matchedBomLen) {
                pb.unread(first4, matchedBomLen, read - matchedBomLen)
            }
        } else {
            // No matching BOM → push ALL bytes back; the explicit charset
            // keeps precedence, and a non-matching BOM (if any) is read
            // as normal data by the decoder. That will typically produce
            // a MalformedInputException, which is the intended failure
            // mode per §6.9 ("BOM-Mismatch bleibt stehen und wird nicht
            // still weginterpretiert").
            pb.unread(first4, 0, read)
        }
        return pb
    }

    /**
     * Anzahl Bytes des passenden BOMs für [charset] im Prefix von
     * [bytes], oder 0, wenn kein BOM gefunden (oder es nicht zum
     * Charset passt). Prüft UTF-32 zuerst (vor UTF-16 LE, siehe
     * [detectOrFallback]).
     */
    private fun matchBomFor(charset: Charset, bytes: ByteArray, read: Int): Int {
        val name = charset.name().uppercase()
        return when (name) {
            "UTF-8" -> if (hasUtf8Bom(bytes, read)) 3 else 0
            "UTF-16BE" -> if (hasUtf16BeBom(bytes, read)) 2 else 0
            "UTF-16LE" -> if (hasUtf16LeBom(bytes, read)) 2 else 0
            else -> 0
        }
    }

    private fun hasUtf32BeBom(bytes: ByteArray, read: Int): Boolean =
        read >= 4 && matchesPrefix(bytes, 0x00, 0x00, 0xFE, 0xFF)

    private fun hasUtf32LeBom(bytes: ByteArray, read: Int): Boolean =
        read >= 4 && matchesPrefix(bytes, 0xFF, 0xFE, 0x00, 0x00)

    private fun hasUtf8Bom(bytes: ByteArray, read: Int): Boolean =
        read >= 3 && matchesPrefix(bytes, 0xEF, 0xBB, 0xBF)

    private fun hasUtf16BeBom(bytes: ByteArray, read: Int): Boolean =
        read >= 2 && matchesPrefix(bytes, 0xFE, 0xFF)

    private fun hasUtf16LeBom(bytes: ByteArray, read: Int): Boolean =
        read >= 2 &&
            matchesPrefix(bytes, 0xFF, 0xFE) &&
            !hasUtf32LeBom(bytes, read)

    private fun matchesPrefix(bytes: ByteArray, vararg expected: Int): Boolean =
        expected.indices.all { index -> bytes[index] == expected[index].toByte() }

    /**
     * Liest bis zu [buf].size Bytes aus [s], sammelt auch kurze
     * `read()`-Rückgaben auf und gibt die tatsächlich gelesene Länge
     * zurück. Wird gebraucht, weil [InputStream.read] nicht garantiert
     * die volle Länge in einem Aufruf liefert.
     */
    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = s.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

/**
 * Geworfen, wenn die Eingabe ein nicht-unterstütztes Encoding-BOM trägt
 * (z.B. UTF-32) oder der vom Nutzer angegebene Charset unbekannt ist.
 * Der CLI-Aufrufer mappt das auf Exit 2 mit Hinweis auf `--encoding`.
 */
class UnsupportedFileEncodingException(message: String) : RuntimeException(message)
