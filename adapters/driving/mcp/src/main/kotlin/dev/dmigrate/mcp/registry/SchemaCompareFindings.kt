package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.ComputedExpressionDecidability
import dev.dmigrate.cli.commands.SchemaFindingPath
import dev.dmigrate.cli.commands.SchemaFindingPath.Section
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.mcp.registry.CompareFinding.added
import dev.dmigrate.mcp.registry.CompareFinding.beforeAfter
import dev.dmigrate.mcp.registry.CompareFinding.changed
import dev.dmigrate.mcp.registry.CompareFinding.finding
import dev.dmigrate.mcp.registry.CompareFinding.removed
import dev.dmigrate.mcp.schema.SchemaFindingSeverity

/**
 * Projiziert einen `SchemaDiff` auf die `findings` von `schema_compare`.
 *
 * Severity policy — top-level objects (tables / views / sequences /
 * customTypes / functions / procedures / triggers):
 * - added → severity `info` (additive, generally compatible)
 * - removed → severity `warning` (potentially breaking)
 * - changed (non-table objects) → severity `warning`
 *
 * Schema metadata:
 * - `name` change → severity `warning` (identity drift)
 * - `version` change → severity `info`
 *
 * Table-internal changes: see [TableCompareFindings].
 *
 * Every `path` follows the finding path schema of `schema compare`
 * (`SchemaFindingPath`, `spec/cli-spec.md`).
 */
internal object SchemaCompareFindings {

    fun of(diff: SchemaDiff): List<Map<String, Any?>> = buildList {
        diff.schemaMetadata?.let { meta ->
            meta.name?.let {
                add(
                    finding(
                        SchemaFindingSeverity.WARNING, "SCHEMA_NAME_CHANGED", SchemaFindingPath.NAME,
                        "schema name changed from '${it.before}' to '${it.after}'",
                        beforeAfter(it.before, it.after),
                    ),
                )
            }
            meta.version?.let {
                add(
                    finding(
                        SchemaFindingSeverity.INFO, "SCHEMA_VERSION_CHANGED", SchemaFindingPath.VERSION,
                        "schema version changed from '${it.before}' to '${it.after}'",
                        beforeAfter(it.before, it.after),
                    ),
                )
            }
        }
        addAll(diff.tablesAdded.map { added("TABLE_ADDED", SchemaFindingPath.table(it.name)) })
        addAll(diff.tablesRemoved.map { removed("TABLE_REMOVED", SchemaFindingPath.table(it.name)) })
        diff.tablesChanged.forEach { addAll(TableCompareFindings.of(it)) }
        addAll(diff.viewsAdded.map { added("VIEW_ADDED", SchemaFindingPath.of(Section.VIEWS, it.name)) })
        addAll(diff.viewsRemoved.map { removed("VIEW_REMOVED", SchemaFindingPath.of(Section.VIEWS, it.name)) })
        diff.viewsChanged.forEach {
            addAll(fieldChanges("VIEW_CHANGED", SchemaFindingPath.of(Section.VIEWS, it.name), ObjectDiffFields.of(it)))
        }
        addAll(diff.sequencesAdded.map { added("SEQUENCE_ADDED", SchemaFindingPath.of(Section.SEQUENCES, it.name)) })
        addAll(diff.sequencesRemoved.map { removed("SEQUENCE_REMOVED", SchemaFindingPath.of(Section.SEQUENCES, it.name)) })
        diff.sequencesChanged.forEach {
            addAll(fieldChanges("SEQUENCE_CHANGED", SchemaFindingPath.of(Section.SEQUENCES, it.name), ObjectDiffFields.of(it)))
        }
        addAll(diff.customTypesAdded.map { added("CUSTOM_TYPE_ADDED", SchemaFindingPath.of(Section.CUSTOM_TYPES, it.name)) })
        addAll(diff.customTypesRemoved.map { customTypeRemoved(SchemaFindingPath.of(Section.CUSTOM_TYPES, it.name)) })
        diff.customTypesChanged.forEach {
            addAll(fieldChanges("CUSTOM_TYPE_CHANGED", SchemaFindingPath.of(Section.CUSTOM_TYPES, it.name), ObjectDiffFields.of(it)))
        }
        addAll(diff.functionsAdded.map { added("FUNCTION_ADDED", SchemaFindingPath.of(Section.FUNCTIONS, it.name)) })
        addAll(diff.functionsRemoved.map { removed("FUNCTION_REMOVED", SchemaFindingPath.of(Section.FUNCTIONS, it.name)) })
        diff.functionsChanged.forEach {
            addAll(fieldChanges("FUNCTION_CHANGED", SchemaFindingPath.of(Section.FUNCTIONS, it.name), ObjectDiffFields.of(it)))
        }
        addAll(diff.proceduresAdded.map { added("PROCEDURE_ADDED", SchemaFindingPath.of(Section.PROCEDURES, it.name)) })
        addAll(diff.proceduresRemoved.map { removed("PROCEDURE_REMOVED", SchemaFindingPath.of(Section.PROCEDURES, it.name)) })
        diff.proceduresChanged.forEach {
            addAll(fieldChanges("PROCEDURE_CHANGED", SchemaFindingPath.of(Section.PROCEDURES, it.name), ObjectDiffFields.of(it)))
        }
        addAll(diff.triggersAdded.map { added("TRIGGER_ADDED", SchemaFindingPath.of(Section.TRIGGERS, it.name)) })
        addAll(diff.triggersRemoved.map { removed("TRIGGER_REMOVED", SchemaFindingPath.of(Section.TRIGGERS, it.name)) })
        diff.triggersChanged.forEach {
            addAll(fieldChanges("TRIGGER_CHANGED", SchemaFindingPath.of(Section.TRIGGERS, it.name), ObjectDiffFields.of(it)))
        }
    }

