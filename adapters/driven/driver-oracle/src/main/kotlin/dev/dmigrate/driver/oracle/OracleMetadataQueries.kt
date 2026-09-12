package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.OracleServerVersion
import dev.dmigrate.driver.metadata.ConstraintProjection
import dev.dmigrate.driver.metadata.ForeignKeyProjection
import dev.dmigrate.driver.metadata.IndexProjection
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.TableRef
import java.sql.Clob

/**
 * Katalog-Queries für den Oracle-Reverse-Read. `ALL_*`-Sichten (nicht
 * `USER_*`) mit explizitem `owner`-Filter, damit ein Aufrufer mit
 * weitreichenderen Grants nicht versehentlich andere Schemas sieht.
 * Recycle-Bin-Objekte (`BIN$...`) werden ausgeschlossen.
 */
internal object OracleMetadataQueries {

    data class ColumnRow(
        val name: String,
        val typeName: String,
        val length: Int?,
        val precision: Int?,
        val scale: Int?,
        val nullable: Boolean,
        val isIdentity: Boolean,
        val identityGeneration: String?,
        val identitySequenceName: String?,
        val defaultDefinition: String?,
        val ordinal: Int,
    )

    /**
     * [indices] traegt auch Indizes ueber einem Ausdruck, den das neutrale
     * Modell als `IndexColumn.expression` fuehrt. Oracle
     * fuehrt an ihrer Stelle eine unsichtbare Systemspalte (`SYS_NC00006$`);
     * den echten Ausdruck liefert `ALL_IND_EXPRESSIONS`.
     */
    data class IndexScan(
        val indices: List<IndexProjection>,
        /**
         * Namen der Indizes, die Oracle Text traegt (`INDEX_TYPE = DOMAIN`
         * mit `CTXSYS.CONTEXT`). Der Katalog fuehrt sie nicht als eigene
         * Indexart, sondern ueber den Indextyp-Eigner — die Unterscheidung
         * gehoert deshalb hierher, nicht in die Typabbildung des Lesers.
         */
        val fullTextIndexes: Set<String> = emptySet(),
        /**
         * Namen der Indizes, die Oracle Spatial traegt (`INDEX_TYPE = DOMAIN`
         * mit `MDSYS.SPATIAL_INDEX_V2` oder dem Vorgaenger
         * `MDSYS.SPATIAL_INDEX`) — aus demselben Grund hier wie
         * [fullTextIndexes].
         */
        val spatialIndexes: Set<String> = emptySet(),
        /**
         * Domain-Indizes einer **anderen** Indexart (benutzereigen).
         * Sie als B-Tree zu lesen ergaebe im Ziel einen Index, der etwas
         * anderes tut; sie werden ausgelassen und gemeldet (R357).
         */
        val foreignDomainIndexes: List<String> = emptyList(),
    )

    data class SequenceRow(
        val name: String,
        val lastNumber: Long,
        val increment: Long,
        val minValue: Long?,
        val maxValue: Long?,
        val cycle: Boolean,
        val cache: Int?,
    )

    data class ViewRow(val name: String, val text: String)

    /**
     * Die Objekte, von denen eine View abhaengt, aufgeteilt nach dem, was
     * die Projektion belegen kann.
     *
     * [tables]/[views] fuehren nur Objekte **desselben Schemas** —
     * `ALL_DEPENDENCIES` liefert auch schemafremde Verweise (gemessen: eine
     * `SELECT 1 FROM dual`-View traegt `PUBLIC.DUAL` als `SYNONYM`), die im
     * neutralen Modell keine Entsprechung haben.
     *
     * Eine View, die im Ergebnis GAR NICHT vorkommt, ist etwas anderes als
     * eine mit leeren Listen: **jede View traegt mindestens eine
     * `ALL_DEPENDENCIES`-Zeile** — selbst die ueber `dual`.
     * Fehlt sie ganz, sieht der lesende Nutzer die Abhaengigkeiten nicht
     * (fehlende Rechte auf die referenzierten Objekte).
     *
     * [unmappedInSchema] zaehlt die Zeilen, die im eigenen Schema liegen,
     * aber weder `TABLE` noch `VIEW` sind — vor allem **Synonyme**, die
     * gemessen als eigener `referenced_type` auftreten. Ohne diese Zahl
     * liesse sich „referenziert wirklich nichts im Schema" nicht von
     * „referenziert eine Tabelle ueber ein Synonym" unterscheiden, und der
     * zweite Fall wuerde faelschlich als verifiziert leer gelten: der
     * Reprojector faende dann beim Rename nichts und liesse die Sicht still
     * invalid zurueck.
     */
    data class ViewDependencyRow(
        val tables: List<String>,
        val views: List<String>,
        val unmappedInSchema: Int,
    )

