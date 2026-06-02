package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition

/**
 * F.4 Sub-Slice A.2 Teil 2: fold rename-overlay mappings for views,
 * triggers, functions, procedures and sequences into the matching
 * `Rename*` operation, or fall back to Drop+Create with a
 * [RenameProvenance] marker, or block with `OBJECT_RENAME_UNSUPPORTED`,
 * depending on what [ObjectRenamePolicyRegistry.forDialect] returns for
 * the candidate.
 *
 * Each `foldRename*` consumes the corresponding overlay-mapping list
 * from the [RenameOverlayIndex], builds an [ObjectRenameCandidate] from
 * the schema-side diff entries, consults the dialect-specific
 * [ObjectRenamePolicy], and emits one of three outcomes per mapping:
 *
 * - [RenameSupport.Native]: emit a single `Rename*` operation, absorb
 *   both the `from` and `to` visible names so the caller's regular
 *   Create/Drop loop skips them.
 * - [RenameSupport.DropCreateFallback]: leave the visible names in
 *   `*Added`/`*Removed` so the regular loop emits Drop+Create, but
 *   register a [RenameProvenance] keyed by `fromName` / `toName` so
 *   the caller tags those operations.
 * - [RenameSupport.Blocked]: emit an `OBJECT_RENAME_UNSUPPORTED`
 *   BLOCKER diagnostic; no absorption, no `Rename*` operation, no
 *   provenance marker. The regular Drop+Create still runs but without
 *   any rename provenance.
 */
internal object RenameObjectMapper {

    const val OBJECT_RENAME_UNSUPPORTED: String = "OBJECT_RENAME_UNSUPPORTED"

    /**
     * Per-kind fold output the caller's `mapViews/mapSequences/...`
     * loops consume to skip absorbed names and to tag Drop+Create
     * fallbacks with their rename provenance.
     */
    data class ObjectFoldResult(
        val absorbedFromNames: Set<String>,
        val absorbedToNames: Set<String>,
        val fallbackByFromName: Map<String, RenameProvenance>,
        val fallbackByToName: Map<String, RenameProvenance>,
    ) {
        companion object {
            val EMPTY: ObjectFoldResult = ObjectFoldResult(emptySet(), emptySet(), emptyMap(), emptyMap())
        }
    }

    fun foldRenameViews(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): ObjectFoldResult {
        val mappings = renameIndex.viewMappings()
        if (mappings.isEmpty()) return ObjectFoldResult.EMPTY
        val state = FoldState()
        val policy = ObjectRenamePolicyRegistry.forDialect(ctx.capabilities.dialect)
        val addedByName = diff.viewsAdded.associateBy { it.name }
        val removedByName = diff.viewsRemoved.associateBy { it.name }
        for (mapping in mappings) {
            val removed = removedByName[mapping.fromName] ?: continue
            val added = addedByName[mapping.toName] ?: continue
            val candidate = buildViewCandidate(mapping, removed.definition, added.definition)
            when (val support = policy.classify(candidate, ctx.capabilities)) {
                is RenameSupport.Native -> {
                    ops += buildRenameView(mapping)
                    state.absorb(mapping.fromName, mapping.toName)
                }
                is RenameSupport.DropCreateFallback -> {
                    val provenance = mapping.toProvenance(DiffObjectType.VIEW, support.rationale)
                    state.fallback(mapping.fromName, mapping.toName, provenance)
                }
                is RenameSupport.Blocked -> diagnostics += blockedDiagnostic(
                    kind = "view",
                    from = mapping.fromName,
                    to = mapping.toName,
                    source = mapping.source,
                    entryId = mapping.entryId,
                    support = support,
                )
            }
        }
        return state.result()
    }

