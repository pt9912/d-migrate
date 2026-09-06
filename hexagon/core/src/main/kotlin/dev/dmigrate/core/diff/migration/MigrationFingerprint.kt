package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ConstraintDiffContract
import dev.dmigrate.core.diff.EffectivePrimaryKey
import dev.dmigrate.core.diff.routine.RoutineIdentityNormalizer
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.model.canonicalOrder
import dev.dmigrate.core.util.sha256Hex

/**
 * Canonical fingerprint of a [SchemaDefinition] for the migration
 * pipeline. Used by:
 *
 * - migration reports (`currentFingerprint`, `desiredFingerprint`),
 * - the SQL metadata block in `--rollback-output` artefacts
 *   (`postUpFingerprint`, `allowedPostUpFingerprints`),
 * - the post-`--execute` compare and `schema rollback` drift checks.
 *
 * The fingerprint is a **pure content hash**. Schema-level reporting
 * metadata — `name`, `version`, `description`, `encoding`, `locale` —
 * is *not* part of the projection. None of these are observable
 * database state from the reverse reader's perspective: a live
 * PostgreSQL has no concept of "schema name", and the reader does
 * not surface the server's collation/encoding onto
 * [SchemaDefinition]. Including any of them would mean a desired
 * YAML could never round-trip against a real DB even with identical
 * content. Reverse-marker handling is therefore a non-event for
 * fingerprinting and lives only at compare-/report-level concerns.
 *
 * Algorithm: `schema-fingerprint-v1`. Steps:
 *
 * 1. Build a deterministic string projection of the schema's
 *    content (custom types, tables, views, sequences, routines,
 *    triggers). Maps are emitted in lexicographic key order; lists
 *    keep their declared order (PK column lists, parameter lists,
 *    etc.) — those are semantic.
 * 2. SHA-256 over the UTF-8 bytes of the projection, lowercase hex.
 *
 * The output format includes the [ALGORITHM] string as a leading
 * line so future projection changes ship as a new algorithm name
 * (e.g. `schema-fingerprint-v2`) rather than as a silent invariant
 * shift.
 *
 * Property:  the projection itself is exposed as [project] for
 * Diagnose-Pfade (e.g. golden-file tests, debug reports). Production
 * callers should use [compute].
 */
object MigrationFingerprint {