    /** Identity-Spalte fuer den Datenpfad: Name, Erzeugungsmodus, Sequenzname, Increment. */
    data class IdentityColumnRow(
        val column: String,
        val generation: String,
        val sequenceName: String,
        val increment: Long,
    )

    /**
     * Ohne **Sekundaerobjekte**. Ein Oracle-Text-Index legt im selben Schema
     * eigene Tabellen an — live gemessen sieben Stueck je Index
     * (`DR${'$'}<index>${'$'}B/C/I/K/N/Q/U`), gewoehnliche Zeilen in `ALL_TABLES`.
     * Sie mitzulesen ergaebe Phantom-Tabellen im Reverse, Drift nach jedem
     * `migrate --execute` und — ueber [OracleTableLister], der dieselbe
     * Abfrage nutzt — einen Datenpfad, der Oracle-interne Token-Tabellen
     * kopiert.
     *
     * `ALL_TABLES` fuehrt kein Kennzeichen dafuer; `ALL_OBJECTS.SECONDARY`
     * schon (gemessen: `Y` fuer alle sieben, `N` fuer die echte Tabelle).
     * Ueber den Namen zu filtern waere die schlechtere Loesung — `DR${'$'}`
     * ist keine reservierte Zeichenfolge.
     *
     * Ohne **Materialized Views und ihre Logs**, aus demselben Grund und mit
     * derselben Folge. Beide stehen als gewoehnliche Zeilen in `ALL_TABLES`
     * (gemessen: eine MV traegt dort ihren eigenen Namen, ein Log den Namen
     * `MLOG${'$'}_<tabelle>`), und `SECONDARY` ist bei beiden `N` — der
     * Sekundaerobjekt-Filter greift also nicht. Ausgeschlossen werden sie
     * ueber die Katalogsichten, die den Begriff fuehren, statt ueber ein
     * Namensmuster.
     */
    fun listTableRefs(session: JdbcOperations, schema: String): List<TableRef> =
        session.queryList(
            """
            SELECT t.table_name
            FROM all_tables t
            WHERE t.owner = ? AND t.table_name NOT LIKE 'BIN${'$'}%'
              AND NOT EXISTS (
                SELECT 1 FROM all_objects o
                WHERE o.owner = t.owner AND o.object_name = t.table_name
                  AND o.object_type = 'TABLE' AND o.secondary = 'Y'
              )
              AND NOT EXISTS (
                SELECT 1 FROM all_mviews m
                WHERE m.owner = t.owner AND m.mview_name = t.table_name
              )
              AND NOT EXISTS (
                SELECT 1 FROM all_mview_logs l
                WHERE l.log_owner = t.owner AND l.log_table = t.table_name
              )
            ORDER BY t.table_name
            """.trimIndent(),
            schema,
        ).map { row -> TableRef(name = row.string("table_name"), schema = schema) }

    /** Die SRID einer Geometriespalte aus `USER_SDO_GEOM_METADATA`. */
    data class GeometryMetadataRow(val column: String, val srid: Int?)

    /**
     * Die SRID je Geometriespalte.
     *
     * Oracle traegt sie **nicht** am Spaltentyp — `ALL_TAB_COLUMNS` meldet nur
     * `SDO_GEOMETRY` mit `DATA_TYPE_OWNER = 'PUBLIC'`. Ohne diese Sicht kaeme
     * jede Geometrie ohne Koordinatensystem zurueck.
     *
     * Ohne installiertes Oracle Spatial existiert die Sicht nicht; der
     * Aufrufer faengt das ab und liest die Spalte dann ohne SRID.
     */
    fun listGeometryMetadata(
        session: JdbcOperations,
        schema: String,
        table: String,
    ): List<GeometryMetadataRow> = session.queryList(
        """
        SELECT m.column_name, m.srid
        FROM all_sdo_geom_metadata m
        WHERE m.owner = ? AND m.table_name = ?
        """.trimIndent(),
        schema,
        table,
    ).map { row ->
        GeometryMetadataRow(column = row.string("column_name"), srid = row.int("srid"))
    }

