package dev.dmigrate.streaming

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SeekableChunkSource
import java.io.InputStream

/**
 * Aufgeloeste Tabellen-Eingabe fuer den Import-Pfad (AP10
 * §5.4 / S2 Cut A — `docs/planning/in-progress/parquet-productive-cut-a.md`).
 *
 * Sealed mit zwei Subtypen:
 *
 * - [Stream]: bisherige Form fuer JSON/YAML/CSV (Reader haengt
 *   am InputStream — siehe `parquet-libraries.md` §7 Bullet 2,
 *   kein impliziter Temp-Spool).
 * - [Seekable]: neuer Pfad fuer Parquet (Reader haengt an
 *   [SeekableChunkSource] plus aufgeloestem [ChunkSchema]; das
 *   Schema kommt vom AP7/AP8/AP9-Preflight, nicht aus dem
 *   Datei-Footer — AP10 §3.3).
 *
 * S2 introduziert nur die Sealed-Struktur; die einzige
 * produktive [Seekable]-Quelle wird in S5a/S5b (Bundle- bzw.
 * Single-File-Resolver) verdrahtet, der `TableImporter`-
 * Konsum folgt in S7.
 */
internal sealed class ResolvedTableInput {
    abstract val table: String

    internal data class Stream(
        override val table: String,
        val openInput: () -> InputStream,
    ) : ResolvedTableInput()

    internal data class Seekable(
        override val table: String,
        val source: SeekableChunkSource,
        val schema: ChunkSchema,
    ) : ResolvedTableInput()
}
