package dev.dmigrate.core.diff

import dev.dmigrate.core.model.*

/**
 * Compares two [TableDefinition]s and produces a [TableDiff].
 *
 * Handles column comparison with single-column UNIQUE/FK normalization,
 * constraint diffing (single-column + multi-column), index diffing,
 * and primary key / metadata changes.
 *
 * Extracted from [SchemaComparator] to isolate the most complex
 * comparison logic (~320 LOC).
 */
internal class TableComparator(
    /**
     * Ziel-bewusster Modus: der Vergleich unterdrueckt Unterschiede, die der
     * ZIEL-Dialekt nicht ausdruecken kann — Spaltentypen, die auf denselben
     * deklarierten Typ falten, PK-impliziertes `required`, der
     * implizit-`identifier`-PK, dazu Index-Eigenschaften und die
     * Erzeugungsart ueber [TargetProjection]. Dieselben Projektionen, die
     * auch in den Fingerabdruck eingehen, damit der Migrationsplan
     * KONVERGIERT: ein zweiter Lauf gegen ein frisch migriertes Ziel plant
     * null Operationen statt eines ewigen No-Op-Rebuilds.
     *
     * `schema compare` bleibt strikt (null).
     */
    private val targetProjection: TargetProjection? = null,
) {

    fun compareTables(left: SchemaDefinition, right: SchemaDefinition): TableDiffs {
        val leftNames = left.tables.keys
        val rightNames = right.tables.keys

        val added = (rightNames - leftNames).sorted().map {
            NamedTable(it, right.tables.getValue(it))
        }
        val removed = (leftNames - rightNames).sorted().map {
            NamedTable(it, left.tables.getValue(it))
        }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareTable(name, left.tables.getValue(name), right.tables.getValue(name))
        }

        return TableDiffs(added, removed, changed)
    }

    internal fun compareTable(
        name: String,
        left: TableDefinition,
        right: TableDefinition,
    ): TableDiff? {
        val leftNorm = normalizeConstraints(left)
        val rightNorm = normalizeConstraints(right)

        // Im ziel-bewussten Modus zählt der EFFEKTIVE PK (v3-Regel) für
        // PK-Vergleich und PK-implizites required — sonst leere Sets (strikt).
        val leftPk = if (targetProjection != null) EffectivePrimaryKey.of(left).toSet() else emptySet()
        val rightPk = if (targetProjection != null) EffectivePrimaryKey.of(right).toSet() else emptySet()
        val absorbedColumns = AbsorbedColumns(
            uniqueLeft = leftNorm.singleColumnUnique,
            uniqueRight = rightNorm.singleColumnUnique,
            fkLeft = leftNorm.singleColumnForeignKeys.keys,
            fkRight = rightNorm.singleColumnForeignKeys.keys,
            pkLeft = leftPk,
            pkRight = rightPk,
        )

        val columnDiffs = compareColumns(left, right, absorbedColumns)
        val pkDiff = when {
            targetProjection != null && EffectivePrimaryKey.of(left) == EffectivePrimaryKey.of(right) -> null
            left.primaryKey == right.primaryKey -> null
            else -> ValueChange(left.primaryKey, right.primaryKey)
        }

        val indexDiffs = compareIndices(left.indices, right.indices)
        val constraintDiffs = compareConstraints(leftNorm, rightNorm)
        val metadataDiff = if (left.metadata == right.metadata) null
            else ValueChange(left.metadata, right.metadata)
        val partitioningDiff = comparePartitioning(left.partitioning, right.partitioning)

        val diff = TableDiff(
            name = name,
            columnsAdded = columnDiffs.added,
            columnsRemoved = columnDiffs.removed,
            columnsChanged = columnDiffs.changed,
            primaryKey = pkDiff,
            indicesAdded = indexDiffs.added,
            indicesRemoved = indexDiffs.removed,
            indicesChanged = indexDiffs.changed,
            constraintsAdded = constraintDiffs.added,
            constraintsRemoved = constraintDiffs.removed,
            constraintsChanged = constraintDiffs.changed,
            metadata = metadataDiff,
            partitioning = partitioningDiff,
        )

        return if (diff.hasChanges()) diff else null
    }

    // ── Partitioning (AP4, ADR 0019) ──────────────

    /**
     * Compares the partitioning of two tables. Strategy and key (ordered — the
     * partition-key column order is semantic) must be equal, and the **child
     * partitions as a set** (order-independent: the reverse reader emits them by
     * `relname`, the generator by list position). Set equality relies on the
     * *single canonical* bound encoding (AP1/AP1a) — otherwise false-positive diffs.
     *
     * Within each partition the **child-local indices are likewise order-
     * independent** (AP2a): they are canonicalised by [indexKey] before the set
     * comparison, so a reordered-but-equal index set is not a diff. This matches
     * the order-independent top-level index comparison and the sorted fingerprint
     * projection — without it the comparator and the post-`--execute` drift check
     * would disagree on a reordered index set.
     *
     * One step ahead of the set is order-*dependent*, and deliberately so: a
     * missing lower bound is derived from the preceding partition's upper one
     * ([PartitionBoundNormalizer]), because that is the only place the
     * information exists — MySQL states a RANGE partition by its upper bound
     * alone. A list whose partitions are out of ascending order therefore
     * derives different bounds; MySQL rejects such a definition anyway, and a
     * partition that states its lower bound is never touched.
     */
    private fun comparePartitioning(
        left: PartitionConfig?,
        right: PartitionConfig?,
    ): ValueChange<PartitionConfig?>? {
        val equivalent = when {
            left == null && right == null -> true
            left == null || right == null -> false
            else -> left.type == right.type &&
                left.key == right.key &&
                canonicalPartitions(left) == canonicalPartitions(right)
        }
        return if (equivalent) null else ValueChange(left, right)
    }

    /**
     * Partitions as a set, each with its child-local indices in canonical order
     * and its lower bound derived where the source left it out
     * ([PartitionBoundNormalizer]) — MySQL describes a RANGE partition by its
     * upper bound alone, its reverse reader computes the lower one, and without
     * the equalisation the same table compares unequal to itself.
     */
    private fun canonicalPartitions(config: PartitionConfig): Set<PartitionDefinition> =
        PartitionBoundNormalizer.withDerivedLowerBounds(projectPartitioning(config)).partitions.map { partition ->
            // ADR 0025: project child-local indices too (drop the generate-only FULLTEXT
            // hints) so a partition's FULLTEXT index does not phantom-diff authored-vs-reversed
            // — same exclusion the table-level compareIndices applies.
            partition.copy(indices = partition.indices.map { projectIndex(it) }.sortedBy { indexKey(it) })
        }.toSet()

    // ── Columns ───────────────────────────────────

    private data class AbsorbedColumns(
        val uniqueLeft: Set<String>, val uniqueRight: Set<String>,
        val fkLeft: Set<String>, val fkRight: Set<String>,
        /** Effektive PK-Spalten je Seite (leer im strikten Modus). */
        val pkLeft: Set<String> = emptySet(), val pkRight: Set<String> = emptySet(),
    )

    private data class ColumnDiffs(
        val added: Map<String, ColumnDefinition>,
        val removed: Map<String, ColumnDefinition>,
        val changed: List<ColumnDiff>,
    )

    private fun compareColumns(
        left: TableDefinition, right: TableDefinition, absorbed: AbsorbedColumns,
    ): ColumnDiffs {
        val leftNames = left.columns.keys
        val rightNames = right.columns.keys
        val added = (rightNames - leftNames).sorted()
            .associateWith { projectColumn(right.columns.getValue(it)) }
        val removed = (leftNames - rightNames).sorted()
            .associateWith { projectColumn(left.columns.getValue(it)) }
        val changed = (leftNames intersect rightNames).sorted().mapNotNull { name ->
            compareColumn(name, left.columns.getValue(name), right.columns.getValue(name), absorbed)
        }
        return ColumnDiffs(added, removed, changed)
    }

    private fun compareColumn(
        name: String, left: ColumnDefinition, right: ColumnDefinition, absorbed: AbsorbedColumns,
    ): ColumnDiff? {
        val canon = targetProjection?.type
        // Typen, die der Ziel-Dialekt auf denselben deklarierten Typ faltet,
        // sind dort keine ausdrückbare Änderung — ein geplanter Alter wäre ein
        // ewiger No-Op-Rebuild (Post-Compare wäre per v7 clean).
        val typeDiff = if (canon != null && canon(left.type) == canon(right.type)) null
            else diffValueChangeOrNull(left.type, right.type)
        // PK ⇒ NOT NULL — required vergleicht im ziel-bewussten Modus effektiv.
        val requiredDiff = if (canon != null && effectiveRequiredEqual(name, left, right, absorbed)) null
            else diffValueChangeOrNull(left.required, right.required)
        val defaultDiff = if (left.default == right.default) null
            else ValueChange(left.default, right.default)
        val uniqueAbsorbed = name in absorbed.uniqueLeft || name in absorbed.uniqueRight
        val uniqueDiff = if (uniqueAbsorbed) null else diffValueChangeOrNull(left.unique, right.unique)
        val fkAbsorbed = name in absorbed.fkLeft || name in absorbed.fkRight
        val refDiff = if (fkAbsorbed) null
            else if (left.references == right.references) null
            else ValueChange(left.references, right.references)
        val generationDiff = when {
            projectGeneration(left.generation) == projectGeneration(right.generation) -> null
            identitySpelledDifferently(left, right) -> null
            else -> ValueChange(left.generation, right.generation)
        }
        if (hasNoColumnDiff(typeDiff, requiredDiff, defaultDiff, uniqueDiff, refDiff, generationDiff)) return null
        return ColumnDiff(
            name = name,
            type = typeDiff,
            required = requiredDiff,
            default = defaultDiff,
            unique = uniqueDiff,
            references = refDiff,
            generation = generationDiff,
        )
    }

    /** `required` unter Einrechnung der effektiven PK-Mitgliedschaft je Seite. */
    private fun effectiveRequiredEqual(
        name: String, left: ColumnDefinition, right: ColumnDefinition, absorbed: AbsorbedColumns,
    ): Boolean {
        val leftEffective = left.required || name in absorbed.pkLeft
        val rightEffective = right.required || name in absorbed.pkRight
        return leftEffective == rightEffective
    }

    private fun hasNoColumnDiff(vararg diffs: Any?): Boolean =
        diffs.all { it == null }

    private fun projectColumn(col: ColumnDefinition): ColumnDefinition =
        col.copy(unique = false, references = null)

    // ── Constraint Normalization ──────────────────

    private data class NormalizedConstraints(
        val singleColumnUnique: Set<String>,
        val singleColumnForeignKeys: Map<String, ForeignKeySignature>,
        val multiColumnConstraints: Map<String, ConstraintDefinition>,
        /**
         * Der Katalog- bzw. Deklarationsname eines einspaltigen Constraints,
         * sofern die Seite ihn traegt — er kommt aus einem **benannten**
         * Tabellen-Constraint.
         *
         * Ein `DropConstraint` braucht ihn: kein Dialekt ausser Oracle kennt
         * eine Form, die einen Constraint ueber seine Spalte statt ueber
         * seinen Namen abbaut. Ohne ihn erfand der Vergleich einen Namen
         * (`_unique_<spalte>`), und das gerenderte `ALTER TABLE … DROP
         * CONSTRAINT` traf an keiner echten Datenbank etwas.
         *
         * Leer bleibt er, wo die Seite den Constraint als Spalteneigenschaft
         * fuehrt (`column.unique`, `column.references`) — dort gibt es im
         * Modell keinen Namen. Was daraus folgt, steht in
         * `docs/planning/open/single-column-constraint-synthetic-name.md`.
         */
        val singleColumnNames: Map<SingleColumnKey, String> = emptyMap(),
    )

    /** Spalte plus Art — ein UNIQUE und ein FK derselben Spalte sind zweierlei. */
    private data class SingleColumnKey(val column: String, val type: ConstraintType)

    private data class ForeignKeySignature(
        val column: String, val refTable: String, val refColumn: String,
        val onDelete: ReferentialAction?, val onUpdate: ReferentialAction?,
    )

    private fun normalizeConstraints(table: TableDefinition): NormalizedConstraints {
        val singleUnique = mutableSetOf<String>()
        val singleFk = mutableMapOf<String, ForeignKeySignature>()
        val multi = mutableMapOf<String, ConstraintDefinition>()
        val names = mutableMapOf<SingleColumnKey, String>()

        for ((colName, col) in table.columns) {
            if (col.unique) {
                singleUnique.add(colName)
                col.uniqueConstraintName?.let { names[SingleColumnKey(colName, ConstraintType.UNIQUE)] = it }
            }
            col.references?.let { ref ->
                singleFk[colName] = ForeignKeySignature(colName, ref.table, ref.column, ref.onDelete, ref.onUpdate)
                ref.constraintName?.let { names[SingleColumnKey(colName, ConstraintType.FOREIGN_KEY)] = it }
            }
        }

        for (constraint in table.constraints) {
            when {
                constraint.type == ConstraintType.UNIQUE && constraint.columns?.size == 1 -> {
                    val colName = constraint.columns.first()
                    singleUnique.add(colName)
                    names[SingleColumnKey(colName, ConstraintType.UNIQUE)] = constraint.name
                }

                constraint.type == ConstraintType.FOREIGN_KEY && constraint.columns?.size == 1 &&
                    constraint.references != null && constraint.references.columns.size == 1 -> {
                    val colName = constraint.columns.first()
                    val sig = ForeignKeySignature(
                        colName, constraint.references.table, constraint.references.columns.first(),
                        constraint.references.onDelete, constraint.references.onUpdate,
                    )
                    val existing = singleFk[colName]
                    if (existing != null && existing != sig) {
                        multi[constraint.name] = constraint
                    } else {
                        singleFk[colName] = sig
                        names[SingleColumnKey(colName, ConstraintType.FOREIGN_KEY)] = constraint.name
                    }
                }

                ConstraintDiffContract.isRawSqlConstraint(constraint) ->
                    multi[constraint.name] = ConstraintDiffContract.comparable(constraint)

                else -> multi[constraint.name] = ConstraintDiffContract.comparable(constraint)
            }
        }

        return NormalizedConstraints(singleUnique, singleFk, multi, names)
    }

    // ── Constraints ──────────────────────────────

    private data class ConstraintDiffResult(
        val added: List<ConstraintDefinition>, val removed: List<ConstraintDefinition>,
        val changed: List<ValueChange<ConstraintDefinition>>,
    )

    private fun compareConstraints(left: NormalizedConstraints, right: NormalizedConstraints): ConstraintDiffResult {
        val added = mutableListOf<ConstraintDefinition>()
        val removed = mutableListOf<ConstraintDefinition>()
        val changed = mutableListOf<ValueChange<ConstraintDefinition>>()

        for (col in (right.singleColumnUnique - left.singleColumnUnique).sorted())
            added.add(syntheticUniqueConstraint(col, right))
        // Beim Entfernen zaehlt der Name der LINKEN Seite: sie beschreibt, was
        // in der Datenbank steht, und genau das wird abgebaut.
        for (col in (left.singleColumnUnique - right.singleColumnUnique).sorted())
            removed.add(syntheticUniqueConstraint(col, left))
        // Beide Seiten fuehren den Constraint, nennen ihn aber verschieden.
        // Umbenennen ist Abbau und Aufbau: kein Dialekt kennt eine portable
        // Form, die einen Constraint umbenennt, und der Name ist Teil des
        // Vertrags — Anwendungen lesen ihn in der Fehlerbehandlung.
        for (col in (left.singleColumnUnique intersect right.singleColumnUnique).sorted()) {
            if (renamedConstraint(left, right, col, ConstraintType.UNIQUE)) {
                removed.add(syntheticUniqueConstraint(col, left))
                added.add(syntheticUniqueConstraint(col, right))
            }
        }

        val fkLeftCols = left.singleColumnForeignKeys.keys
        val fkRightCols = right.singleColumnForeignKeys.keys
        for (col in (fkRightCols - fkLeftCols).sorted())
            added.add(syntheticFkConstraint(right.singleColumnForeignKeys.getValue(col), right))
        for (col in (fkLeftCols - fkRightCols).sorted())
            removed.add(syntheticFkConstraint(left.singleColumnForeignKeys.getValue(col), left))
        for (col in (fkLeftCols intersect fkRightCols).sorted()) {
            val l = left.singleColumnForeignKeys.getValue(col)
            val r = right.singleColumnForeignKeys.getValue(col)
            if (l != r) changed.add(ValueChange(syntheticFkConstraint(l, left), syntheticFkConstraint(r, right)))
        }

        val multiLeftNames = left.multiColumnConstraints.keys
        val multiRightNames = right.multiColumnConstraints.keys
        for (name in (multiRightNames - multiLeftNames).sorted())
            added.add(right.multiColumnConstraints.getValue(name))
        for (name in (multiLeftNames - multiRightNames).sorted())
            removed.add(left.multiColumnConstraints.getValue(name))
        for (name in (multiLeftNames intersect multiRightNames).sorted()) {
            val l = left.multiColumnConstraints.getValue(name)
            val r = right.multiColumnConstraints.getValue(name)
            if (l != r) changed.add(ValueChange(l, r))
        }

        return ConstraintDiffResult(added, removed, changed)
    }

    /**
     * Ob beide Seiten denselben Constraint verschieden **nennen**.
     *
     * Nur wo beide einen Namen tragen: ein handgeschriebenes `unique: true`
     * nennt keinen, und dann erfuellt jeder Name die Zusicherung — eine
     * Aenderung daraus zu machen hiesse, dem Autor einen Namen zu
     * unterstellen, den er nicht genannt hat.
     *
     * Und nur wo der Zieldialekt Namen ueberhaupt fuehrt: wo sein Reverse sie
     * synthetisiert, verglichen sich zwei Erfindungen
     * ([TargetProjection.constraintName]).
     */
    private fun renamedConstraint(
        left: NormalizedConstraints,
        right: NormalizedConstraints,
        column: String,
        type: ConstraintType,
    ): Boolean {
        val project = targetProjection?.constraintName ?: { it }
        val l = project(left.singleColumnNames[SingleColumnKey(column, type)])
        val r = project(right.singleColumnNames[SingleColumnKey(column, type)])
        return l != null && r != null && l != r
    }

    /**
     * Der Name, unter dem die Seite den Constraint fuehrt — oder ein
     * gebildeter, wenn sie ihn als Spalteneigenschaft fuehrt und im Modell
     * keiner steht.
     */
    private fun nameFor(
        side: NormalizedConstraints,
        column: String,
        type: ConstraintType,
        fallback: String,
    ): String = side.singleColumnNames[SingleColumnKey(column, type)] ?: fallback

    /**
     * Ob beide Seiten dieselbe Autowert-Spalte meinen und sie nur verschieden
     * hinschreiben: einmal `identifier` + `auto_increment`, einmal der
     * numerische Typ mit `generation: identity`.
     *
     * Der Reverse liefert immer die zweite Form, ein handgeschriebenes Soll
     * meist die erste. Wo der Dialekt beide zum selben DDL rendert
     * ([TargetProjection.foldsAutoIncrementOntoIdentity]), ist ihr Unterschied
     * keine ausdrueckbare Aenderung — geplant wuerde sonst bei jedem Lauf
     * dieselbe Aenderung an einer unveraenderten Spalte, und auf Oracle
     * endete sie mit einem Blocker, weil sich eine Spalte dort nicht
     * nachtraeglich zur Identity-Spalte machen laesst.
     *
     * Der **Modus** bleibt vergleichbar: die `auto_increment`-Schreibweise
     * nennt keinen, also gibt es nichts zu vergleichen. Nennen ihn beide
     * Seiten, faellt dieser Zweig gar nicht erst an.
     */
    private fun identitySpelledDifferently(left: ColumnDefinition, right: ColumnDefinition): Boolean {
        if (targetProjection?.foldsAutoIncrementOntoIdentity != true) return false
        return autoIncrementOnly(left) && identityOnly(right) ||
            identityOnly(left) && autoIncrementOnly(right)
    }

    private fun autoIncrementOnly(column: ColumnDefinition): Boolean =
        column.generation == null && (column.type as? NeutralType.Identifier)?.autoIncrement == true

    private fun identityOnly(column: ColumnDefinition): Boolean =
        column.generation is ColumnGeneration.Identity &&
            (column.type as? NeutralType.Identifier)?.autoIncrement != true

    private fun syntheticUniqueConstraint(column: String, side: NormalizedConstraints) = ConstraintDefinition(
        name = nameFor(side, column, ConstraintType.UNIQUE, "_unique_$column"),
        type = ConstraintType.UNIQUE,
        columns = listOf(column),
    )

    private fun syntheticFkConstraint(sig: ForeignKeySignature, side: NormalizedConstraints) = ConstraintDefinition(
        name = nameFor(side, sig.column, ConstraintType.FOREIGN_KEY, "_fk_${sig.column}"),
        type = ConstraintType.FOREIGN_KEY,
        columns = listOf(sig.column),
        references = ConstraintReferenceDefinition(sig.refTable, listOf(sig.refColumn), sig.onDelete, sig.onUpdate),
    )

    // ── Indices ──────────────────────────────────

    private data class IndexDiffResult(
        val added: List<IndexDefinition>, val removed: List<IndexDefinition>,
        val changed: List<ValueChange<IndexDefinition>>,
    )

    private fun compareIndices(left: List<IndexDefinition>, right: List<IndexDefinition>): IndexDiffResult {
        val leftByKey = byProjectedKey(left)
        val rightByKey = byProjectedKey(right)
        val leftKeys = leftByKey.keys
        val rightKeys = rightByKey.keys
        val added = (rightKeys - leftKeys).sorted().map { rightByKey.getValue(it) }
        val removed = (leftKeys - rightKeys).sorted().map { leftByKey.getValue(it) }
        val changed = (leftKeys intersect rightKeys).sorted().mapNotNull { key ->
            val l = leftByKey.getValue(key); val r = rightByKey.getValue(key)
            if (projectIndex(l) == projectIndex(r)) null else ValueChange(l, r)
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
    private fun projectIndex(index: IndexDefinition): IndexDefinition {
        // Erst die Faehigkeits-Projektion des Ziels (sie kann den Indextyp
        // falten und den synthetisierten Volltext-Namen nullen), dann die
        // dialekt-unabhaengige Hinweis-Denylist unten -- sonst pruefte deren
        // FULLTEXT-Bedingung einen Typ, den das Ziel gar nicht ablegt.
        val projected = targetProjection?.index?.invoke(index) ?: index
        return if (projected.type != IndexType.FULLTEXT) projected
        else projected.copy(fullTextVectorColumn = null, fullTextAccessMethod = null)
    }

    /**
     * Die Partitionierung durch die Ziel-Projektion -- vor der Ableitung der
     * unteren Grenzen, damit die Ableitung auf den bereits gefalteten
     * Grenzliteralen arbeitet.
     */
    private fun projectPartitioning(config: PartitionConfig): PartitionConfig =
        targetProjection?.partitioning?.invoke(config) ?: config

    /**
     * Die Erzeugungsart durch die Ziel-Projektion.
     *
     * Bewusst kein `?:`-Fallback auf den Eingabewert: die Projektion **darf**
     * `null` liefern (sie blendet etwa den system-vergebenen Sequenznamen
     * aus), und ein Elvis machte daraus wieder den unprojizierten Wert.
     */
    private fun projectGeneration(generation: ColumnGeneration?): ColumnGeneration? {
        val project = targetProjection?.generation ?: return generation
        return project(generation)
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

    private fun indexKey(index: IndexDefinition): String =
        index.name ?: "idx:${index.columns.joinToString(",")}:${index.type}:${index.unique}:${index.where.orEmpty()}"
}

internal data class TableDiffs(
    val added: List<NamedTable>,
    val removed: List<NamedTable>,
    val changed: List<TableDiff>,
)