    fun listColumns(session: JdbcOperations, schema: String, table: String): List<ColumnRow> =
        session.queryList(
            """
            SELECT c.column_name, c.data_type, c.data_length, c.data_precision, c.data_scale,
                   c.nullable, c.column_id, c.data_default,
                   ic.generation_type AS identity_generation, ic.sequence_name AS identity_sequence
            FROM all_tab_columns c
            LEFT JOIN all_tab_identity_cols ic
                ON ic.owner = c.owner AND ic.table_name = c.table_name AND ic.column_name = c.column_name
            WHERE c.owner = ? AND c.table_name = ?
            ORDER BY c.column_id
            """.trimIndent(),
            schema,
            table,
        ).map { row ->
            ColumnRow(
                name = row.string("column_name"),
                typeName = row.string("data_type"),
                length = row.int("data_length"),
                precision = row.int("data_precision"),
                scale = row.int("data_scale"),
                nullable = row.string("nullable") == "Y",
                isIdentity = row["identity_generation"] != null,
                identityGeneration = row["identity_generation"] as? String,
                identitySequenceName = row["identity_sequence"] as? String,
                defaultDefinition = (row["data_default"] as? String)?.trim()?.ifEmpty { null },
                ordinal = row.int("column_id") ?: 0,
            )
        }



    /**
     * Indizes ohne die, die bereits die PK-Constraint tragen
     * (`ALL_CONSTRAINTS.INDEX_NAME` -- Oracles Aequivalent zu MSSQLs
     * `is_primary_key`-Flag). UNIQUE-Constraint-Indizes bleiben ABSICHTLICH
     * erhalten (anders als PK): es gibt keine gesonderte Oracle-Abfrage fuer
     * UNIQUE-Constraints, `singleColumnUniqueFromIndices`/
     * `buildMultiColumnUniqueFromIndices` heben sie aus genau diesem Scan.
     */
    fun scanIndexes(session: JdbcOperations, schema: String, table: String): IndexScan {
        val primaryKeyIndexNames = session.queryList(
            """
            SELECT index_name
            FROM all_constraints
            WHERE owner = ? AND table_name = ? AND constraint_type = 'P' AND index_name IS NOT NULL
            """.trimIndent(),
            schema,
            table,
        ).mapNotNull { it["index_name"] as? String }.toSet()

        val rows = session.queryList(
            """
            SELECT i.index_name, i.index_type, i.uniqueness, i.ityp_owner, i.ityp_name,
                   ic.column_name, ic.column_position, ic.descend
            FROM all_indexes i
            JOIN all_ind_columns ic
                ON ic.index_owner = i.owner AND ic.index_name = i.index_name
            WHERE i.owner = ? AND i.table_name = ?
            ORDER BY i.index_name, ic.column_position
            """.trimIndent(),
            schema,
            table,
        )
        val expressions = indexExpressions(session, schema, table)
        val indices = mutableListOf<IndexProjection>()
        val fullTextIndexes = mutableSetOf<String>()
        val spatialIndexes = mutableSetOf<String>()
        val foreignDomainIndexes = mutableListOf<String>()
        rows.groupBy { it.string("index_name") }
            .filterKeys { it !in primaryKeyIndexNames }
            .forEach { (name, group) ->
                val head = group.first()
                val domain = domainKind(head)
                if (domain == DomainKind.FOREIGN) {
                    foreignDomainIndexes += name
                    return@forEach
                }
                if (domain == DomainKind.FULL_TEXT) fullTextIndexes += name
                if (domain == DomainKind.SPATIAL) spatialIndexes += name
                val keys = resolveIndexColumns(name, group, expressions)
                indices += IndexProjection(
                    name = name,
                    columns = keys.map { it.text },
                    isUnique = head.string("uniqueness") == "UNIQUE",
                    type = head.string("index_type"),
                    directions = group.map { row ->
                        if (row["descend"] as? String == "DESC") {
                            dev.dmigrate.core.model.IndexSortDirection.DESC
                        } else {
                            null
                        }
                    },
                    expressionPositions = keys.withIndex()
                        .filter { it.value.isExpression }
                        .map { it.index }
                        .toSet(),
                )
            }
        return IndexScan(
            indices = indices,
            fullTextIndexes = fullTextIndexes,
            spatialIndexes = spatialIndexes,
            foreignDomainIndexes = foreignDomainIndexes,
        )
    }

