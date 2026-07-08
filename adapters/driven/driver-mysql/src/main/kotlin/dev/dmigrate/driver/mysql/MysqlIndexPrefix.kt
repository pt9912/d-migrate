package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType

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
}
