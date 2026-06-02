package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import dev.dmigrate.driver.migration.MigrationBlockedReason

internal object PostgresUsingOverlayResolver {

    fun resolve(op: DiffOperation.AlterColumnType, ctx: PostgresDiffRenderContext): String? {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val sourceType = ctx.sql.toSql(op.before)
        val targetType = ctx.sql.toSql(op.after)
        val candidates = matchingCandidates(ctx.migrationOverlays, table, column, sourceType, targetType)

        if (candidates.isEmpty()) {
            return block(
                op = op,
                ctx = ctx,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                code = "PG_USING_OVERLAY_MISSING",
                message = "AlterColumnType ${op.objectRef.displayName} from $sourceType to $targetType requires " +
                    "a valid using-expression migration overlay.",
            )
        }
        if (candidates.size > 1) {
            return block(
                op = op,
                ctx = ctx,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                code = "PG_USING_OVERLAY_AMBIGUOUS",
                message = "Multiple using-expression overlays match ${op.objectRef.displayName}; provide exactly one.",
            )
        }

        val (document, entry) = candidates.single()
        if (!validateOverlayContract(document, entry, op, ctx)) return null
        return expressionForDirection(op, ctx, document.source, document.overlay.overlayHash, entry)
    }

    private fun validateOverlayContract(
        document: MigrationOverlayDocument,
        entry: UsingExpressionOverlayEntry,
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
    ): Boolean {
        val sourceFingerprint = ctx.sourceFingerprint
        val targetFingerprint = ctx.targetFingerprint
        if (sourceFingerprint.isNullOrBlank() || targetFingerprint.isNullOrBlank()) {
            block(
                op = op,
                ctx = ctx,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                code = "PG_USING_OVERLAY_FINGERPRINTS_MISSING",
                message = "Using-expression overlays require source and target fingerprints before render.",
            )
            return false
        }

        val result = MigrationOverlayValidator.validate(
            overlay = document.overlay,
            context = MigrationOverlayValidationContext(
                expectedSourceFingerprint = sourceFingerprint,
                expectedTargetFingerprint = targetFingerprint,
                expectedDialect = "postgresql",
                supportedOverlayKinds = setOf(MigrationOverlayKinds.USING_EXPRESSION),
            ),
            source = document.source,
        )
        if (!result.hasBlockers) return true

        for (diagnostic in result.diagnostics) {
            ctx.addDiagnostic(
                code = diagnostic.code,
                operationId = op.id,
                message = "Using overlay source=${result.source} entry=${diagnostic.entryId ?: entry.id} " +
                    "hash=${diagnostic.overlayHash}: ${diagnostic.message}",
                severity = DiffDiagnostic.Severity.BLOCKER,
            )
        }
        ctx.skip(op, "Using-expression overlay ${document.source} does not satisfy the F.0 contract.")
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return false
    }

    private fun expressionForDirection(
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
        source: String,
        overlayHash: String?,
        entry: UsingExpressionOverlayEntry,
    ): String? {
        val expression = if (ctx.direction == PostgresRenderDirection.UP) {
            entry.upUsingExpression
        } else {
            entry.downUsingExpression ?: return blockMissingDown(op, ctx, source, entry)
        }
        if (expression.secret) {
            return block(
                op = op,
                ctx = ctx,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                code = "PG_USING_OVERLAY_SECRET_EXPRESSION",
                message = "Using overlay source=$source entry=${entry.id} marks the selected expression as secret; " +
                    "secret overlay values cannot be copied into rendered SQL artifacts.",
            )
        }
        if (entry.expressionSource !in ALLOWED_EXPRESSION_SOURCES) {
            return block(
                op = op,
                ctx = ctx,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                code = "PG_USING_OVERLAY_INVALID_EXPRESSION_SOURCE",
                message = "Using overlay source=$source entry=${entry.id} has unsupported expressionSource " +
                    "'${entry.expressionSource}'. Supported values: ${ALLOWED_EXPRESSION_SOURCES.sorted().joinToString(", ")}.",
            )
        }
        val error = validateExpression(expression.value, entry.column)
        if (error != null) {
            return block(
                op = op,
                ctx = ctx,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                code = "PG_USING_OVERLAY_INVALID_EXPRESSION",
                message = "Using overlay source=$source entry=${entry.id} is not a single-column PostgreSQL " +
                    "expression: $error",
            )
        }

        val downStatus = when {
            entry.downUsingExpression != null -> "EXPLICIT"
            else -> entry.conversionReversibility.name
        }
        ctx.addInfoDiagnostic(
            code = "PG_USING_OVERLAY_APPLIED",
            operationId = op.id,
            message = "Using overlay source=$source entry=${entry.id} hash=$overlayHash " +
                "dataRisk=${entry.dataRisk.name} downStatus=$downStatus expressionSource=${entry.expressionSource}",
        )
        return expression.value
    }