    private enum class DomainKind { NONE, FULL_TEXT, SPATIAL, FOREIGN }

    /**
     * Ein Domain-Index traegt seine Art nicht in `INDEX_TYPE`, sondern in
     * `ITYP_OWNER`/`ITYP_NAME` — gemessen `CTXSYS`/`CONTEXT` fuer Oracle Text
     * und `MDSYS`/`SPATIAL_INDEX_V2` fuer Oracle Spatial. Benutzereigene
     * Indextypen haben im neutralen Modell keine Entsprechung.
     *
     * Beide Spatial-Indextypen zaehlen: `SPATIAL_INDEX_V2` ist der heutige,
     * `SPATIAL_INDEX` der Vorgaenger, den aeltere Bestaende tragen. Sie
     * unterscheiden sich in Speicherform und Duldsamkeit, nicht in dem, was
     * das neutrale Modell von ihnen abbildet — ein raeumlicher Index ueber
     * eine Geometriespalte.
     */
    private fun domainKind(row: Map<String, Any?>): DomainKind {
        if (row.stringOrNull("index_type") != "DOMAIN") return DomainKind.NONE
        val owner = row.stringOrNull("ityp_owner")
        val name = row.stringOrNull("ityp_name")
        return when {
            owner == "CTXSYS" && name == "CONTEXT" -> DomainKind.FULL_TEXT
            owner == "MDSYS" && name in SPATIAL_INDEX_TYPES -> DomainKind.SPATIAL
            else -> DomainKind.FOREIGN
        }
    }

    private val SPATIAL_INDEX_TYPES = setOf("SPATIAL_INDEX_V2", "SPATIAL_INDEX")

    /**
     * `ALL_IND_EXPRESSIONS.COLUMN_EXPRESSION` je (Indexname, Spaltenposition).
     * Gefuellt fuer jede Spalte eines Function-based-Index -- und das ist
     * Oracle auch bei `CREATE INDEX … (spalte DESC)`: absteigende Indizes sind
     * intern function-based (`INDEX_TYPE = FUNCTION-BASED
     * NORMAL`), ihr Ausdruck ist dann aber nur der Spaltenname selbst.
     */
    private fun indexExpressions(
        session: JdbcOperations,
        schema: String,
        table: String,
    ): Map<Pair<String, Int>, String> = session.queryList(
        """
        SELECT index_name, column_position, column_expression
        FROM all_ind_expressions
        WHERE index_owner = ? AND table_name = ?
        """.trimIndent(),
        schema,
        table,
    ).mapNotNull { row ->
        val expression = row["column_expression"]?.toString() ?: return@mapNotNull null
        (row.string("index_name") to (row["column_position"] as Number).toInt()) to expression
    }.toMap()

    /** Ein aufgeloester Indexschluessel: entweder eine Spalte oder ein Ausdruck. */
    data class ResolvedKey(val text: String, val isExpression: Boolean)

    /**
     * Loest die Schluesselspalten eines Index auf. Ein Ausdruck, der nur aus
     * einem zitierten Bezeichner besteht, IST die Spalte — so sieht ein
     * DESC-Index von innen aus; alles andere bleibt ein Ausdruck.
     */
    private fun resolveIndexColumns(
        indexName: String,
        group: List<Map<String, Any?>>,
        expressions: Map<Pair<String, Int>, String>,
    ): List<ResolvedKey> = group.map { row ->
        val position = (row["column_position"] as Number).toInt()
        val expression = expressions[indexName to position]
            ?: return@map ResolvedKey(row.string("column_name"), isExpression = false)
        val plainColumn = PLAIN_COLUMN_EXPRESSION.matchEntire(expression.trim())
            ?.groupValues?.get(1)?.replace("\"\"", "\"")
        if (plainColumn != null) {
            ResolvedKey(plainColumn, isExpression = false)
        } else {
            ResolvedKey(expression.trim(), isExpression = true)
        }
    }

    /**
     * Ein Ausdruck, der NUR aus einem zitierten Bezeichner besteht. Oracle
     * verdoppelt ein Anfuehrungszeichen im Namen (`A"B` steht als `"A""B"`),
     * deshalb erlaubt das Muster `""` innerhalb — sonst gaelte ein
     * DESC-Index auf einer so benannten Spalte faelschlich als
     * Ausdrucks-Index -- und der Reverse gaebe seinen Ausdruck statt der Spalte
     * zurueck, obwohl es dieselbe Spalte ist.
     */
    private val PLAIN_COLUMN_EXPRESSION = Regex("""^"((?:[^"]|"")+)"$""")



