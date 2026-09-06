package dev.dmigrate.core.diff.migration

/**
 * F.4 Sub-Slice A.2: per-dialect classification of how the Mapper
 * should turn a rename candidate into operations.
 *
 * Implementations live next to the existing per-dialect
 * `RenameDependencyPolicy` registry in `hexagon:core` so the Mapper
 * has a single core-local entry point. The application/CLI layer
 * maps `DatabaseDialect` → `RenameProjectionDialect` (the existing
 * core-local discriminator from the Dependency-Projection slice)
 * before `DiffPlanner.plan(...)` and the planner threads that
 * value through `RenameProjectionCapabilities`.
 *
 * `classify(...)` is total: every (dialect, [ObjectRenameCandidate])
 * combination yields one of the three [RenameSupport] branches.
 * Runtime-dependent decisions (PG-major version, MySQL family,
 * SQLite legacy-alter-table) come through the [capabilities]
 * argument, not from the policy's `dialect` discriminator.
 */
internal interface ObjectRenamePolicy {

    /** Which dialect this policy implementation owns. */
    val dialect: RenameProjectionDialect

    fun classify(
        candidate: ObjectRenameCandidate,
        capabilities: RenameProjectionCapabilities,
    ): RenameSupport
}

/**
 * Lookup for the per-dialect [ObjectRenamePolicy] implementation.
 * Mapper code calls [forDialect] once per rename candidate; the
 * registry holds the singletons.
 */
internal object ObjectRenamePolicyRegistry {

    private val policies: Map<RenameProjectionDialect, ObjectRenamePolicy> = mapOf(
        RenameProjectionDialect.POSTGRESQL to PostgresObjectRenamePolicy,
        RenameProjectionDialect.MYSQL to MysqlObjectRenamePolicy,
        RenameProjectionDialect.SQLITE to SqliteObjectRenamePolicy,
        RenameProjectionDialect.ORACLE to OracleObjectRenamePolicy,
    )

    /**
     * Ein fehlender Eintrag ist ein noch nicht gebauter Dialekt, kein
     * Programmierfehler — die Registry waechst pro Dialekt. `getValue`
     * haette daraus eine `NoSuchElementException` mitten im Planer
     * gemacht: ein Abbruch ohne Diagnose-Code, wo der Vertrag einen
     * Blocker vorsieht. Betrifft heute MSSQL
     * (`docs/planning/open/mssql-object-rename-policy-missing.md`).
     */
    fun forDialect(dialect: RenameProjectionDialect): ObjectRenamePolicy =
        policies[dialect] ?: UnsupportedObjectRenamePolicy(dialect)
}

internal class UnsupportedObjectRenamePolicy(
    override val dialect: RenameProjectionDialect,
) : ObjectRenamePolicy {

    override fun classify(
        candidate: ObjectRenameCandidate,
        capabilities: RenameProjectionCapabilities,
    ): RenameSupport = RenameSupport.Blocked(
        code = "OBJECT_RENAME_UNSUPPORTED",
        message = "d-migrate has no object-rename policy for ${dialect.name}; the rename of " +
            "${candidate.objectType} '${candidate.fromName}' → '${candidate.toName}' cannot be " +
            "classified. Remove the rename mapping for this object, or drop and recreate it explicitly.",
    )
}

/**
 * PostgreSQL: every supported object kind has a native
 * `ALTER … RENAME TO …` template. Materialized views block until a
 * dedicated D.3b rename contract ships (the candidate carries
 * `materializedView = true`). Routine candidates with an unequal
 * body hash also block: PG's `ALTER FUNCTION/PROCEDURE … RENAME`
 * preserves the body, but a rename combined with a body change is
 * an `OR REPLACE` + `RENAME` operation pair and that combined path
 * is out of E.1 scope.
 */
internal object PostgresObjectRenamePolicy : ObjectRenamePolicy {

    override val dialect: RenameProjectionDialect = RenameProjectionDialect.POSTGRESQL

    override fun classify(
        candidate: ObjectRenameCandidate,
        capabilities: RenameProjectionCapabilities,
    ): RenameSupport {
        if (candidate.objectType == DiffObjectType.VIEW && candidate.materializedView) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "Materialized-view rename is part of D.3b, not F.4. " +
                    "Set ViewDefinition.materialized = false or wait for the D.3b rename contract.",
            )
        }
        if (candidate.objectType.isBodyBearing() && candidate.hasBodyDrift()) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "Body-drift detected for ${candidate.objectType} rename " +
                    "'${candidate.fromName}' → '${candidate.toName}': source and target bodies differ. " +
                    "Split the change into a body-change Replace plus a separate rename.",
            )
        }
        return RenameSupport.Native
    }
}