    /**
     * Algorithm identifier folded into every projection. Bump on contract change.
     *
     * v2: index columns now carry a MySQL prefix length (`IndexColumn.prefixLength`,
     * read from `SUB_PART` on reverse). Projections that include a prefix index
     * differ from v1; the bump signals the reverse-reader semantic change so
     * v1-era rollback artefacts are recognised as a different algorithm version
     * rather than silently mismatched.
     *
     * v3: the primary key is projected as its **effective** value — an implicit,
     * `identifier`-typed PK is canonicalised to the same projection as an explicit
     * `primary_key`. Rationale: `spec/neutral-model-spec.md` 13.1 defines a PK as
     * "explicit OR via the `identifier` type", so a desired schema that omits
     * `primary_key` (PK only implicit via `identifier`) is semantically identical to
     * the reverse, which always materialises `primaryKey = [<col>]`. Without this,
     * `migrate --execute` reported a spurious post-compare drift (Exit 5) on a
     * spec-valid identifier-only schema — dialect-neutral
     * (`docs/planning/done/migrate-postcompare-identifier-pk-drift.md`).
     *
     * v4: table partitioning is now projected (strategy, key, child partitions with
     * their structured bounds). AP4/ADR 0019 makes `SchemaComparator` partition-aware;
     * the fingerprint must follow so the comparator and the post-`--execute` drift
     * check agree — otherwise a partition-only difference would be DIFFERENT to
     * `schema compare` yet identical to the fingerprint. Child partitions are sorted
     * by name (set equality — declaration order is not semantic).
     *
     * v5: child-local partition indices are now projected too (AP2a). The reverse
     * reader captures indices defined directly on a partition (not parent-propagated)
     * and the comparator compares them structurally, so the fingerprint includes them
     * for the same comparator/drift agreement reason as v4.
     *
     * v6: index `textSearchConfig` is now projected (ADR 0025) — it is semantic (changes the
     * text analysis). A FULLTEXT index's backing tsvector column and access method are NOT
     * projected: they are generate-only reconstruction hints (also excluded from
     * `TableComparator.projectIndex`), so a hint-only difference does not read as a change.
     *
     * v7 (postcompare-type-canonicalization slice): four canonicalisations so a
     * per-dialect-lossless round trip hashes identically to its reverse:
     *
     * 1. **Column types** run through an injected `canonicalizeType` projection
     *    (default identity). The migrate/rollback call sites pass the TARGET
     *    dialect's [dev.dmigrate.driver] `NeutralTypeCanonicalizer`, folding types
     *    the dialect flattens onto one declared type (SQLite: `smallint`→`integer`,
     *    `datetime`→`text`, …). Deliberate divergence from `SchemaComparator`:
     *    `schema compare` stays structurally strict (a wanted `smallint→integer`
     *    IS a difference there) — the fingerprint with a canonicaliser answers
     *    "does the TARGET distinguish these?", the comparator answers "does the
     *    MODEL?".
     * 2. **Single-column UNIQUE fold** — a named single-column UNIQUE constraint
     *    projects as the column's `unique` flag (constraint name dropped), exactly
     *    mirroring `TableComparator.normalizeConstraints`.
     * 3. **Single-column FOREIGN-KEY fold** — a named single-column FK constraint
     *    (single-column target) projects as the column's `references` (mirrors the
     *    comparator's `ForeignKeySignature` absorption; live-belegt: authored
     *    column-level `references:` vs. reverse-materialised named constraint
     *    drifted on every dialect). A signature that DIVERGES from an existing
     *    column-level reference stays a distinct constraint.
     * 4. **Effective required** — `required` projects as
     *    `required || column ∈ effectivePrimaryKey` (PK ⇒ NOT NULL; the PG reverse
     *    materialises it, the desired parser does not — same asymmetry family as
     *    the v3 effective PK).
     *
     * v8 (ADR 0048): der **Wertevorrat eines Enums** bekommt eine eigene Sektion, unabhaengig davon, ob
     * er authored als `enum`-Typ an der Spalte steht oder zurueckgelesen als CHECK-
     * Constraint danebensteht. Dialekte, die keinen Enum-Typ kennen, bilden ihn als
     * `col IN (…)` bzw. `col='a' OR col='b'` ab; ohne die Projektion las sich derselbe
     * Wertevorrat je nach Herkunft verschieden.
     *
     * v9: **abdeckende und clustered Indizes.** `includeColumns` und `clustered` sind
     * semantisch — INCLUDE aendert, was der Index traegt, `clustered` die Ablage der
     * Tabelle — und stehen deshalb in derselben Projektion wie `where`. Dialekte, die
     * sie nicht ausdruecken koennen, kanonisieren sie weg, damit ein verlustfreier
     * Round-Trip weiterhin identisch hasht.
     *
     * Plan: `docs/planning/done/postcompare-type-canonicalization-slice.md`.
     */
    const val ALGORITHM: String = "schema-fingerprint-v9"

    /** Field-/key separator inside the canonical projection. Shared with [CanonicalPayload]. */
    private const val SEP: Char = CanonicalEncoding.SEP

    /**
     * SHA-256 hex of the canonical projection. [canonicalizeType] is the target
     * dialect's neutral-type projection (v7); the identity default keeps the
     * fingerprint dialect-neutral for callers without a target-dialect context.
     */
    fun compute(
        schema: SchemaDefinition,
        canonicalizeType: (NeutralType) -> NeutralType = { it },
        canonicalizeIndex: (IndexDefinition) -> IndexDefinition = { it },
        canonicalizeGeneration: (ColumnGeneration?) -> ColumnGeneration? = { it },
    ): String = sha256Hex(project(schema, canonicalizeType, canonicalizeIndex, canonicalizeGeneration))

