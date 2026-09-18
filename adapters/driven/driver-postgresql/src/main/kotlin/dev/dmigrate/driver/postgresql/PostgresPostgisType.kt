package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Die beiden PostGIS-Spaltentypen im Reverse: wann ein benutzerdefinierter
 * Typ PostGIS gehoert, und was er dann im neutralen Modell wird.
 *
 * **Warum als eigenes Objekt.** `geometry` und `geography` sind gewoehnliche
 * Typnamen; ein Anwendertyp darf so heissen, solange er in einem anderen
 * Schema liegt. Die Unterscheidung braucht deshalb Name **und** Schema — und
 * dieselbe Frage stellt nicht nur das Typ-Mapping, sondern auch die Meldung
 * ueber unerreichbare Registriersichten ([notePostgisOutOfReach]). Beide
 * Stellen gehen ueber dieses eine Praedikat.
 */
internal object PostgresPostgisType {

    /** Die beiden PostGIS-Spaltentypen, die der Reverse als Geometrie liest. */
    private const val GEOMETRY_UDT = "geometry"
    private const val GEOGRAPHY_UDT = "geography"

    /** Ob der Typname einer der beiden PostGIS-Spaltentypen ist. */
    fun isUdtName(udtName: String?): Boolean {
        val lower = udtName?.lowercase() ?: return false
        return lower == GEOMETRY_UDT || lower == GEOGRAPHY_UDT
    }

    /**
     * PostGIS ist der Eigentuemer des Typs, wenn sein Schema das Schema der
     * Extension ist. Ohne beide Angaben faellt die Antwort auf „nein" — eine
     * Spalte, deren Herkunft der Reverse nicht belegen kann, als
     * PostGIS-Geometrie zu lesen hiesse raten. Ist PostGIS nicht installiert
     * (`postgisSchema` ist `null`), kann es keine PostGIS-Spalte geben.
     */
    fun isOwnedByPostgis(udtName: String?, udtSchema: String?, postgisSchema: String?): Boolean =
        isUdtName(udtName) && postgisSchema != null && udtSchema == postgisSchema

    /**
     * Die neutrale Form einer PostGIS-Spalte — oder `null`, wenn der Typ
     * keine ist.
     *
     * Subtyp und SRID kommen aus `geometry_columns` bzw. `geography_columns`
     * (`null` → `GEOMETRY` / keine SRID).
     */
    fun mapColumn(
        udtName: String,
        udtSchema: String?,
        postgisSchema: String?,
        objectName: String,
        subtype: String?,
        srid: Int?,
    ): PostgresTypeMapping.MappingResult? {
        if (!isOwnedByPostgis(udtName, udtSchema, postgisSchema)) return null
        return PostgresTypeMapping.MappingResult(
            type = NeutralType.Geometry(geometryType = GeometryType.of(subtype), srid = srid),
            note = if (udtName.lowercase() == GEOGRAPHY_UDT) geographyNote(objectName, srid) else geometryNote(objectName),
        )
    }

    private fun geometryNote(objectName: String) = SchemaReadNote(
        severity = SchemaReadSeverity.INFO,
        code = "R401",
        objectName = objectName,
        message = "PostGIS geometry column uses the PostGIS extension",
        hint = "Extension installation is reported separately by reverse note R400",
    )

    /**
     * `R403` — eine `geography`-Spalte liest als neutrale Geometrie.
     *
     * Das neutrale Modell kennt nur **eine** Geometrie; ein PostgreSQL-Ziel
     * rendert sie deshalb als `geometry` mit demselben SRID
     * (`spec/ddl-generation-rules.md`, Abschnitt 16.2). Auf `geography`
     * rechnet PostGIS Abstaende und Flaechen auf dem Ellipsoid, auf
     * `geometry` in der Ebene — die Bedeutung von Abfragen auf der Spalte
     * aendert sich also schon PostgreSQL → PostgreSQL.
     *
     * Severity `WARNING` und nicht `INFO`: die Klartext-Ausgabe blendet
     * `INFO` ohne `--verbose` aus, und ein geaendertes Rechenergebnis
     * verdient einen Blick ohne Zusatzschalter.
     */
    private fun geographyNote(objectName: String, srid: Int?) = SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "R403",
        objectName = objectName,
        message = "PostGIS 'geography' column read as neutral geometry" +
            (srid?.let { " with SRID $it" } ?: "") +
            ": a PostgreSQL target renders it as 'geometry' with the same SRID, and distances and areas " +
            "are then computed on the plane instead of on the ellipsoid.",
        hint = "Change the column type back to 'geography' on a PostgreSQL target if geodesic " +
            "measurement matters; SQL Server picks 'geography' from the SRID by itself.",
    )
}