    fun foldRenameTriggers(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): ObjectFoldResult {
        val mappings = renameIndex.triggerMappings()
        if (mappings.isEmpty()) return ObjectFoldResult.EMPTY
        val state = FoldState()
        val policy = ObjectRenamePolicyRegistry.forDialect(ctx.capabilities.dialect)
        val addedByName = diff.triggersAdded.associateBy { it.name }
        val removedByName = diff.triggersRemoved.associateBy { it.name }
        for (mapping in mappings) {
            val removed = removedByName[mapping.fromName] ?: continue
            val added = addedByName[mapping.toName] ?: continue
            // Schema-side table mismatch: the canonical key in the
            // overlay claims `table::name` but the actual trigger lives
            // on a different table. Cross-table moves are blocked at
            // index time; this is the residual safety net.
            if (!removed.definition.table.equals(mapping.table, ignoreCase = true) ||
                !added.definition.table.equals(mapping.table, ignoreCase = true)
            ) {
                diagnostics += blockedDiagnostic(
                    kind = "trigger",
                    from = ObjectKeyCodec.triggerKey(mapping.table, mapping.fromName),
                    to = ObjectKeyCodec.triggerKey(mapping.table, mapping.toName),
                    source = mapping.source,
                    entryId = mapping.entryId,
                    support = RenameSupport.Blocked(
                        code = OBJECT_RENAME_UNSUPPORTED,
                        message = "Trigger rename overlay claims table '${mapping.table}' but schema-side " +
                            "trigger lives on '${removed.definition.table}' / '${added.definition.table}'.",
                    ),
                )
                continue
            }
            val candidate = buildTriggerCandidate(mapping, removed.definition, added.definition)
            when (val support = policy.classify(candidate, ctx.capabilities)) {
                is RenameSupport.Native -> {
                    ops += buildRenameTrigger(mapping, removed.definition.body)
                    state.absorb(mapping.fromName, mapping.toName)
                }
                is RenameSupport.DropCreateFallback -> {
                    val provenance = mapping.toProvenance(DiffObjectType.TRIGGER, support.rationale)
                    state.fallback(mapping.fromName, mapping.toName, provenance)
                }
                is RenameSupport.Blocked -> diagnostics += blockedDiagnostic(
                    kind = "trigger",
                    from = ObjectKeyCodec.triggerKey(mapping.table, mapping.fromName),
                    to = ObjectKeyCodec.triggerKey(mapping.table, mapping.toName),
                    source = mapping.source,
                    entryId = mapping.entryId,
                    support = support,
                )
            }
        }
        return state.result()
    }

    fun foldRenameFunctions(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): ObjectFoldResult {
        val mappings = renameIndex.functionMappings()
        if (mappings.isEmpty()) return ObjectFoldResult.EMPTY
        val state = FoldState()
        val policy = ObjectRenamePolicyRegistry.forDialect(ctx.capabilities.dialect)
        for (mapping in mappings) {
            val removed = diff.functionsRemoved.firstOrNull {
                it.name == mapping.fromName && matchesSignature(it.definition.parameters, mapping.parameters)
            } ?: continue
            val added = diff.functionsAdded.firstOrNull {
                it.name == mapping.toName && matchesSignature(it.definition.parameters, mapping.parameters)
            } ?: continue
            val parameters = removed.definition.parameters
            val candidate = ObjectRenameCandidate(
                objectType = DiffObjectType.FUNCTION,
                fromName = mapping.fromName,
                toName = mapping.toName,
                routineSignature = parameters,
                sourceBodyHash = removed.definition.body,
                targetBodyHash = added.definition.body,
            )
            when (val support = policy.classify(candidate, ctx.capabilities)) {
                is RenameSupport.Native -> {
                    ops += buildRenameFunction(mapping, parameters, removed.definition.body)
                    state.absorb(mapping.fromName, mapping.toName)
                }
                is RenameSupport.DropCreateFallback -> {
                    val provenance = mapping.toRoutineProvenance(DiffObjectType.FUNCTION, parameters, support.rationale)
                    state.fallback(mapping.fromName, mapping.toName, provenance)
                }
                is RenameSupport.Blocked -> diagnostics += blockedDiagnostic(
                    kind = "function",
                    from = ObjectKeyCodec.routineKey(mapping.fromName, parameters),
                    to = ObjectKeyCodec.routineKey(mapping.toName, parameters),
                    source = mapping.source,
                    entryId = mapping.entryId,
                    support = support,
                )
            }
        }
        return state.result()
    }