    /**
     * Benannte Sequenzen des Schemas -- **ohne** die Sequenzen hinter
     * IDENTITY-Spalten.
     *
     * Oracle fuehrt die identity-gestuetzte Sequenz (`ISEQ$$_n`) in
     * `ALL_SEQUENCES` wie jede andere. Sie ist aber kein Objekt, das ein
     * Anwender deklariert hat: sie entsteht mit der Spalte, verschwindet mit
     * ihr, und `ALTER SEQUENCE` fasst sie nicht an (`ORA-32793`). Ungefiltert
     * traegt jedes reverse-gelesene Schema mit IDENTITY-Spalte eine Sequenz,
     * die im Soll-Schema niemals steht -- der Vergleich meldete sie als
     * fehlend, und `schema migrate` plante ein `DROP SEQUENCE`, das Oracle
     * ohnehin ablehnt.
     *
     * PostgreSQL loest dasselbe ueber `pg_depend.deptype IN ('a','i')`; das
     * Oracle-Gegenstueck ist `ALL_TAB_IDENTITY_COLS.SEQUENCE_NAME`.
     */
    fun listSequences(session: JdbcOperations, schema: String): List<SequenceRow> =
        session.queryList(
            """
            SELECT s.sequence_name, s.last_number, s.increment_by, s.min_value, s.max_value,
                   s.cycle_flag, s.cache_size
            FROM all_sequences s
            WHERE s.sequence_owner = ?
              AND NOT EXISTS (
                  SELECT 1 FROM all_tab_identity_cols i
                  WHERE i.owner = s.sequence_owner AND i.sequence_name = s.sequence_name
              )
            ORDER BY s.sequence_name
            """.trimIndent(),
            schema,
        ).map { row ->
            val increment = row.long("increment_by") ?: 1L
            SequenceRow(
                name = row.string("sequence_name"),
                lastNumber = row.long("last_number") ?: 1L,
                increment = increment,
                // Die verbleibende Default-Grenze faellt auf `null` zurueck:
                // aufsteigend ist `MINVALUE 1` Oracles Default, absteigend
                // `MAXVALUE -1`. `ALL_SEQUENCES` fuehrt kein Kennzeichen
                // dafuer, ob sie deklariert wurde — Oracle unterscheidet
                // "nicht angegeben" und "genau auf den Default gesetzt" also
                // selbst nicht, und der Reverse kann es folglich auch nicht.
                // Sie stehen zu lassen hiesse, jeder Oracle-Sequenz dauerhaft
                // eine `min_value: null -> 1`-Abweichung anzuhaengen: `schema
                // compare` erreichte nie "keine Aenderungen", und `migrate`
                // plante dasselbe `ALTER SEQUENCE` bei jedem Lauf erneut.
                minValue = row.sequenceBound("min_value")
                    ?.takeUnless { increment > 0 && it == ASCENDING_DEFAULT_MIN },
                maxValue = row.sequenceBound("max_value")
                    ?.takeUnless { increment < 0 && it == DESCENDING_DEFAULT_MAX },
                cycle = row.string("cycle_flag") == "Y",
                cache = row.int("cache_size")?.takeIf { it > 0 },
            )
        }

    /** `ALL_VIEWS.TEXT` ist bereits der reine SELECT-Text -- kein CREATE-VIEW-Wrapper. */
    /** Eine Materialized View: Refresh-Angaben und die Abfrage dahinter. */
    data class MaterializedViewRow(
        val name: String,
        /** `COMPLETE`, `FAST`, `FORCE` oder `NEVER`. */
        val refreshMethod: String?,
        /** `DEMAND`, `COMMIT` oder `NEVER`. */
        val refreshMode: String?,
        /** `null`, wenn der Katalog keinen Text fuehrt — nicht der Leerstring. */
        val query: String?,
    )