    /**
     * Returns the canonical projection string. Public for diagnostics.
     *
     * Schema-level metadata (`description`, `encoding`, `locale`) is
     * intentionally **not** projected — same B+ rationale as
     * `name`/`version`: these are reporting fields, not observable
     * database state. A live PostgreSQL or MySQL has its own server-
     * level encoding/collation that the reverse reader does not
     * surface on the [SchemaDefinition], so a YAML that customised
     * those fields would otherwise drift against any real DB target
     * even with identical content. If we ever decide they ARE
     * content, `SchemaComparator.compareMetadata` must learn to diff
     * them too — both code paths must agree on what counts as
     * "schema state".
     */
    fun project(
        schema: SchemaDefinition,
        canonicalizeType: (NeutralType) -> NeutralType = { it },
        canonicalizeIndex: (IndexDefinition) -> IndexDefinition = { it },
        canonicalizeGeneration: (ColumnGeneration?) -> ColumnGeneration? = { it },
    ): String {
        val sb = StringBuilder()
        sb.append("algorithm=").append(ALGORITHM).append('\n')
        appendCustomTypes(sb, schema.customTypes)
        appendTables(sb, canonicalizedTables(schema.tables, canonicalizeIndex, canonicalizeGeneration), canonicalizeType)
        appendViews(sb, schema.views)
        appendSequences(sb, schema.sequences)
        appendFunctions(sb, schema.functions)
        appendProcedures(sb, schema.procedures)
        appendTriggers(sb, schema.triggers)
        return sb.toString()
    }

    // ── Custom types ────────────────────────────────────────────────

    private fun appendCustomTypes(sb: StringBuilder, types: Map<String, CustomTypeDefinition>) {
        sb.append("custom_types[").append(types.size).append("]\n")
        for ((name, def) in types.entries.sortedBy { it.key }) {
            sb.append("custom_type=").append(name)
                .append(SEP).append("kind=").append(def.kind.name)
                .append(SEP).append("base=").append(def.baseType ?: "")
                .append(SEP).append("values=").append(def.values?.let(::joinValues) ?: "")
                .append(SEP).append("precision=").append(def.precision ?: "")
                .append(SEP).append("scale=").append(def.scale ?: "")
                .append(SEP).append("check=").append(def.check ?: "")
                .append('\n')
        }
    }

    // ── Tables ──────────────────────────────────────────────────────

    /**
     * Projiziert jeden Index -- auch die partitionslokalen -- und die
     * Erzeugungsart jeder Spalte durch die Sicht des Ziel-Dialekts, bevor
     * irgendetwas angehaengt wird.
     *
     * Einmal am Eingang statt an jeder Anhaengestelle: die Projektion ist die
     * gleiche fuer Tabellen- und Partitionsindizes, und ein zweiter Lambda durch
     * die Anhaenge-Kette waere eine Naht mehr, an der die beiden auseinanderlaufen
     * koennten. Fuer Dialekte ohne INCLUDE oder ohne clustered-Steuerung faellt das
     * jeweilige Feld hier weg, damit ein verlustfreier Round-Trip identisch hasht.
     */
    private fun canonicalizedTables(
        tables: Map<String, TableDefinition>,
        canonicalizeIndex: (IndexDefinition) -> IndexDefinition,
        canonicalizeGeneration: (ColumnGeneration?) -> ColumnGeneration?,
    ): Map<String, TableDefinition> = tables.mapValues { (_, table) ->
        table.copy(
            columns = table.columns.mapValues { (_, col) ->
                col.copy(generation = canonicalizeGeneration(col.generation))
            },
            indices = table.indices.map(canonicalizeIndex),
            partitioning = table.partitioning?.let { config ->
                config.copy(
                    partitions = config.partitions.map { part ->
                        part.copy(indices = part.indices.map(canonicalizeIndex))
                    },
                )
            },
        )
    }