/**
 * MySQL: views share the table namespace and rename via
 * `RENAME TABLE` (native). Triggers and routines have no
 * `ALTER … RENAME` grammar, so the policy falls back to
 * Drop+Create. Sequence renames also fall back to Drop+Create —
 * MySQL has no native sequence concept (the helper-table
 * emulation in `MysqlSequenceEmulationTemplates` stores each
 * sequence as a row in `dmg_sequences`), and the diff renderer's
 * defensive `UPDATE dmg_sequences SET name = …` path is a
 * regression guard only.
 */
internal object MysqlObjectRenamePolicy : ObjectRenamePolicy {

    override val dialect: RenameProjectionDialect = RenameProjectionDialect.MYSQL

    override fun classify(
        candidate: ObjectRenameCandidate,
        capabilities: RenameProjectionCapabilities,
    ): RenameSupport {
        // Materialized views: MySQL has no MV support at all (D.3b
        // already blocks the create/drop paths with
        // MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT).
        if (candidate.objectType == DiffObjectType.VIEW && candidate.materializedView) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "MySQL has no native materialized-view support; rename is undefined.",
            )
        }
        return when (candidate.objectType) {
            DiffObjectType.VIEW -> RenameSupport.Native // RENAME TABLE
            DiffObjectType.TRIGGER -> mysqlBodyAwareFallback(
                candidate, "MySQL has no `ALTER TRIGGER … RENAME`",
            )
            DiffObjectType.FUNCTION, DiffObjectType.PROCEDURE -> mysqlBodyAwareFallback(
                candidate, "MySQL has no `ALTER ${candidate.objectType.name} … RENAME`",
            )
            DiffObjectType.SEQUENCE -> RenameSupport.DropCreateFallback(
                rationale = "MySQL has no native sequence-rename grammar; the helper-table " +
                    "emulation stores each sequence as a row in `dmg_sequences`, so a rename " +
                    "decomposes into DropSequence(from) + CreateSequence(to) with " +
                    "RenameProvenance. The diff renderer's `UPDATE dmg_sequences SET name = …` " +
                    "path stays as a defensive regression guard for direct RenameSequence ops.",
            )
            else -> RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "MySQL policy: object type ${candidate.objectType} is not a rename target.",
            )
        }
    }

    private fun mysqlBodyAwareFallback(
        candidate: ObjectRenameCandidate,
        rationale: String,
    ): RenameSupport {
        if (candidate.sourceBodyHash == null || candidate.targetBodyHash == null) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "$rationale and the Drop+Create fallback needs both source and target " +
                    "body hashes; missing ${
                        listOfNotNull(
                            "sourceBodyHash".takeIf { candidate.sourceBodyHash == null },
                            "targetBodyHash".takeIf { candidate.targetBodyHash == null },
                        ).joinToString(" + ")
                    }.",
            )
        }
        if (candidate.hasBodyDrift()) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "Body-drift detected for MySQL ${candidate.objectType} rename " +
                    "'${candidate.fromName}' → '${candidate.toName}': source and target bodies differ. " +
                    "Split into a body-change Replace and a separate rename.",
            )
        }
        return RenameSupport.DropCreateFallback(rationale = rationale)
    }
}

/**
 * Oracle benennt Sichten und Sequenzen mit der freistehenden Anweisung
 * `RENAME alt TO neu` um — ein `ALTER VIEW … RENAME` gibt es nicht.
 * Fuer beide ist das damit ein nativer Rename. (Tabellen kann dieselbe
 * Anweisung ebenfalls, d-migrate rendert sie dort aber als
 * `ALTER TABLE … RENAME TO`, siehe `OracleDiffTableOps`.)
 *
 * Die uebrigen Objektarten blocken, und der Grund liegt bei d-migrate,
 * nicht bei Oracle: Routinen, Trigger und Materialized Views liest der
 * Oracle-Reader nicht und der Generator schreibt sie nicht (Slices 9
 * bzw. 10, ADR 0052). Ein Rename-Vertrag fuer Objekte, die auf keinem
 * anderen Pfad existieren, waere nicht pruefbar.
 */
internal object OracleObjectRenamePolicy : ObjectRenamePolicy {

    override val dialect: RenameProjectionDialect = RenameProjectionDialect.ORACLE