    /**
     * Die Materialized Views eines Schemas.
     *
     * `QUERY` steht **zuletzt** in der Auswahl, weil es eine `LONG`-Spalte
     * ist: der Oracle-Treiber streamt sie und schliesst den Strom, sobald
     * eine spaeter stehende Spalte gelesen wird. Der generische Zeilenleser
     * geht die Spalten aufsteigend durch, damit passt es — die Reihenfolge
     * hier ist die Bedingung dafuer, nicht Geschmack.
     *
     * Anders als `ALL_VIEWS.TEXT` kommt der Text unveraendert zurueck, so wie
     * der Autor ihn geschrieben hat (gemessen).
     */
    fun listMaterializedViews(session: JdbcOperations, schema: String): List<MaterializedViewRow> =
        session.queryList(
            """
            SELECT mview_name, refresh_method, refresh_mode, query
            FROM all_mviews
            WHERE owner = ?
            ORDER BY mview_name
            """.trimIndent(),
            schema,
        ).map { row ->
            MaterializedViewRow(
                name = row.string("mview_name"),
                refreshMethod = row.stringOrNull("refresh_method"),
                refreshMode = row.stringOrNull("refresh_mode"),
                // `null` bleibt `null`: der Planer unterscheidet „keine
                // Abfrage" von „leere Abfrage", und ein Leerstring liesse
                // seine Waechter ins Leere laufen.
                query = row.stringOrNull("query")?.trim()?.ifEmpty { null },
            )
        }

    fun listViews(session: JdbcOperations, schema: String): List<ViewRow> =
        session.queryList(
            """
            SELECT view_name, text
            FROM all_views
            WHERE owner = ?
            ORDER BY view_name
            """.trimIndent(),
            schema,
        ).map { row -> ViewRow(name = row.string("view_name"), text = row.string("text")) }

    /**
     * View-Abhaengigkeiten aus `ALL_DEPENDENCIES`, je View gebuendelt.
     *
     * Die Sicht ist **objektgenau, nicht spaltengenau** — eine
     * spaltengranulare Quelle gibt es in Oracle nicht (gemessen: es existiert
     * kein `ALL_DEPENDENCY_COLUMNS`, und unter den `SYS`-Sichten mit
     * `DEPENDENC` im Namen ist keine spaltenbezogene). `DependencyInfo.columns`
     * bleibt fuer Oracle deshalb leer, und der dialektunabhaengige
     * `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS`-Waechter des Planers greift —
     * dieselbe Lage wie bei MySQL.
     *
     * Der `referenced_owner`-Filter laeuft absichtlich NICHT in der
     * `WHERE`-Klausel: sonst liesse sich „keine Zeile im Schema" nicht von
     * „gar keine Zeile" (fehlende Rechte) unterscheiden.
     */
    fun listViewDependencies(session: JdbcOperations, schema: String): Map<String, ViewDependencyRow> =
        // Auch `MATERIALIZED VIEW`: der Katalog fuehrt eine MV unter dieser
        // Objektart. Ohne sie kaeme jede Oracle-MV ohne Abhaengigkeiten
        // zurueck, und die Waechter, die eine verwaiste Sicht verhindern,
        // liefen leer.
        dependenciesOf(session, schema, listOf("VIEW", "MATERIALIZED VIEW"))

    /**
     * Abhaengigkeiten von Routinen und Triggern, je Objekt gebuendelt.
     *
     * Dieselbe Sicht und dieselbe Bündelung wie bei [listViewDependencies] —
     * ein Rumpf zieht Kanten auf Tabellen und Sichten so gut wie eine
     * `SELECT`-Definition. Der Schluessel traegt die Objektart mit, weil
     * Trigger in Oracle einen eigenen Namensraum haben: eine Funktion und ein
     * Trigger duerfen denselben Namen fuehren.
     */
    fun listRoutineDependencies(
        session: JdbcOperations,
        schema: String,
    ): Map<Pair<String, String>, ViewDependencyRow> =
        dependenciesOf(session, schema, ROUTINE_DEPENDENCY_TYPES, keyOf = { it.string("type") to it.string("name") })

    private fun dependenciesOf(
        session: JdbcOperations,
        schema: String,
        types: List<String>,
    ): Map<String, ViewDependencyRow> = dependenciesOf(session, schema, types, keyOf = { it.string("name") })