    private fun appendTables(
        sb: StringBuilder,
        tables: Map<String, TableDefinition>,
        canonicalizeType: (NeutralType) -> NeutralType,
    ) {
        sb.append("tables[").append(tables.size).append("]\n")
        for ((name, table) in tables.entries.sortedBy { it.key }) {
            sb.append("table=").append(name).append('\n')
            val effectivePk = effectivePrimaryKey(table)
            val folded = foldConstraints(table)
            sb.append("  columns[").append(table.columns.size).append("]\n")
            for ((colName, col) in table.columns.entries.sortedBy { it.key }) {
                sb.append("    column=").append(colName)
                    .append(SEP).append("type=").append(neutralType(canonicalizeType(col.type)))
                    // v7: PK ⇒ NOT NULL — required projects as its effective value.
                    .append(SEP).append("required=").append(col.required || colName in effectivePk)
                    // v7: single-column UNIQUE constraints fold onto the column flag.
                    .append(SEP).append("unique=").append(col.unique || colName in folded.uniqueColumns)
                    .append(SEP).append("default=").append(defaultValue(col.default))
                    // v7: single-column FK constraints fold onto the column reference.
                    .append(SEP).append("references=")
                    .append(reference(col.references).ifEmpty { folded.foldedFkByColumn[colName] ?: "" })
                    .append(SEP).append("generation=").append(generation(col.generation))
                    .append('\n')
            }
            sb.append("  primary_key=").append(effectivePk.joinToString(",")).append('\n')
            sb.append("  indices[").append(table.indices.size).append("]\n")
            for (idx in table.indices.sortedWith(indexOrder)) appendIndex(sb, "    ", "index", idx)
            sb.append("  constraints[").append(folded.remaining.size).append("]\n")
            for (c in folded.remaining.sortedBy { it.name }) {
                sb.append("    constraint=").append(c.name)
                    .append(SEP).append("type=").append(c.type.name)
                    .append(SEP).append("columns=").append(c.columns?.joinToString(",") ?: "")
                    .append(SEP).append("expr=").append(c.expression ?: "")
                    .append(SEP).append("ref=")
                    .append(c.references?.let { "${it.table}[${it.columns.joinToString(",")}]" } ?: "")
                    .append('\n')
            }
            // v8: eine eigene Sektion statt einer Zeile im Constraint-Block —
            // sonst haenge die Zaehlung `constraints[n]` davon ab, in welcher
            // Form der Wertevorrat gerade vorliegt.
            sb.append("  enum_checks[").append(folded.enumValuesByColumn.size).append("]\n")
            for ((colName, values) in folded.enumValuesByColumn.entries.sortedBy { it.key }) {
                sb.append("    enum_check=").append(colName)
                    .append(SEP).append("values=").append(joinValues(values))
                    .append('\n')
            }
            appendPartitioning(sb, table.partitioning)
        }
    }

    private data class FoldedConstraints(
        val uniqueColumns: Set<String>,
        /** Column name → projected reference string (same shape as [reference]). */
        val foldedFkByColumn: Map<String, String>,
        /**
         * v8: Spalte → Wertevorrat, egal ob er authored als `enum` an der
         * Spalte steht oder zurueckgelesen als `CHECK (spalte IN (…))`.
         */
        val enumValuesByColumn: Map<String, List<String>>,
        val remaining: List<ConstraintDefinition>,
    )

    /**
     * v7 canonicalisation: mirrors `TableComparator.normalizeConstraints`. The
     * reverse readers materialise single-column UNIQUE/FK as **named
     * constraints**, authored YAML uses the column-level `unique:`/`references:`
     * shorthand — semantically identical, and the constraint name is not
     * observable state on every dialect (SQLite synthesises `fk_N`). Absorbed
     * constraints leave the constraints block; a single-column FK whose
     * signature DIVERGES from an existing column-level reference (or an earlier
     * absorbed one) stays a distinct constraint, mirroring the comparator.
     */
    private fun foldConstraints(table: TableDefinition): FoldedConstraints {
        val unique = mutableSetOf<String>()
        val fkByCol = mutableMapOf<String, String>()
        val enumByCol = mutableMapOf<String, List<String>>()
        val remaining = mutableListOf<ConstraintDefinition>()
        // v8: authored steht der Wertevorrat am Spaltentyp. Zuerst eintragen,
        // damit ein zurueckgelesener CHECK dagegen abgeglichen werden kann.
        //
        // Sortiert wird nur fuer DIESEN Abgleich: welche Reihenfolge eine
        // Datenbank in ihrem CHECK zurueckliefert, ist nicht zugesichert. Der
        // Typ selbst behaelt seine Reihenfolge — MySQLs nativer ENUM hat
        // Ordinal-Semantik, dort ist sie bedeutsam.
        for ((colName, col) in table.columns) {
            (col.type as? NeutralType.Enum)?.values?.let { enumByCol[colName] = it.sorted() }
        }
        val foldable = foldableEnumChecks(table, enumByCol)
        for (c in table.constraints) {
            // Fold only onto columns that exist — a constraint on an unknown column
            // must stay in the block, not silently vanish from the projection.
            val cols = c.columns?.takeIf { it.size == 1 && it.first() in table.columns }
            when {
                c.type == ConstraintType.UNIQUE && cols != null ->
                    unique += cols.first()

                c.type == ConstraintType.FOREIGN_KEY && cols != null &&
                    c.references != null && c.references.columns.size == 1 -> {
                    val colName = cols.first()
                    val sig = fkSignature(c.references)
                    val columnRef = table.columns[colName]?.references?.let(::reference)?.ifEmpty { null }
                    val existing = columnRef ?: fkByCol[colName]
                    if (existing != null && existing != sig) {
                        remaining += ConstraintDiffContract.comparable(c)
                    } else {
                        fkByCol[colName] = sig
                    }
                }

                // v8: der zurueckgelesene Enum. Ein CHECK, der den Wertevorrat
                // EINER Spalte aufzaehlt, beschreibt dasselbe wie ein
                // `enum` am Spaltentyp — er wandert in dieselbe Projektion
                // statt in den Constraint-Block, damit beide Seiten gleich
                // hashen. Welche Constraints das sind, steht vorher fest
                // ([foldableEnumChecks]); hier wird nur noch zugeordnet.
                c.type == ConstraintType.CHECK && foldable.containsKey(c) -> {
                    enumByCol[foldable.getValue(c).first] = foldable.getValue(c).second
                }

                // Comparator-Parität auch für den Rest: CHECK-/EXCLUDE-Expressions
                // werden wie in TableComparator.normalizeConstraints kanonisiert
                // (CRLF→LF + trim), sonst driftet der Hash auf reiner Textform.
                else -> remaining += ConstraintDiffContract.comparable(c)
            }
        }
        return FoldedConstraints(unique, fkByCol, enumByCol, remaining)
    }

