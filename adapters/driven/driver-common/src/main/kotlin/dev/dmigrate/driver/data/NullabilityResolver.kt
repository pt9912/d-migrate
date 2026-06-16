package dev.dmigrate.driver.data

import java.sql.ResultSetMetaData

/**
 * Loest die Nullability einer Spalte aus den vorhandenen
 * Quellen (JDBC-Metadaten + optional SchemaReader-Hint) per
 * AP2 §9 Resolver-Regeln (`docs/planning/done-archive/parquet-schema-source.md`).
 *
 * Eingabe-Form spiegelt `ResultSetMetaData.isNullable(i)`
 * (Werte `columnNoNulls=0`, `columnNullable=1`,
 * `columnNullableUnknown=2`); der Resolver normalisiert sie
 * auf die drei AP2-Faelle NOT_NULL/NULLABLE/UNKNOWN.
 *
 * AP2 §9 verlangt zusaetzlich Treiber-Audits fuer PG/MySQL/
 * SQLite. Die Audits sind AP2.c-Folgeaufgabe (eigene
 * `@Tag("schema-audit")`-Tests); der Resolver selbst ist
 * dialekt-neutral.
 */
internal object NullabilityResolver {

    fun resolve(
        jdbcIsNullable: Int,
        schemaReaderNullable: Boolean? = null,
    ): NullabilityDecision {
        val jdbc = normalizeJdbc(jdbcIsNullable)
        return when (jdbc) {
            JdbcNullable.NULLABLE -> when (schemaReaderNullable) {
                null -> NullabilityDecision(
                    nullable = true,
                    origin = NullabilityOrigin.JDBC_METADATA,
                )
                true -> NullabilityDecision(
                    nullable = true,
                    origin = NullabilityOrigin.MERGED,
                )
                false -> NullabilityDecision(
                    nullable = false,
                    origin = NullabilityOrigin.MERGED_CONFLICT,
                    diagnostic = "JDBC=NULLABLE, SchemaReader=NOT_NULL — SchemaReader gewinnt",
                )
            }
            JdbcNullable.NOT_NULL -> when (schemaReaderNullable) {
                null -> NullabilityDecision(
                    nullable = false,
                    origin = NullabilityOrigin.JDBC_METADATA,
                )
                false -> NullabilityDecision(
                    nullable = false,
                    origin = NullabilityOrigin.MERGED,
                )
                true -> NullabilityDecision(
                    nullable = true,
                    origin = NullabilityOrigin.MERGED_CONFLICT,
                    diagnostic = "JDBC=NOT_NULL, SchemaReader=NULLABLE — SchemaReader gewinnt",
                )
            }
            JdbcNullable.UNKNOWN -> when (schemaReaderNullable) {
                null -> NullabilityDecision(
                    nullable = true,
                    origin = NullabilityOrigin.DEFAULT_PERMISSIVE,
                    diagnostic = "JDBC=UNKNOWN, kein SchemaReader-Hint — Fallback auf nullable=true",
                )
                else -> NullabilityDecision(
                    nullable = schemaReaderNullable,
                    origin = NullabilityOrigin.SCHEMA_READER,
                )
            }
        }
    }

    private fun normalizeJdbc(raw: Int): JdbcNullable = when (raw) {
        ResultSetMetaData.columnNoNulls -> JdbcNullable.NOT_NULL
        ResultSetMetaData.columnNullable -> JdbcNullable.NULLABLE
        else -> JdbcNullable.UNKNOWN
    }

    private enum class JdbcNullable { NULLABLE, NOT_NULL, UNKNOWN }
}

/**
 * Ergebnis des Nullability-Resolvers (AP2 §9). [origin]
 * dokumentiert, welche Quelle die Entscheidung getragen hat;
 * [diagnostic] traegt Konflikte oder Fallback-Begruendungen
 * (z.B. `MERGED_CONFLICT`, `DEFAULT_PERMISSIVE`).
 *
 * Bewusst keine `sealed interface` mit Per-Origin-
 * Subklassen — die AP2-Variantenstruktur ist linear (drei
 * Felder, keine origin-spezifischen Zusatzfelder); eine
 * Sealed-Hierarchie waere Buchhaltungs-Overhead.
 */
internal data class NullabilityDecision(
    val nullable: Boolean,
    val origin: NullabilityOrigin,
    val diagnostic: String? = null,
)

/** AP2 §9 Provenance. */
internal enum class NullabilityOrigin {
    JDBC_METADATA,
    SCHEMA_READER,
    MERGED,
    MERGED_CONFLICT,
    DEFAULT_PERMISSIVE,
}
