package dev.dmigrate.server.application.job

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Phase E §7.6 Pre-Idempotency-Validation-Layer.
 *
 * Tool-Handler aus AP E.6 (3/4) rufen [validate] VOR jedem Idempotency-/
 * Policy-/Quota-Store-Write auf. Bei [JobStartInputValidation.Invalid]
 * antwortet der Handler mit einem strukturellen Fehler-Envelope und
 * schreibt NICHTS in die Stores (Plan §7.6 line 1118-1121).
 *
 * Ziele der Pflichtchecks:
 *
 * - `idempotencyKey` muss vorhanden und non-blank sein (Plan §7.6
 *   "idempotencyKey als Pflichtfeld erzwingen").
 * - Connection-/Schema-Refs muessen im tenant-scoped
 *   `dmigrate://tenants/<tenant>/<kind>/<id>`-Format vorliegen
 *   (Plan §7.6 "syntaktische Resource-URI-Form, Tenant-Prefix").
 * - Freie JDBC-URLs (`jdbc:<driver>:...`) werden hart abgelehnt
 *   (Plan §7.6 "freie JDBC-Strings").
 *
 * Die Pruefreihenfolge ist deterministisch: erst Idempotency, dann pro
 * Ref der Reihe nach JDBC-URL → Syntax → Kind → Tenant-Prefix. Damit
 * sehen Caller bei mehreren gleichzeitigen Verstoessen den engsten
 * Fehler zuerst.
 */
object JobStartInputValidator {

    fun validate(attempt: JobStartInputAttempt): JobStartInputValidation {
        if (attempt.idempotencyKey.isNullOrBlank()) {
            return JobStartInputValidation.Invalid.IdempotencyKeyMissing
        }
        for (ref in attempt.refs) {
            val outcome = validateRef(ref, attempt.callerTenant)
            if (outcome !is JobStartInputValidation.Valid) return outcome
        }
        return JobStartInputValidation.Valid
    }

    private fun validateRef(
        ref: RefField,
        callerTenant: TenantId,
    ): JobStartInputValidation {
        if (ref.value.startsWith(JDBC_URL_PREFIX)) {
            return JobStartInputValidation.Invalid.FreeJdbcUrl(ref.name)
        }
        return when (val parsed = ServerResourceUri.parse(ref.value)) {
            is ResourceUriParseResult.Invalid ->
                JobStartInputValidation.Invalid.InvalidRefSyntax(ref.name, ref.value, parsed.reason)
            is ResourceUriParseResult.Valid -> when {
                parsed.uri.kind != ref.expectedKind ->
                    JobStartInputValidation.Invalid.WrongRefKind(
                        field = ref.name,
                        expected = ref.expectedKind,
                        actual = parsed.uri.kind,
                    )
                parsed.uri.tenantId != callerTenant ->
                    JobStartInputValidation.Invalid.TenantPrefixMismatch(
                        field = ref.name,
                        expected = callerTenant,
                        actual = parsed.uri.tenantId,
                    )
                else -> JobStartInputValidation.Valid
            }
        }
    }

    private const val JDBC_URL_PREFIX: String = "jdbc:"
}

/**
 * Input-Bundle fuer [JobStartInputValidator.validate]. Tool-Handler
 * komponieren das aus dem geparsten MCP-Argument, der Caller-Identitaet
 * und der pro Tool bekannten Ref-Felder.
 */
data class JobStartInputAttempt(
    val toolName: String,
    val callerTenant: TenantId,
    val idempotencyKey: String?,
    val refs: List<RefField> = emptyList(),
)

/**
 * Eine Ref-Eingabe, die im tenant-scoped `dmigrate://`-Format vorliegen
 * MUSS. Der erwartete [expectedKind] entscheidet, ob zum Beispiel ein
 * `connectionId`-Feld auch wirklich auf eine Connection zeigt und nicht
 * auf einen Job- oder Artefakt-URI.
 */
data class RefField(
    val name: String,
    val value: String,
    val expectedKind: ResourceKind,
)

sealed interface JobStartInputValidation {

    data object Valid : JobStartInputValidation

    sealed interface Invalid : JobStartInputValidation {

        data object IdempotencyKeyMissing : Invalid

        data class FreeJdbcUrl(val field: String) : Invalid

        data class InvalidRefSyntax(
            val field: String,
            val value: String,
            val reason: String,
        ) : Invalid

        data class WrongRefKind(
            val field: String,
            val expected: ResourceKind,
            val actual: ResourceKind,
        ) : Invalid

        data class TenantPrefixMismatch(
            val field: String,
            val expected: TenantId,
            val actual: TenantId,
        ) : Invalid
    }
}