    fun foldRenameProcedures(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): ObjectFoldResult {
        val mappings = renameIndex.procedureMappings()
        if (mappings.isEmpty()) return ObjectFoldResult.EMPTY
        val state = FoldState()
        val policy = ObjectRenamePolicyRegistry.forDialect(ctx.capabilities.dialect)
        for (mapping in mappings) {
            val removed = diff.proceduresRemoved.firstOrNull {
                it.name == mapping.fromName && matchesSignature(it.definition.parameters, mapping.parameters)
            } ?: continue
            val added = diff.proceduresAdded.firstOrNull {
                it.name == mapping.toName && matchesSignature(it.definition.parameters, mapping.parameters)
            } ?: continue
            val parameters = removed.definition.parameters
            val candidate = ObjectRenameCandidate(
                objectType = DiffObjectType.PROCEDURE,
                fromName = mapping.fromName,
                toName = mapping.toName,
                routineSignature = parameters,
                sourceBodyHash = removed.definition.body,
                targetBodyHash = added.definition.body,
            )
            when (val support = policy.classify(candidate, ctx.capabilities)) {
                is RenameSupport.Native -> {
                    ops += buildRenameProcedure(mapping, parameters, removed.definition.body)
                    state.absorb(mapping.fromName, mapping.toName)
                }
                is RenameSupport.DropCreateFallback -> {
                    val provenance = mapping.toRoutineProvenance(
                        DiffObjectType.PROCEDURE, parameters, support.rationale,
                    )
                    state.fallback(mapping.fromName, mapping.toName, provenance)
                }
                is RenameSupport.Blocked -> diagnostics += blockedDiagnostic(
                    kind = "procedure",
                    from = ObjectKeyCodec.routineKey(mapping.fromName, parameters),
                    to = ObjectKeyCodec.routineKey(mapping.toName, parameters),
                    source = mapping.source,
                    entryId = mapping.entryId,
                    support = support,
                )
            }
        }
        return state.result()
    }

    fun foldRenameSequences(
        diff: SchemaDiff,
        ctx: RenameMappingContext,
        renameIndex: RenameOverlayIndex,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ): ObjectFoldResult {
        val mappings = renameIndex.sequenceMappings()
        if (mappings.isEmpty()) return ObjectFoldResult.EMPTY
        val state = FoldState()
        val policy = ObjectRenamePolicyRegistry.forDialect(ctx.capabilities.dialect)
        val addedByName = diff.sequencesAdded.associateBy { it.name }
        val removedByName = diff.sequencesRemoved.associateBy { it.name }
        for (mapping in mappings) {
            removedByName[mapping.fromName] ?: continue
            addedByName[mapping.toName] ?: continue
            val candidate = ObjectRenameCandidate(
                objectType = DiffObjectType.SEQUENCE,
                fromName = mapping.fromName,
                toName = mapping.toName,
            )
            when (val support = policy.classify(candidate, ctx.capabilities)) {
                is RenameSupport.Native -> {
                    ops += buildRenameSequence(mapping)
                    state.absorb(mapping.fromName, mapping.toName)
                }
                is RenameSupport.DropCreateFallback -> {
                    val provenance = mapping.toProvenance(DiffObjectType.SEQUENCE, support.rationale)
                    state.fallback(mapping.fromName, mapping.toName, provenance)
                }
                is RenameSupport.Blocked -> diagnostics += blockedDiagnostic(
                    kind = "sequence",
                    from = mapping.fromName,
                    to = mapping.toName,
                    source = mapping.source,
                    entryId = mapping.entryId,
                    support = support,
                )
            }
        }
        return state.result()
    }

    /**
     * Compares the schema-side [ParameterDefinition] list with the
     * overlay's parsed `(direction:type)` pairs. Returns `true` only
     * when both lists have the same length, the same order and the
     * same `(direction-lowercased, type)` content. The parameter
     * *name* is irrelevant for signature identity — PostgreSQL
     * dispatches on type alone.
     */
    private fun matchesSignature(
        schemaParams: List<ParameterDefinition>,
        overlayParams: List<Pair<String, String>>,
    ): Boolean {
        if (schemaParams.size != overlayParams.size) return false
        return schemaParams.withIndex().all { (idx, p) ->
            val (direction, type) = overlayParams[idx]
            p.direction.name.equals(direction, ignoreCase = true) && p.type == type
        }
    }

    // ── Candidate builders ──────────────────────────────────────────

