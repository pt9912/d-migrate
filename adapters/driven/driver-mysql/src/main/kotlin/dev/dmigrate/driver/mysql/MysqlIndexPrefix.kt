package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * I-08: MySQL prefix-index rules. An unbounded `TEXT`/`BLOB` column cannot be
 * indexed without a key length (`ERROR 1170`). Single source of truth shared by
 * the full-schema generator ([MysqlIndexPartitionDdlHelper]) and the diff path
 * ([MysqlDiffRenderContext]).
 */
internal object MysqlIndexPrefix {

    /**
     * The first indexed column that renders to an unbounded TEXT/BLOB but carries
     * no prefix length, or null when the index is renderable. [columnType]
     * resolves a column name to its neutral type (null when unknown).
     */
    fun columnNeedingPrefix(index: IndexDefinition, columnType: (String) -> NeutralType?): String? {
        // FULLTEXT (and SPATIAL) indexes are their own MySQL index kind and are exempt
        // from the BTREE TEXT/BLOB prefix-length rule — a `CREATE FULLTEXT INDEX` over a
        // TEXT column needs no key length (ADR 0025).
        if (index.type == IndexType.FULLTEXT || index.type == IndexType.SPATIAL) return null
        return index.columns.firstOrNull { col ->
            col.prefixLength == null && needsPrefixLength(columnType(col.name))
        }?.name
    }

    fun needsPrefixLength(type: NeutralType?): Boolean = when (type) {
        is NeutralType.Text -> type.maxLength == null
        is NeutralType.Binary, is NeutralType.Xml -> true
        else -> false
    }

    /**
     * Die Note zu einem uebersprungenen **UNIQUE-Constraint** auf einer
     * unbegrenzten TEXT/BLOB-Spalte. Dieselbe Regel wie beim Index
     * ([columnNeedingPrefix]) und derselbe Code (`W125`) — nur ein anderer
     * Objekttyp, weshalb der Wortlaut den Constraint nennt.
     */
    fun uniquePrefixSkipNote(constraintName: String, column: String): TransformationNote =
        TransformationNote(
            type = NoteType.WARNING,
            code = "W125",
            objectName = constraintName,
            message = "UNIQUE constraint '$constraintName' on TEXT/BLOB column '$column' was skipped: " +
                "MySQL requires a prefix length (e.g. `$column(255)`) which is not present.",
            hint = "Bound the column with a max_length so it becomes key-eligible, " +
                "or enforce uniqueness manually.",
        )
}