    /**
     * Projects a [ComputedExpressionDecidability] result onto the same
     * finding shape as everything else — `path` is pulled out of the first
     * backtick-quoted section of the message, since [DiffDiagnostic] itself
     * has no structured path field. The diagnostic writes its location there
     * in the finding path schema (`SchemaFindingPath`), so the value follows
     * the same structure as every other finding.
     */
    fun undecided(d: DiffDiagnostic): Map<String, Any?> {
        val path = Regex("`([^`]+)`").find(d.message)?.groupValues?.get(1) ?: "-"
        return finding(SchemaFindingSeverity.WARNING, d.code, path, d.message)
    }

    /**
     * Ein Custom-Type, den die Gegenseite nicht als Typ fuehrt, ist nicht
     * zwangslaeufig verloren: Dialekte ohne benannte Typen (MySQL, SQLite)
     * tragen einen Enum-Wertevorrat **inline** an der Spalte, etwa als CHECK.
     * Der Fund bleibt — der Typ *ist* auf dieser Seite nicht vorhanden —, aber
     * „was removed" behauptet einen Verlust, den es oft nicht gibt.
     */
    private fun customTypeRemoved(path: String): Map<String, Any?> =
        finding(
            SchemaFindingSeverity.WARNING,
            "CUSTOM_TYPE_REMOVED",
            path,
            "$path is not present as a custom type on the other side — a dialect without named types " +
                "carries the same values inline on the column",
        )

    /**
     * Ein Fund je geaendertem Feld eines Objekts, der Pfad endet auf dem
     * Dokument-Schluessel des Feldes — derselbe Aufbau wie bei den Spalten
     * einer Tabelle (`tables.t.columns.c.type`).
     *
     * Die Werte stehen als `before`/`after` darin; fehlt eine Seite, war das
     * Feld dort nicht gesetzt. Listen stehen als `[a, b]` — auch die Spalten
     * einer Sicht (nur die Namen; sie werden nur verglichen, wenn beide Seiten
     * sie tragen, siehe `SchemaComparator.compareView`). Ausgenommen sind die
     * Felder in [WITHOUT_VALUES]: lange Rumpf-Texte und strukturierte Werte —
     * der Aufrufer hat beide Seiten selbst in der Hand.
     */
    private fun fieldChanges(
        code: String,
        objectPath: String,
        fields: List<Pair<String, ValueChange<*>?>>,
    ): List<Map<String, Any?>> = fields.mapNotNull { (key, change) ->
        change?.let {
            val details = if (key in WITHOUT_VALUES) null else beforeAfter(it.before, it.after)
            changed(code, SchemaFindingPath.field(objectPath, key), details)
        }
    }

    private val WITHOUT_VALUES = setOf("query", "body", "parameters", "returns", "fields")
}