    /**
     * Welche CHECK-Constraints als Wertevorrat gelten — **vor** dem Falten
     * bestimmt, damit das Ergebnis nicht von der Reihenfolge in
     * `table.constraints` abhaengt.
     *
     * Zwei Faelle bleiben ausdruecklich ungefaltet:
     *
     * - **Mehr als ein passender CHECK auf derselben Spalte.** Dann ist nicht
     *   entscheidbar, welcher den Wertevorrat beschreibt — und zwei Constraints
     *   in eine Projektion zu falten liesse einen davon spurlos verschwinden,
     *   samt dem Unterschied, den er ausmacht.
     * - **Ein CHECK, der dem Spaltentyp widerspricht.** Ein handgeschriebener
     *   CHECK mit anderen Werten als das authored `enum` bleibt sichtbar.
     */
    private fun foldableEnumChecks(
        table: TableDefinition,
        fromColumnTypes: Map<String, List<String>>,
    ): Map<ConstraintDefinition, Pair<String, List<String>>> {
        val candidates = mutableListOf<Pair<ConstraintDefinition, Pair<String, List<String>>>>()
        for (c in table.constraints) {
            if (c.type != ConstraintType.CHECK) continue
            for (colName in table.columns.keys) {
                val values = EnumCheckProjection.valuesOf(c.expression, colName)?.sorted() ?: continue
                candidates += c to (colName to values)
                break
            }
        }
        val perColumn = candidates.groupBy { it.second.first }
        return candidates
            .filter { (_, hit) ->
                val (colName, values) = hit
                val declared = fromColumnTypes[colName]
                perColumn.getValue(colName).size == 1 && (declared == null || declared == values)
            }
            .toMap()
    }

    /** Single-column FK signature — delegiert an [reference], damit es genau EIN
     *  String-Format gibt, gegen das der Fold vergleicht. */
    private fun fkSignature(ref: ConstraintReferenceDefinition): String =
        reference(
            ReferenceDefinition(
                table = ref.table,
                column = ref.columns.first(),
                onDelete = ref.onDelete,
                onUpdate = ref.onUpdate,
            ),
        )

    // v4: project partitioning (strategy, key, child partitions). Children are
    // sorted by name (set equality, ADR 0019); bounds render in the same canonical
    // encoding the reverse parser / generator share, so an unchanged partitioned
    // table hashes identically across a round-trip.
    private fun appendPartitioning(sb: StringBuilder, partitioning: PartitionConfig?) {
        if (partitioning == null) {
            sb.append("  partitioning=none\n")
            return
        }
        sb.append("  partitioning=").append(partitioning.type.name)
            .append(SEP).append("key=").append(partitioning.key.joinToString(","))
            .append(SEP).append("partitions[").append(partitioning.partitions.size).append("]\n")
        for (part in partitioning.partitions.sortedBy { it.name }) {
            sb.append("    partition=").append(part.name)
                .append(SEP).append("default=").append(part.isDefault)
                .append(SEP).append("from=").append(bounds(part.from))
                .append(SEP).append("to=").append(bounds(part.to))
                .append(SEP).append("values=")
                .append(part.values?.let { if (it.isEmpty()) EMPTY_LIST_MARKER else it.joinToString(",") } ?: "")
                .append(SEP).append("modulus=").append(part.modulus ?: "")
                .append(SEP).append("remainder=").append(part.remainder ?: "")
                .append('\n')
            // v5/AP2a: child-local indices, same shape as the table-level index
            // projection, sorted by the shared indexOrder (declaration order is
            // not semantic). Lets the fingerprint agree with the comparator,
            // which compares partition.indices as a set.
            for (idx in part.indices.sortedWith(indexOrder)) appendIndex(sb, "      ", "partition_index", idx)
        }
    }

