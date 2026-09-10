package dev.dmigrate.core.diff

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType

/**
 * Vergleicht die Indizes zweier Tabellen.
 *
 * Aus [TableComparator] herausgeloest, weil die Index-Identitaet eine eigene
 * Frage ist: unter welchem Schluessel zwei Indizes einander ueberhaupt
 * zugeordnet werden, und welche ihrer Felder dabei zaehlen.
 */
internal class TableIndexComparator(
    private val targetProjection: TargetProjection?,
    private val folding: RawTextFolding,
) {

    data class IndexDiffResult(
        val added: List<IndexDefinition>, val removed: List<IndexDefinition>,
        val changed: List<ValueChange<IndexDefinition>>,
    )

    fun compareIndices(
        tableName: String,
        left: List<IndexDefinition>,
        right: List<IndexDefinition>,
    ): IndexDiffResult {
        val leftByKey = byProjectedKey(left)
        val rightByKey = byProjectedKey(right)
        val leftKeys = leftByKey.keys
        val rightKeys = rightByKey.keys
        val added = (rightKeys - leftKeys).sorted().map { rightByKey.getValue(it) }
        val removed = (leftKeys - rightKeys).sorted().map { leftByKey.getValue(it) }
        val changed = (leftKeys intersect rightKeys).sorted().mapNotNull { key ->
            val l = leftByKey.getValue(key); val r = rightByKey.getValue(key)
            val folded = folding.index(tableName, l, r)
            if (projectIndex(l) == projectIndex(folded)) null else ValueChange(l, r)
        }
        return IndexDiffResult(added, removed, changed)
    }

    /**
     * ADR 0025: a FULLTEXT index's `fullTextVectorColumn` / `fullTextAccessMethod` are
     * generate-only reconstruction hints (which tsvector column PostgreSQL materialises and
     * with which access method) — they do not change the fulltext *capability*. Null them out
     * before equality so an authored index (hint absent) and the reversed live index (hint
     * set) are not reported as changed, mirroring [projectColumn] for non-semantic fields.
     * Guarded on the index type (only FULLTEXT carries the hints) so the field list lives in
     * one place — the `copy` — and a future hint can't slip past a stale guard condition.
     *
     * Contract (keep in sync): the index identity is shared by THREE projections — this
     * denylist (excludes the generate-only hints), `MigrationFingerprint.appendIndex` and
     * `CanonicalPayload.index` (allowlists of the semantic fields). A new *semantic* index
     * field must be added to both allowlists; a new generate-only *hint* must be added to the
     * `copy` here. `SchemaComparatorFullTextHintsTest` pins the hint exclusion across all three.
     */
    fun projectIndex(index: IndexDefinition): IndexDefinition {
        // Erst die Faehigkeits-Projektion des Ziels (sie kann den Indextyp
        // falten und den synthetisierten Volltext-Namen nullen), dann die
        // dialekt-unabhaengige Hinweis-Denylist unten -- sonst pruefte deren
        // FULLTEXT-Bedingung einen Typ, den das Ziel gar nicht ablegt.
        val projected = targetProjection?.index?.invoke(index) ?: index
        return if (projected.type != IndexType.FULLTEXT) projected
        else projected.copy(fullTextVectorColumn = null, fullTextAccessMethod = null)
    }

    /**
     * Indizes unter dem Schluessel, den das **Ziel** von ihnen sieht.
     *
     * Der Schluessel entscheidet, ob zwei Indizes ueberhaupt einander
     * zugeordnet werden — vor jedem Feldvergleich. Ihn ungefaltet zu bilden
     * machte die Projektion an genau der Stelle wirkungslos, an der sie am
     * meisten zaehlt: wo ein Dialekt den Namen gar nicht ablegt, synthetisiert
     * sein Reverse einen anderen, die Schluesselmengen waeren disjunkt, und
     * aus einem unveraenderten Index wuerden `DropIndex` + `AddIndex` — bei
     * jedem Lauf erneut, obwohl der Fingerabdruck (der dieselbe Projektion
     * schon nutzt) Ruhe meldet.
     *
     * Fallen zwei Indizes derselben Seite auf denselben gefalteten Schluessel,
     * kann das Ziel sie nicht unterscheiden. Der zweite bleibt dann unter
     * seinem **ungefalteten** Schluessel stehen, statt still aus dem Vergleich
     * zu verschwinden.
     */
    private fun byProjectedKey(indices: List<IndexDefinition>): Map<String, IndexDefinition> {
        val byKey = LinkedHashMap<String, IndexDefinition>()
        for (index in indices) {
            if (byKey.putIfAbsent(indexKey(projectIndex(index)), index) != null) {
                byKey[indexKey(index)] = index
            }
        }
        return byKey
    }

    fun indexKey(index: IndexDefinition): String =
        index.name ?: "idx:${index.columns.joinToString(",")}:${index.type}:${index.unique}:${index.where.orEmpty()}"
}