    private fun matchingCandidates(
        documents: List<MigrationOverlayDocument>,
        table: String,
        column: String,
        sourceType: String,
        targetType: String,
    ): List<Pair<MigrationOverlayDocument, UsingExpressionOverlayEntry>> =
        documents.flatMap { doc ->
            doc.overlay.entries
                .filterIsInstance<UsingExpressionOverlayEntry>()
                .filter {
                    it.table == table &&
                        it.column == column &&
                        canonicalType(it.sourceType) == canonicalType(sourceType) &&
                        canonicalType(it.targetType) == canonicalType(targetType)
                }
                .map { doc to it }
        }

    private fun blockMissingDown(
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
        source: String,
        entry: UsingExpressionOverlayEntry,
    ): String? {
        val reason = when (entry.conversionReversibility) {
            MigrationOverlayConversionReversibility.NOT_REVERSIBLE -> MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
            MigrationOverlayConversionReversibility.MANUAL_REQUIRED -> MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            MigrationOverlayConversionReversibility.AUTOMATIC -> MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        }
        return block(
            op = op,
            ctx = ctx,
            reason = reason,
            code = "PG_USING_OVERLAY_DOWN_MISSING",
            message = "Using overlay source=$source entry=${entry.id} has no explicit downUsingExpression; " +
                "down render status is ${entry.conversionReversibility.name}.",
        )
    }

    private fun validateExpression(expression: String, column: String): String? {
        val trimmed = expression.trim()
        if (trimmed.isBlank()) return "expression is blank"
        if (containsStatementSeparator(trimmed)) {
            return "statement separators and comments are not allowed"
        }
        val quotedIdentifiers = Regex("\"((?:\"\"|[^\"])*)\"").findAll(trimmed).map { it.groupValues[1] }.toList()
        val otherQuoted = quotedIdentifiers.firstOrNull { it.replace("\"\"", "\"") != column }
        if (otherQuoted != null) return "quoted identifier \"$otherQuoted\" is not the target column"

        val withoutStrings = trimmed
            .replace(Regex("'(?:''|[^'])*'"), "''")
            .replace(Regex("\"((?:\"\"|[^\"])*)\""), column)
        val identifiers = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(withoutStrings)
            .map { it.value }
            .toList()
        if (identifiers.none { it == column }) return "expression must reference only the target column"

        val allowed = allowedIdentifiers(column)
        val unexpected = identifiers.firstOrNull { it.uppercase() !in allowed }
        return unexpected?.let { "identifier `$it` is not allowed in this first-slice expression" }
    }

    private fun allowedIdentifiers(column: String): Set<String> =
        setOf(
            column.uppercase(),
            "SMALLINT",
            "INTEGER",
            "BIGINT",
            "TEXT",
            "VARCHAR",
            "CHAR",
            "BOOLEAN",
            "DATE",
            "TIME",
            "TIMESTAMP",
            "UUID",
            "JSONB",
            "JSON",
            "XML",
            "BYTEA",
            "NULL",
            "TRUE",
            "FALSE",
            "CAST",
            "AS",
        )

    private fun containsStatementSeparator(expression: String): Boolean =
        ';' in expression ||
            "--" in expression ||
            "/*" in expression ||
            "*/" in expression

    private fun canonicalType(type: String): String =
        type.trim().replace(Regex("\\s+"), " ").uppercase()

    private val ALLOWED_EXPRESSION_SOURCES: Set<String> = setOf(
        "user",
        "reviewed-user",
        "migration-overlay",
    )

    private fun block(
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
        reason: MigrationBlockedReason,
        code: String,
        message: String,
    ): String? {
        ctx.skip(op, message, code)
        ctx.addBlocker(reason, operationIds = setOf(op.id))
        return null
    }
}
