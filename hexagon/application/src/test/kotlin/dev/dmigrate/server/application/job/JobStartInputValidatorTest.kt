package dev.dmigrate.server.application.job

import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class JobStartInputValidatorTest : FunSpec({

    val acme = Fixtures.tenant("acme")

    fun attempt(
        idempotencyKey: String? = "k1",
        refs: List<RefField> = emptyList(),
    ) = JobStartInputAttempt(
        toolName = "schema_reverse_start",
        callerTenant = acme,
        idempotencyKey = idempotencyKey,
        refs = refs,
    )

    fun connRef(name: String = "connectionId", value: String) =
        RefField(name = name, value = value, expectedKind = ResourceKind.CONNECTIONS)

    test("Happy path mit gueltiger Connection-Ref") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "dmigrate://tenants/acme/connections/conn-1"))),
        )
        outcome shouldBe JobStartInputValidation.Valid
    }

    test("idempotencyKey null -> IdempotencyKeyMissing") {
        val outcome = JobStartInputValidator.validate(attempt(idempotencyKey = null))
        outcome shouldBe JobStartInputValidation.Invalid.IdempotencyKeyMissing
    }

    test("idempotencyKey leer -> IdempotencyKeyMissing") {
        val outcome = JobStartInputValidator.validate(attempt(idempotencyKey = ""))
        outcome shouldBe JobStartInputValidation.Invalid.IdempotencyKeyMissing
    }

    test("idempotencyKey nur whitespace -> IdempotencyKeyMissing") {
        val outcome = JobStartInputValidator.validate(attempt(idempotencyKey = "   "))
        outcome shouldBe JobStartInputValidation.Invalid.IdempotencyKeyMissing
    }

    test("idempotencyKey-Check kommt vor Ref-Checks") {
        val outcome = JobStartInputValidator.validate(
            attempt(
                idempotencyKey = null,
                refs = listOf(connRef(value = "jdbc:postgresql://oops")),
            ),
        )
        outcome shouldBe JobStartInputValidation.Invalid.IdempotencyKeyMissing
    }

    test("Freier JDBC-URL wird abgelehnt (jdbc:postgresql)") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "jdbc:postgresql://localhost/db"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.FreeJdbcUrl>()
        outcome.field shouldBe "connectionId"
    }

    test("Freier JDBC-URL wird abgelehnt (jdbc:mysql)") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(name = "sourceUri", value = "jdbc:mysql://prod"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.FreeJdbcUrl>()
        outcome.field shouldBe "sourceUri"
    }

    test("JDBC-URL-Check kommt vor URI-Syntax-Check") {
        // Beide Checks wuerden treffen — JDBC-URL gewinnt fuer den
        // engsten Fehler (LF-012 / LN-011 / LN-017 / LN-027: "freie JDBC-Strings" explizit
        // genannt).
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "jdbc:sqlite::memory:"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.FreeJdbcUrl>()
    }

    test("Nicht-dmigrate-URI -> InvalidRefSyntax") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "https://example.com/db"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.InvalidRefSyntax>()
        outcome.field shouldBe "connectionId"
        outcome.value shouldBe "https://example.com/db"
    }

    test("Falsches Format (zu wenige Segmente) -> InvalidRefSyntax") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "dmigrate://tenants/acme"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.InvalidRefSyntax>()
    }

    test("Falscher ResourceKind -> WrongRefKind") {
        // Caller liefert eine Job-URI als connectionRef.
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "dmigrate://tenants/acme/jobs/j-1"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.WrongRefKind>()
        outcome.expected shouldBe ResourceKind.CONNECTIONS
        outcome.actual shouldBe ResourceKind.JOBS
    }

    test("Tenant-Prefix-Mismatch -> TenantPrefixMismatch") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(connRef(value = "dmigrate://tenants/initech/connections/conn-1"))),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.TenantPrefixMismatch>()
        outcome.expected shouldBe acme
        outcome.actual shouldBe Fixtures.tenant("initech")
    }

    test("Mehrere Refs: erster Fehler gewinnt deterministisch") {
        // 1. Ref ist gueltig, 2. ist Tenant-Prefix-Mismatch, 3. ist
        // freier JDBC. Erwartung: Tenant-Prefix-Mismatch der 2. Ref.
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(
                connRef(name = "f1", value = "dmigrate://tenants/acme/connections/c1"),
                connRef(name = "f2", value = "dmigrate://tenants/initech/connections/c2"),
                connRef(name = "f3", value = "jdbc:postgresql://oops"),
            )),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.TenantPrefixMismatch>()
        outcome.field shouldBe "f2"
    }

    test("Schema-Ref mit expectedKind=SCHEMAS akzeptiert Schema-URI") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(
                RefField("schemaRef", "dmigrate://tenants/acme/schemas/sch-1", ResourceKind.SCHEMAS),
            )),
        )
        outcome shouldBe JobStartInputValidation.Valid
    }

    test("Schema-Ref mit expectedKind=SCHEMAS lehnt Connection-URI ab") {
        val outcome = JobStartInputValidator.validate(
            attempt(refs = listOf(
                RefField("schemaRef", "dmigrate://tenants/acme/connections/c1", ResourceKind.SCHEMAS),
            )),
        )
        outcome.shouldBeInstanceOf<JobStartInputValidation.Invalid.WrongRefKind>()
        outcome.expected shouldBe ResourceKind.SCHEMAS
        outcome.actual shouldBe ResourceKind.CONNECTIONS
    }
})