    override fun classify(
        candidate: ObjectRenameCandidate,
        capabilities: RenameProjectionCapabilities,
    ): RenameSupport {
        if (candidate.objectType == DiffObjectType.VIEW && candidate.materializedView) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "d-migrate does not read or render Oracle materialized views yet " +
                    "(Oracle rollout, ADR 0052); a rename contract for them would be untestable.",
            )
        }
        return when (candidate.objectType) {
            DiffObjectType.VIEW, DiffObjectType.SEQUENCE -> oracleNativeRename(candidate)
            DiffObjectType.TRIGGER, DiffObjectType.FUNCTION, DiffObjectType.PROCEDURE ->
                RenameSupport.Blocked(
                    code = "OBJECT_RENAME_UNSUPPORTED",
                    message = "d-migrate does not read or render Oracle " +
                        "${candidate.objectType.name.lowercase()} objects yet (Oracle rollout, " +
                        "ADR 0052); a rename contract for them would be untestable.",
                )
            else -> RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "Oracle policy: object type ${candidate.objectType} is not a rename target.",
            )
        }
    }

    /**
     * `RENAME` fasst den Rumpf nicht an. Ein Rename, der gleichzeitig den
     * Rumpf aendert, wuerde die Rumpfaenderung deshalb still verschlucken —
     * er gehoert in ein `Replace` plus einen eigenen Rename.
     */
    private fun oracleNativeRename(candidate: ObjectRenameCandidate): RenameSupport {
        if (candidate.objectType.isBodyBearing() && candidate.hasBodyDrift()) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "Body-drift detected for Oracle ${candidate.objectType} rename " +
                    "'${candidate.fromName}' → '${candidate.toName}': source and target bodies differ. " +
                    "Oracle's `RENAME` leaves the body untouched, so the body change would be lost. " +
                    "Split the change into a body-change Replace plus a separate rename.",
            )
        }
        return RenameSupport.Native
    }
}

/**
 * SQLite: no native rename for views, triggers, or routines.
 * Views and triggers fall back to Drop+Create when both bodies are
 * known and identical; routines are not modelled in SQLite at all;
 * sequence rename is blocked until SQLite gets a sequence
 * rendering contract.
 */
internal object SqliteObjectRenamePolicy : ObjectRenamePolicy {

    override val dialect: RenameProjectionDialect = RenameProjectionDialect.SQLITE

    override fun classify(
        candidate: ObjectRenameCandidate,
        capabilities: RenameProjectionCapabilities,
    ): RenameSupport {
        if (candidate.objectType == DiffObjectType.VIEW && candidate.materializedView) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "SQLite has no native materialized-view support; rename is undefined.",
            )
        }
        return when (candidate.objectType) {
            DiffObjectType.VIEW -> sqliteBodyAwareFallback(
                candidate, "SQLite has no native view-rename grammar",
            )
            DiffObjectType.TRIGGER -> sqliteBodyAwareFallback(
                candidate, "SQLite has no native trigger-rename grammar",
            )
            DiffObjectType.FUNCTION, DiffObjectType.PROCEDURE -> RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "SQLite has no user-defined ${candidate.objectType.name} concept; rename is undefined.",
            )
            DiffObjectType.SEQUENCE -> RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "SQLite sequence emulation (E.3) is not yet in scope; rename is blocked.",
            )
            else -> RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "SQLite policy: object type ${candidate.objectType} is not a rename target.",
            )
        }
    }

    private fun sqliteBodyAwareFallback(
        candidate: ObjectRenameCandidate,
        rationale: String,
    ): RenameSupport {
        // Views need both query bodies; triggers need both definition bodies.
        if (candidate.sourceBodyHash == null || candidate.targetBodyHash == null) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "$rationale and the Drop+Create fallback needs both source and target body " +
                    "hashes; missing ${
                        listOfNotNull(
                            "sourceBodyHash".takeIf { candidate.sourceBodyHash == null },
                            "targetBodyHash".takeIf { candidate.targetBodyHash == null },
                        ).joinToString(" + ")
                    }.",
            )
        }
        if (candidate.hasBodyDrift()) {
            return RenameSupport.Blocked(
                code = "OBJECT_RENAME_UNSUPPORTED",
                message = "Body-drift detected for SQLite ${candidate.objectType} rename " +
                    "'${candidate.fromName}' → '${candidate.toName}': source and target bodies differ.",
            )
        }
        return RenameSupport.DropCreateFallback(rationale = rationale)
    }
}

/** Body-bearing kinds: rename must respect body identity, not just the name pair. */
private fun DiffObjectType.isBodyBearing(): Boolean = this in setOf(
    DiffObjectType.VIEW,
    DiffObjectType.TRIGGER,
    DiffObjectType.FUNCTION,
    DiffObjectType.PROCEDURE,
)

/**
 * Body-drift: both bodies present and differ. A `null` on either
 * side is **not** drift — it is a missing prior body, which the
 * policy classifies with a more specific code in
 * [MysqlObjectRenamePolicy.mysqlBodyAwareFallback] /
 * [SqliteObjectRenamePolicy.sqliteBodyAwareFallback].
 */
private fun ObjectRenameCandidate.hasBodyDrift(): Boolean =
    sourceBodyHash != null && targetBodyHash != null && sourceBodyHash != targetBodyHash