    /**
     * Die Objektarten stehen im SQL-Text, nicht an Bind-Platzhaltern: sie sind
     * Konstanten dieser Datei, und eine variable Zahl von Platzhaltern machte
     * aus einer Abfrage mit einem Parameter je nach Aufrufer eine mit zwei
     * oder vier.
     */
    private fun <K> dependenciesOf(
        session: JdbcOperations,
        schema: String,
        types: List<String>,
        keyOf: (Map<String, Any?>) -> K,
    ): Map<K, ViewDependencyRow> {
        val typeList = types.joinToString(", ") { "'$it'" }
        val rows = session.queryList(
            """
            SELECT name, type, referenced_owner, referenced_name, referenced_type
            FROM all_dependencies
            WHERE owner = ? AND type IN ($typeList)
            ORDER BY name, referenced_name
            """.trimIndent(),
            schema,
        )
        return rows.groupBy(keyOf).mapValues { (_, viewRows) ->
            val inSchema = viewRows.filter { it.stringOrNull("referenced_owner") == schema }
            val tables = inSchema.filter { it.stringOrNull("referenced_type") == "TABLE" }
                .map { it.string("referenced_name") }
                .distinct()
            val views = inSchema.filter { it.stringOrNull("referenced_type") == "VIEW" }
                .map { it.string("referenced_name") }
                .distinct()
            ViewDependencyRow(
                tables = tables,
                views = views,
                unmappedInSchema = inSchema.size -
                    inSchema.count { it.stringOrNull("referenced_type") in MAPPED_REFERENCED_TYPES },
            )
        }
    }

    /**
     * PL/SQL-Packages im Schema.
     *
     * Sie bleiben ungelesen, weil das neutrale Modell Routinen einzeln fuehrt
     * und keine Gruppierung dafuer hat; freistehende Funktionen, Prozeduren
     * und Trigger liest dagegen [OracleRoutineReader].
     */
    fun listUnreadPackages(session: JdbcOperations, schema: String): List<String> =
        session.queryList(
            """
            SELECT object_name
            FROM all_objects
            WHERE owner = ? AND object_type = 'PACKAGE'
            ORDER BY object_name
            """.trimIndent(),
            schema,
        ).map { row -> row.string("object_name") }

    /** Identity-Spalten der Tabelle (Datenpfad: ALWAYS/BY-DEFAULT-Toggle, Reseed). */
    fun identityColumns(session: JdbcOperations, schema: String, table: String): List<IdentityColumnRow> =
        session.queryList(
            """
            SELECT ic.column_name, ic.generation_type, ic.sequence_name, s.increment_by
            FROM all_tab_identity_cols ic
            JOIN all_sequences s
                ON s.sequence_owner = ic.owner AND s.sequence_name = ic.sequence_name
            WHERE ic.owner = ? AND ic.table_name = ?
            """.trimIndent(),
            schema,
            table,
        ).map { row ->
            IdentityColumnRow(
                column = row.string("column_name"),
                generation = row.string("generation_type"),
                sequenceName = row.string("sequence_name"),
                increment = row.long("increment_by") ?: 1L,
            )
        }

    /** Virtuelle (`GENERATED ALWAYS AS (...) VIRTUAL`) Spalten -- der Import darf sie nicht schreiben. */
    fun virtualColumns(session: JdbcOperations, schema: String, table: String): Set<String> =
        session.queryList(
            """
            SELECT column_name
            FROM all_tab_cols
            WHERE owner = ? AND table_name = ? AND virtual_column = 'YES'
            """.trimIndent(),
            schema,
            table,
        ).mapNotNullTo(mutableSetOf()) { it["column_name"] as? String }

