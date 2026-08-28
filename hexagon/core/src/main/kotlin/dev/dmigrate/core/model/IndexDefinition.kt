package dev.dmigrate.core.model

data class IndexColumn(
    val name: String,
    val direction: IndexSortDirection? = null,
    /**
     * MySQL prefix-index key length (`col(n)`), e.g. for indexing the first `n`
     * characters of a TEXT/BLOB column. Null = index the full column. PG/SQLite
     * have no prefix-index concept and drop it (with a note) on generate.
     */
    val prefixLength: Int? = null,
) {
    override fun toString(): String = buildString {
        append(name)
        if (prefixLength != null) append("($prefixLength)")
        if (direction != null) append(" ${direction.name}")
    }
}

enum class IndexSortDirection {
    ASC, DESC
}

data class IndexDefinition(
    val name: String? = null,
    val columns: List<IndexColumn>,
    val type: IndexType = IndexType.BTREE,
    val unique: Boolean = false,
    val where: String? = null,
    /**
     * Text-Search-Konfiguration eines [IndexType.FULLTEXT]-Index (z. B. `english`),
     * beim PG-Reverse aus dem `tsvector`-Trigger / der Generated-Expression abgeleitet
     * (ADR 0025). Null für alle anderen Indextypen.
     */
    val textSearchConfig: String? = null,
    /**
     * Backing-`tsvector`-Spalte eines [IndexType.FULLTEXT]-Index (ADR 0025). [columns]
     * trägt die *Quelltext*-Spalten (MySQL `FULLTEXT` / SQLite FTS5 indizieren diese
     * direkt); PostgreSQL materialisiert stattdessen einen vorberechneten `tsvector` in
     * **dieser** Spalte und legt den GiST/GIN-Index darüber. Beim PG-Reverse aus dem
     * `tsvector_update_trigger` gefüllt, damit PG-Generate/-Diff den Index ohne Raten
     * (auch bei mehreren `tsvector`-Spalten je Tabelle) auf die richtige Vektorspalte
     * rekonstruiert. Null für alle anderen Indextypen / hand-authored ohne Vektorspalte.
     */
    val fullTextVectorColumn: String? = null,
    /**
     * PostgreSQL-Zugriffsmethode eines [IndexType.FULLTEXT]-Index — `GIN` oder `GIST`
     * (ADR 0025). PostgreSQL unterscheidet beide bewusst (Performance/Größe/Planner), daher
     * wird die Original-Methode beim Reverse erfasst und beim Generate exakt rekonstruiert
     * (GIN→GIN, GiST→GiST); fehlt sie (hand-authored), gilt GiST als Default. Wie
     * [fullTextVectorColumn] ein **Generate-only**-Hinweis: getragen fürs Generate, aber aus
     * der Vergleichs-Semantik (Comparator/Fingerprint/CanonicalPayload) ausgeschlossen, weil
     * er die Volltext-*Fähigkeit* nicht verändert. Null für alle anderen Indextypen.
     */
    val fullTextAccessMethod: IndexType? = null,
    /**
     * Nicht-Schlüsselspalten eines abdeckenden Index (`INCLUDE (…)`). Sie stehen nur
     * auf der Blattebene, gehen nicht in die Sortierung ein und zählen bei einem
     * `unique`-Index nicht zur Eindeutigkeit — ein Index über `(a)` mit `INCLUDE (b)`
     * ist etwas anderes als einer über `(a, b)`. PostgreSQL (ab 11) und SQL Server
     * tragen sie nativ; MySQL und SQLite kennen sie nicht und lassen sie beim
     * Generate mit einer Warnung fallen (nicht etwa an die Schlüsselspalten
     * angehängt — das änderte bei `unique` die Semantik).
     */
    val includeColumns: List<String> = emptyList(),
    /**
     * Ob dieser Index die Ablage der Tabelle bildet (`CREATE CLUSTERED INDEX`).
     * Nur SQL Server steuert das explizit; es gibt höchstens einen solchen Index
     * je Tabelle, und ohne Angabe bekommt ihn der Primärschlüssel. Steht das Feld
     * an einem Index, ist der Primärschlüssel derselben Tabelle folglich
     * nonclustered — der Generate-Pfad leitet das her, statt es im Modell zu
     * doppeln. MySQL (InnoDB, immer am PK) und SQLite (`rowid`) haben keine
     * Steuerung; PostgreSQL kennt nur `CLUSTER` als einmalige Reorganisation,
     * nicht als Eigenschaft.
     */
    val clustered: Boolean = false,
) {
    val columnNames: List<String>
        get() = columns.map { it.name }
}

/**
 * ADR 0025: whether this index is a spatial index over a geometry column — true when a
 * **non-FULLTEXT** index touches a [NeutralType.Geometry] column. FULLTEXT is excluded because
 * it lists its *source* TEXT columns; a geometry-typed source must never route a fulltext index
 * to the dialect spatial path. [columnType] resolves a column name to its neutral type (null =
 * unknown). Single predicate shared by every per-dialect generate + diff geometry router so
 * they cannot diverge (the FULLTEXT guard was previously hand-copied across ~6 sites).
 */
fun IndexDefinition.isSpatialGeometryIndex(columnType: (String) -> NeutralType?): Boolean =
    type != IndexType.FULLTEXT && columnNames.any { columnType(it) is NeutralType.Geometry }

enum class IndexType {
    BTREE, HASH, GIN, GIST, BRIN,

    /**
     * VA3: PostgreSQL/PostGIS SP-GiST-Zugriffsmethode (`USING SPGIST`).
     * SP-GiST = „Space-Partitioned Generalized Search Tree" — generische Indexierung
     * mehrdimensionaler Typen über partitionierte Suchbäume (Quad-Tree, k-d-Tree,
     * Radix-Tree/Trie); Alternative zu [GIST] für homogene/„Spaghetti"-Geometrien.
     * Eigener neutraler Typ (statt Verlust → [BTREE] beim Reverse). Cross-Dialect ohne
     * SP-GiST-Pendant (MySQL/SpatiaLite) wird ein SP-GiST-Geometrie-Index auf den
     * dortigen einzigen räumlichen Indextyp normalisiert.
     */
    SPGIST,

    /**
     * VA3 (Spatial-Slice): neutraler räumlicher Index auf einer Geometriespalte für
     * Dialekte ohne Zugriffsmethoden-Wahl. Generate: MySQL `SPATIAL INDEX`, PostGIS
     * `USING GIST` (Default-Spatial-AM), SpatiaLite (VA4) `CreateSpatialIndex`.
     * Reverse: MySQL `index_type=SPATIAL` liefert ihn direkt; PostGIS modelliert
     * seinen Geometrie-Index methoden-genau als [GIST]/[SPGIST]/[BRIN] — beide
     * Generate-Pfade erkennen den räumlichen Fall zusätzlich spaltenbasiert
     * (`indexTouchesGeometry`/`referencesGeometry`).
     */
    SPATIAL,

    /**
     * Volltext-Index über Quelltext-Spalten (ADR 0025). Generate: MySQL
     * `CREATE FULLTEXT INDEX`, SQLite FTS5-Virtual-Table + Sync-Trigger, PostgreSQL
     * Expansion zu `tsvector`-Spalte + Trigger + GiST-Index. Trägt die optionale
     * Text-Search-Konfiguration über [IndexDefinition.textSearchConfig]; der
     * `tsvector`-Spaltentyp selbst bleibt parameterloser [NeutralType.FullText] (ADR 0015).
     */
    FULLTEXT
}