    private fun buildViewCandidate(
        mapping: ViewRenameMapping,
        before: ViewDefinition,
        after: ViewDefinition,
    ): ObjectRenameCandidate = ObjectRenameCandidate(
        objectType = DiffObjectType.VIEW,
        fromName = mapping.fromName,
        toName = mapping.toName,
        materializedView = before.materialized || after.materialized,
        sourceBodyHash = before.query,
        targetBodyHash = after.query,
    )

    private fun buildTriggerCandidate(
        mapping: TriggerRenameMapping,
        before: TriggerDefinition,
        after: TriggerDefinition,
    ): ObjectRenameCandidate = ObjectRenameCandidate(
        objectType = DiffObjectType.TRIGGER,
        fromName = mapping.fromName,
        toName = mapping.toName,
        triggerTableName = mapping.table,
        sourceBodyHash = before.body,
        targetBodyHash = after.body,
    )

    // ── Operation builders ──────────────────────────────────────────

    private fun buildRenameView(mapping: ViewRenameMapping): DiffOperation.RenameView {
        val ref = DiffObjectRef(DiffObjectType.VIEW, listOf(mapping.toName))
        return DiffOperation.RenameView(
            id = OperationIdFactory.makeId(
                "RenameView", ref,
                "from=${mapping.fromName}->to=${mapping.toName}::overlay=${mapping.overlayHash ?: "<unhashed>"}",
            ),
            objectRef = ref,
            fromName = mapping.fromName,
            toName = mapping.toName,
            overlaySource = mapping.source,
            overlayEntryId = mapping.entryId,
            overlayHash = mapping.overlayHash,
        )
    }

    private fun buildRenameTrigger(
        mapping: TriggerRenameMapping,
        body: String?,
    ): DiffOperation.RenameTrigger {
        val canonicalKey = ObjectKeyCodec.triggerKey(mapping.table, mapping.toName)
        val ref = DiffObjectRef(DiffObjectType.TRIGGER, listOf(canonicalKey))
        return DiffOperation.RenameTrigger(
            id = OperationIdFactory.makeId(
                "RenameTrigger", ref,
                "from=${mapping.fromName}->to=${mapping.toName}@${mapping.table}" +
                    "::overlay=${mapping.overlayHash ?: "<unhashed>"}",
            ),
            objectRef = ref,
            tableName = mapping.table,
            fromName = mapping.fromName,
            toName = mapping.toName,
            bodyHash = body,
            overlaySource = mapping.source,
            overlayEntryId = mapping.entryId,
            overlayHash = mapping.overlayHash,
        )
    }

    private fun buildRenameFunction(
        mapping: RoutineRenameMapping,
        parameters: List<ParameterDefinition>,
        body: String?,
    ): DiffOperation.RenameFunction {
        val canonicalKey = ObjectKeyCodec.routineKey(mapping.toName, parameters)
        val ref = DiffObjectRef(DiffObjectType.FUNCTION, listOf(canonicalKey))
        return DiffOperation.RenameFunction(
            id = OperationIdFactory.makeId(
                "RenameFunction", ref,
                "from=${ObjectKeyCodec.routineKey(mapping.fromName, parameters)}" +
                    "->to=$canonicalKey::overlay=${mapping.overlayHash ?: "<unhashed>"}",
            ),
            objectRef = ref,
            fromName = mapping.fromName,
            toName = mapping.toName,
            signature = parameters,
            bodyHash = body,
            overlaySource = mapping.source,
            overlayEntryId = mapping.entryId,
            overlayHash = mapping.overlayHash,
        )
    }

    private fun buildRenameProcedure(
        mapping: RoutineRenameMapping,
        parameters: List<ParameterDefinition>,
        body: String?,
    ): DiffOperation.RenameProcedure {
        val canonicalKey = ObjectKeyCodec.routineKey(mapping.toName, parameters)
        val ref = DiffObjectRef(DiffObjectType.PROCEDURE, listOf(canonicalKey))
        return DiffOperation.RenameProcedure(
            id = OperationIdFactory.makeId(
                "RenameProcedure", ref,
                "from=${ObjectKeyCodec.routineKey(mapping.fromName, parameters)}" +
                    "->to=$canonicalKey::overlay=${mapping.overlayHash ?: "<unhashed>"}",
            ),
            objectRef = ref,
            fromName = mapping.fromName,
            toName = mapping.toName,
            signature = parameters,
            bodyHash = body,
            overlaySource = mapping.source,
            overlayEntryId = mapping.entryId,
            overlayHash = mapping.overlayHash,
        )
    }

