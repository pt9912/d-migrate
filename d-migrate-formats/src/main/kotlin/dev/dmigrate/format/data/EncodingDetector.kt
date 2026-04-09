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
     * @throws UnsupportedEncodingException für UTF-32-BOMs
     */
    fun detectOrFallback(raw: InputStream): Detected {
        val pb = PushbackInputStream(raw, 4)
        val first4 = ByteArray(4)
        val read = readFully(pb, first4)

        // Prüfe UTF-32 zuerst, weil UTF-16 LE BOM (FF FE) ein Prefix von
        // UTF-32 LE BOM (FF FE 00 00) ist. Ohne diese Reihenfolge würde
        // UTF-32-LE still als UTF-16-LE fehldeuten.
        if (read == 4 &&
            first4[0] == 0x00.toByte() && first4[1] == 0x00.toByte() &&
            first4[2] == 0xFE.toByte() && first4[3] == 0xFF.toByte()
        ) {
            throw UnsupportedEncodingException(
                "UTF-32 BE BOM detected — UTF-32 is not supported in 0.4.0. " +
                    "Set --encoding explicitly (e.g. UTF-16LE) or convert the file."
            )
        }
        if (read == 4 &&
            first4[0] == 0xFF.toByte() && first4[1] == 0xFE.toByte() &&
            first4[2] == 0x00.toByte() && first4[3] == 0x00.toByte()
        ) {
            throw UnsupportedEncodingException(
                "UTF-32 LE BOM detected — UTF-32 is not supported in 0.4.0. " +
                    "Set --encoding explicitly (e.g. UTF-16LE) or convert the file."
            )
        }

        // UTF-8 BOM (3 bytes)
        if (read >= 3 &&
            first4[0] == 0xEF.toByte() && first4[1] == 0xBB.toByte() && first4[2] == 0xBF.toByte()
        ) {
            // Push back the 4th byte (if any) — the BOM is consumed.
            if (read == 4) pb.unread(first4, 3, 1)
            return Detected(StandardCharsets.UTF_8, pb)
        }

        // UTF-16 BE BOM (2 bytes, 0xFE 0xFF)
        if (read >= 2 && first4[0] == 0xFE.toByte() && first4[1] == 0xFF.toByte()) {
            // Push back bytes 2..read-1 (the tail we already read past the BOM).
            if (read > 2) pb.unread(first4, 2, read - 2)
            return Detected(StandardCharsets.UTF_16BE, pb)
        }

        // UTF-16 LE BOM (2 bytes, 0xFF 0xFE)
        // Safe here because UTF-32 LE was already handled above.
        if (read >= 2 && first4[0] == 0xFF.toByte() && first4[1] == 0xFE.toByte()) {
            if (read > 2) pb.unread(first4, 2, read - 2)
            return Detected(StandardCharsets.UTF_16LE, pb)
        }

        // No BOM → UTF-8 fallback, push ALL read bytes back so the parser
        // starts from position 0 of the real content.
        if (read > 0) pb.unread(first4, 0, read)
        return Detected(StandardCharsets.UTF_8, pb)
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
            "UTF-8" -> if (
                read >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) 3 else 0

            "UTF-16BE" -> if (
                read >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
            ) 2 else 0

            "UTF-16LE" -> if (
                read >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                // Don't eat a UTF-32 LE BOM as if it were UTF-16 LE
                !(read >= 4 && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte())
            ) 2 else 0

            else -> 0
        }
    }

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
class UnsupportedEncodingException(message: String) : RuntimeException(message)
