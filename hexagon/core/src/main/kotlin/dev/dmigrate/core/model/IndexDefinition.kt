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
) {
    val columnNames: List<String>
        get() = columns.map { it.name }
}

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