    private fun buildRenameSequence(mapping: SequenceRenameMapping): DiffOperation.RenameSequence {
        val ref = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(mapping.toName))
        return DiffOperation.RenameSequence(
            id = OperationIdFactory.makeId(
                "RenameSequence", ref,
                "from=${mapping.fromName}->to=${mapping.toName}::overlay=${mapping.overlayHash ?: "<unhashed>"}",
            ),
            objectRef = ref,
            fromName = mapping.fromName,
            toName = mapping.toName,
            overlaySource = mapping.source,
            overlayEntryId = mapping.entryId,
            overlayHash = mapping.overlayHash,
        )
    }

    // ── Provenance + diagnostic builders ────────────────────────────

    private fun ViewRenameMapping.toProvenance(
        objectType: DiffObjectType,
        rationale: String,
    ): RenameProvenance = RenameProvenance(
        candidateId = entryId,
        objectType = objectType,
        fromPath = listOf(fromName),
        toPath = listOf(toName),
        overlaySource = source,
        overlayEntryId = entryId,
        overlayHash = overlayHash,
        fallbackReason = rationale,
    )

    private fun SequenceRenameMapping.toProvenance(
        objectType: DiffObjectType,
        rationale: String,
    ): RenameProvenance = RenameProvenance(
        candidateId = entryId,
        objectType = objectType,
        fromPath = listOf(fromName),
        toPath = listOf(toName),
        overlaySource = source,
        overlayEntryId = entryId,
        overlayHash = overlayHash,
        fallbackReason = rationale,
    )

    private fun TriggerRenameMapping.toProvenance(
        objectType: DiffObjectType,
        rationale: String,
    ): RenameProvenance = RenameProvenance(
        candidateId = entryId,
        objectType = objectType,
        fromPath = listOf(table, fromName),
        toPath = listOf(table, toName),
        overlaySource = source,
        overlayEntryId = entryId,
        overlayHash = overlayHash,
        fallbackReason = rationale,
    )

    private fun RoutineRenameMapping.toRoutineProvenance(
        objectType: DiffObjectType,
        parameters: List<ParameterDefinition>,
        rationale: String,
    ): RenameProvenance = RenameProvenance(
        candidateId = entryId,
        objectType = objectType,
        fromPath = listOf(ObjectKeyCodec.routineKey(fromName, parameters)),
        toPath = listOf(ObjectKeyCodec.routineKey(toName, parameters)),
        overlaySource = source,
        overlayEntryId = entryId,
        overlayHash = overlayHash,
        fallbackReason = rationale,
    )

    @Suppress("LongParameterList")
    private fun blockedDiagnostic(
        kind: String,
        from: String,
        to: String,
        source: String,
        entryId: String,
        support: RenameSupport.Blocked,
    ): DiffDiagnostic = DiffDiagnostic(
        code = support.code,
        message = "Rename mapping $source entry=$entryId for $kind '$from' -> '$to' is blocked: ${support.message}",
        severity = DiffDiagnostic.Severity.BLOCKER,
    )

    /** Per-call accumulator so each `foldRename*` stays single-pass and locally scoped. */
    private class FoldState {
        private val absorbedFrom = mutableSetOf<String>()
        private val absorbedTo = mutableSetOf<String>()
        private val fallbackFrom = mutableMapOf<String, RenameProvenance>()
        private val fallbackTo = mutableMapOf<String, RenameProvenance>()

        fun absorb(from: String, to: String) {
            absorbedFrom += from
            absorbedTo += to
        }

        fun fallback(from: String, to: String, provenance: RenameProvenance) {
            fallbackFrom[from] = provenance
            fallbackTo[to] = provenance
        }

        fun result(): ObjectFoldResult = ObjectFoldResult(
            absorbedFromNames = absorbedFrom,
            absorbedToNames = absorbedTo,
            fallbackByFromName = fallbackFrom,
            fallbackByToName = fallbackTo,
        )
    }
}