    /** Shared index projection — same field shape for table-level and partition-local indices. */
    private fun appendIndex(sb: StringBuilder, indent: String, label: String, idx: IndexDefinition) {
        sb.append(indent).append(label).append('=').append(idx.name ?: "")
            .append(SEP).append("columns=").append(idx.columns.joinToString(","))
            .append(SEP).append("type=").append(idx.type.name)
            .append(SEP).append("unique=").append(idx.unique)
            .append(SEP).append("where=").append(idx.where ?: "")
            // v6 (ADR 0025): a FULLTEXT index's text-search config is semantic (it changes
            // the analysis), so it is projected here — the comparator distinguishes it via
            // data-class equality, and the fingerprint must agree. The backing tsvector
            // column and access method are deliberately NOT projected: they are generate-only
            // reconstruction hints (excluded from `TableComparator.projectIndex` too), so a
            // hint-only difference must not read as DIFFERENT to `schema compare`.
            .append(SEP).append("textSearchConfig=").append(idx.textSearchConfig ?: "")
            // v9: abdeckende und clustered Indizes. Beide sind semantisch und stehen
            // deshalb in derselben Projektion wie `where` -- der Comparator sieht sie
            // ueber die Datenklassen-Gleichheit, und der Fingerabdruck muss mitgehen.
            .append(SEP).append("includeColumns=").append(idx.includeColumns.joinToString(","))
            .append(SEP).append("clustered=").append(idx.clustered)
            .append('\n')
    }

    /**
     * A null bound list (non-RANGE strategies have no `from`/`to`) projects to
     * the empty string; an *explicitly empty* list projects to [EMPTY_LIST_MARKER].
     * The comparator distinguishes `null` from `[]` via data-class equality, so the
     * fingerprint must too — otherwise a partition-only difference could be
     * DIFFERENT to `schema compare` yet identical to the drift check. The empty
     * case is unreachable from the reverse reader (RANGE always yields non-empty
     * bounds), so no real partition's projection changes.
     */
    private fun bounds(list: List<PartitionBound>?): String = when {
        list == null -> ""
        list.isEmpty() -> EMPTY_LIST_MARKER
        else -> list.joinToString(",") { bound ->
            when (bound) {
                PartitionBound.MinValue -> "MINVALUE"
                PartitionBound.MaxValue -> "MAXVALUE"
                is PartitionBound.Value -> bound.literal
            }
        }
    }

    private const val EMPTY_LIST_MARKER = "<empty>"

    private val indexOrder = compareBy<IndexDefinition> { it.name ?: "" }
        .thenBy { it.columns.joinToString(",") }

    /**
     * v3 canonicalisation: the *effective* primary key. An explicit `primary_key`
     * wins as-is. Otherwise the PK is derived from an `identifier`-typed column
     * (`spec/neutral-model-spec.md` 13.1 — `identifier` carries PK semantics), so a
     * desired schema that omits `primary_key` hashes identically to the reverse,
     * which always materialises the PK explicitly.
     *
     * Precise rule — derive ONLY when there is **exactly one** `identifier` column
     * and `primaryKey` is empty. Multiple `identifier` columns make the implicit PK
     * **ambiguous**; we must NOT invent one (the schemas should still drift). An
     * explicit `primary_key` that diverges from the `identifier` column is left
     * untouched (non-empty → used verbatim), so divergent/composite PKs keep
     * producing distinct projections.
     */
    private fun effectivePrimaryKey(table: TableDefinition): List<String> =
        EffectivePrimaryKey.of(table)

    // ── Views ───────────────────────────────────────────────────────

