package dev.dmigrate.cli.commands

import dev.dmigrate.cli.i18n.TemporalFormatPolicy
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.streaming.checkpoint.CheckpointResumePosition

/**
 * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §4.1 / §5.2):
 * Konvertiert zwischen der Runtime-Sicht ([ResumeMarker.Position]) und
 * der serialisierten Manifest-Sicht ([CheckpointResumePosition]).
 *
 * Design-Entscheidung:
 *
 * - **Encode**: jeder Wert wandert ueber `Any?.toString()` in das
 *   Manifest. Das ist **bewusst** typenfrei — eine Spaltensignatur wird
 *   bereits ueber den `optionsFingerprint` abgesichert, eine separate
 *   Typ-Annotation im Manifest waere doppelte Absicherung.
 * - **Decode**: wiederverwendet den vorhandenen
 *   [TemporalFormatPolicy.parseSinceLiteral]-Pfad, der schon CLI
 *   `--since`-Literale konsistent typinferiert (LocalDate, OffsetDateTime,
 *   Long, BigDecimal, String). Fuer Tie-Breaker gilt derselbe Vertrag.
 *
 * Begrenzung: unkonventionelle Marker-/PK-Typen (Blob, Array) werden
 * nicht unterstuetzt. Das ist in `docs/ImpPlan-0.9.0-C2.md` §8.2 bereits
 * als Nutzer-Einschraenkung dokumentiert.
 */
internal object MarkerCodec {

    /**
     * Wandelt einen Runtime-`Any?`-Wert in die Manifest-String-Form.
     * `null` bleibt `null`, jeder andere Wert geht durch `toString()`.
     */
    fun encodeValue(value: Any?): String? = value?.toString()

    /**
     * Wandelt einen Manifest-String zurueck in einen bindbaren
     * JDBC-Wert. `null` bleibt `null`; ansonsten liefert die
     * Type-Inferenz aus [TemporalFormatPolicy.parseSinceLiteral] den
     * geeigneten Typ (Long, OffsetDateTime, LocalDateTime, LocalDate,
     * BigDecimal oder String).
     */
    fun decodeValue(encoded: String?): Any? =
        encoded?.let { TemporalFormatPolicy.parseSinceLiteral(it) }

    /**
     * [ResumeMarker.Position] → [CheckpointResumePosition] mit den
     * Marker-Metadaten aus dem umgebenden [ResumeMarker].
     */
    fun toPersisted(
        marker: ResumeMarker,
        position: ResumeMarker.Position,
    ): CheckpointResumePosition = CheckpointResumePosition(
        markerColumn = marker.markerColumn,
        markerValue = encodeValue(position.lastMarkerValue),
        tieBreakerColumns = marker.tieBreakerColumns,
        tieBreakerValues = position.lastTieBreakerValues.map(::encodeValue),
    )

    /**
     * [CheckpointResumePosition] → [ResumeMarker.Position] mit
     * JDBC-bindbaren Werten. Der Runner bildet spaeter daraus einen
     * [ResumeMarker] in Kombination mit den aktuellen Tie-Breaker-
     * Spalten.
     */
    fun toRuntimePosition(persisted: CheckpointResumePosition): ResumeMarker.Position =
        ResumeMarker.Position(
            lastMarkerValue = decodeValue(persisted.markerValue),
            lastTieBreakerValues = persisted.tieBreakerValues.map(::decodeValue),
        )
}