    /**
     * Die Tabellen-DDL, wie `DBMS_METADATA` sie erzeugt — oder `null`, wenn das
     * Paket nicht ausfuehrbar ist.
     *
     * **Warum ueberhaupt DDL, wenn es einen Katalog gibt.** Fuer eine
     * **materialisiert** berechnete Spalte fuehrt der Katalog nichts, was sie
     * von einer gewoehnlichen Spalte mit `DEFAULT` unterscheidet — gemessen:
     * `VIRTUAL_COLUMN = 'NO'`, `USER_GENERATED = 'YES'`, Ausdruck in
     * `DATA_DEFAULT`, genau wie bei einem Default. Das Wort `MATERIALIZED`
     * steht nur in der DDL. Gebraucht wird daraus allein die Einordnung; den
     * Ausdruck liefert weiter `DATA_DEFAULT` ([OracleGeneratedColumnScanner]).
     *
     * **Warum der Fehlschlag nichts kostet.** `EXECUTE`-Recht auf
     * `DBMS_METADATA` hat nicht jeder Leser. Ohne die DDL bleiben virtuelle
     * Spalten vollstaendig (sie stehen im Katalog); nur die materialisierten
     * sind dann nicht als solche erkennbar, und **das** meldet der Leser statt
     * es zu raten.
     *
     * Der Locator wird in der Abfrage aufgeloest (`TO_CLOB` auf `VARCHAR2` ginge
     * nicht — die DDL sprengt 4000 Zeichen), deshalb `getSubString` hier und
     * nicht `row.string(...)`.
     */
    fun tableDdl(session: JdbcOperations, schema: String, table: String): String? =
        runCatching {
            session.querySingle(
                "SELECT DBMS_METADATA.GET_DDL('TABLE', ?, ?) AS ddl FROM dual",
                table,
                schema,
            )?.get("ddl")
        }.getOrNull()?.let { value ->
            when (value) {
                is Clob -> runCatching { value.getSubString(1, value.length().toInt()) }
                    .also { runCatching { value.free() } }
                    .getOrNull()
                is String -> value
                else -> null
            }
        }

    /** `MAX(<column>)` der Tabelle; `null` bei leerer Tabelle. */
    fun maxValue(session: JdbcOperations, quotedTable: String, column: String): Long? =
        (
            session.querySingle(
                "SELECT MAX(${OracleIdentifiers.quote(column)}) AS max_value FROM $quotedTable",
            )?.get("max_value") as? Number
            )?.toLong()


    /** Die `referenced_type`-Werte, die im neutralen Modell eine Entsprechung haben. */
    private val MAPPED_REFERENCED_TYPES = setOf("TABLE", "VIEW")

    /** Die `ALL_DEPENDENCIES.TYPE`-Werte, die der Routinen-Reverse bündelt. */
    private val ROUTINE_DEPENDENCY_TYPES = listOf("FUNCTION", "PROCEDURE", "TRIGGER")


    /** Oracles Default-Untergrenze einer aufsteigenden Sequenz. */
    private const val ASCENDING_DEFAULT_MIN = 1L

    /** Oracles Default-Obergrenze einer absteigenden Sequenz. */
    private const val DESCENDING_DEFAULT_MAX = -1L

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    /**
     * Eine Sequenzgrenze aus `ALL_SEQUENCES` — oder `null`, wenn sie
     * „unbegrenzt" bedeutet.
     *
     * Oracle materialisiert `NOMINVALUE`/`NOMAXVALUE` als konkrete Zahlen
     * ausserhalb des `Long`-Bereichs; gemessen am Server (23c):
     *
     * ```
     * aufsteigend:  min = 1                            max = 9999999999999999999999999999
     * absteigend:   min = -999999999999999999999999999 max = -1
     * ```
     *
     * Der Treiber liefert sie als `BigDecimal`, und `toLong()` verkuerzt
     * ausserhalb des Bereichs **still auf die unteren 64 Bit** — aus einer
     * unbegrenzten Sequenz wurde so eine bei rund 4,5 Trillionen begrenzte,
     * ohne Fehler und ohne Notiz. Was nicht in ein `Long` passt, ist deshalb
     * `null`: unbegrenzt.
     */
    private fun Map<String, Any?>.sequenceBound(key: String): Long? {
        val exact = when (val value = this[key]) {
            null -> return null
            is java.math.BigDecimal -> value
            is java.math.BigInteger -> java.math.BigDecimal(value)
            is Number -> java.math.BigDecimal.valueOf(value.toLong())
            else -> return null
        }
        return runCatching { exact.longValueExact() }.getOrNull()
    }

    /**
     * Release-Nummer des Servers aus `product_component_version`.
     *
     * Gelesen wird `version` (`23.0.0.0.0`) und nicht das genauere
     * `version_full` (`23.26.3.0.0`): entschieden wird an der ersten Stelle,
     * die in beiden dieselbe ist — und `version_full` gibt es erst ab 18c,
     * also gerade auf den alten Bestaenden nicht, wegen derer ueberhaupt
     * unterschieden wird.
     */
    fun readServerVersion(session: JdbcOperations): OracleServerVersion? {
        val row = session.querySingle(
            """
            SELECT version
            FROM product_component_version
            WHERE ROWNUM = 1
            """.trimIndent(),
        ) ?: return null
        val raw = row["version"] as? String ?: return null
        return OracleServerVersion.parse(raw)
    }
}