    private fun appendViews(sb: StringBuilder, views: Map<String, ViewDefinition>) {
        sb.append("views[").append(views.size).append("]\n")
        for ((name, view) in views.entries.sortedBy { it.key }) {
            sb.append("view=").append(name)
                .append(SEP).append("materialized=").append(view.materialized)
                .append(SEP).append("refresh=").append(view.refresh ?: "")
                .append(SEP).append("query=").append(view.query ?: "")
                .append(SEP).append("source_dialect=").append(view.sourceDialect ?: "")
                .append('\n')
        }
    }

    // ── Sequences ───────────────────────────────────────────────────

    private fun appendSequences(sb: StringBuilder, seqs: Map<String, SequenceDefinition>) {
        sb.append("sequences[").append(seqs.size).append("]\n")
        for ((name, seq) in seqs.entries.sortedBy { it.key }) {
            sb.append("sequence=").append(name)
                .append(SEP).append("start=").append(seq.start)
                .append(SEP).append("increment=").append(seq.increment)
                .append(SEP).append("min=").append(seq.minValue ?: "")
                .append(SEP).append("max=").append(seq.maxValue ?: "")
                .append(SEP).append("cycle=").append(seq.cycle)
                .append(SEP).append("cache=").append(seq.cache ?: "")
                .append('\n')
        }
    }

    // ── Routines / triggers ────────────────────────────────────────

    private fun appendFunctions(sb: StringBuilder, fns: Map<String, FunctionDefinition>) {
        sb.append("functions[").append(fns.size).append("]\n")
        for ((name, fn) in fns.entries.sortedBy { it.key }) {
            sb.append("function=").append(name)
                .append(SEP).append("language=").append(fn.language ?: "")
                .append(SEP).append("returns=").append(fn.returns?.type ?: "")
                .append(SEP).append("body=").append(fn.body ?: "")
                .append(SEP).append("source_dialect=").append(fn.sourceDialect ?: "")
                // E.1 Slice A: include the routine identity attrs in
                // the fingerprint so two schemas that differ only in
                // security/definer/searchPath/sqlMode produce
                // different hashes. Without this, skip-if-unchanged
                // caches would conclude "no change" while the
                // comparator emits ReplaceFunction.
                .append(SEP).append("security=").append(fn.security?.name ?: "")
                .append(SEP).append("definer=").append(fn.definer ?: "")
                .append(SEP).append("search_path=").append(
                    RoutineIdentityNormalizer.normalizePostgresSearchPath(fn.searchPath)?.joinToString(",") ?: "",
                )
                .append(SEP).append("sql_mode=").append(RoutineIdentityNormalizer.normalizeMysqlSqlMode(fn.sqlMode) ?: "")
                .append('\n')
        }
    }

    private fun appendProcedures(sb: StringBuilder, procs: Map<String, ProcedureDefinition>) {
        sb.append("procedures[").append(procs.size).append("]\n")
        for ((name, proc) in procs.entries.sortedBy { it.key }) {
            sb.append("procedure=").append(name)
                .append(SEP).append("language=").append(proc.language ?: "")
                .append(SEP).append("body=").append(proc.body ?: "")
                .append(SEP).append("source_dialect=").append(proc.sourceDialect ?: "")
                .append(SEP).append("security=").append(proc.security?.name ?: "")
                .append(SEP).append("definer=").append(proc.definer ?: "")
                .append(SEP).append("search_path=").append(
                    RoutineIdentityNormalizer.normalizePostgresSearchPath(proc.searchPath)?.joinToString(",") ?: "",
                )
                .append(SEP).append("sql_mode=").append(RoutineIdentityNormalizer.normalizeMysqlSqlMode(proc.sqlMode) ?: "")
                .append('\n')
        }
    }

    private fun appendTriggers(sb: StringBuilder, trs: Map<String, TriggerDefinition>) {
        sb.append("triggers[").append(trs.size).append("]\n")
        for ((name, trg) in trs.entries.sortedBy { it.key }) {
            sb.append("trigger=").append(name)
                .append(SEP).append("table=").append(trg.table)
                .append(SEP).append("event=").append(trg.events.canonicalOrder().joinToString(",") { it.name })
                .append(SEP).append("timing=").append(trg.timing.name)
                .append(SEP).append("for_each=").append(trg.forEach.name)
                .append(SEP).append("condition=").append(trg.condition ?: "")
                .append(SEP).append("body=").append(trg.body ?: "")
                .append(SEP).append("source_dialect=").append(trg.sourceDialect ?: "")
                .append('\n')
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

}
