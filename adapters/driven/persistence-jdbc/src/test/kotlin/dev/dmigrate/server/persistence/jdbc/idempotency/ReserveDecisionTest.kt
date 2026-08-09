package dev.dmigrate.server.persistence.jdbc.idempotency

import dev.dmigrate.server.core.approval.ApprovalChallenge
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.IdempotencyState
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Entscheidungstabelle des `reserve`-Pfads — ohne Datenbank.
 *
 * Bis zu dieser Naht war jede dieser Zeilen nur mit laufendem Postgres
 * pruefbar, weil die Entscheidung mit der Ausfuehrung in einer Klasse steckte.
 * Die SQL-Ausfuehrung selbst bleibt integrationsgebunden
 * (`JdbcIdempotencyStoreContractTest`).
 */
private fun sampleChallenge() = ApprovalChallenge(
    approvalRequestId = "req-1",
    correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
    correlationKey = "key-1",
    requiredScopes = setOf("schema:write"),
    reasons = listOf("destructive"),
)

class ReserveDecisionTest : FunSpec({

    val scope = IdempotencyScope(
        tenantId = TenantId("acme"),
        callerId = PrincipalId("svc-migrator"),
        toolName = "schema_compare",
        idempotencyKey = IdempotencyKey("key-1"),
    )
    val now = Instant.parse("2026-08-09T12:00:00Z")
    val future = now.plusSeconds(60)
    val past = now.minusSeconds(1)
    val fingerprint = "fp-abc"

    fun row(
        state: IdempotencyState,
        expiresAt: Instant = future,
        fingerprint: String = "fp-abc",
        resultRef: String? = null,
        challengeJson: String? = null,
        reason: String? = null,
        claimed: Boolean = false,
    ) = ReservationRow(
        state = state,
        claimed = claimed,
        fingerprint = fingerprint,
        expiresAt = expiresAt,
        resultRef = resultRef,
        challengeJson = challengeJson,
        reason = reason,
    )

    fun decide(existing: ReservationRow) = decideReserve(scope, fingerprint, now, existing)

    fun outcomeOf(existing: ReservationRow): IdempotencyReserveOutcome =
        decide(existing).shouldBeInstanceOf<ReserveDecision.Complete>().outcome

    test("abweichender Fingerprint schlaegt jeden Zustand") {
        // Der Konflikt gewinnt auch dort, wo derselbe Zustand mit passendem
        // Fingerprint ein verwertbares Ergebnis liefern wuerde.
        IdempotencyState.entries.forEach { state ->
            val outcome = outcomeOf(
                row(state = state, fingerprint = "fp-anders", resultRef = "r", reason = "x"),
            )
            outcome shouldBe IdempotencyReserveOutcome.Conflict(scope, "fp-anders")
        }
    }

    test("COMMITTED liefert den gespeicherten Result-Ref") {
        outcomeOf(row(IdempotencyState.COMMITTED, resultRef = "job-77")) shouldBe
            IdempotencyReserveOutcome.Committed(scope, "job-77")
    }

    test("DENIED liefert Grund und Ablauf") {
        outcomeOf(row(IdempotencyState.DENIED, reason = "policy")) shouldBe
            IdempotencyReserveOutcome.Denied(scope, future, "policy")
    }

    test("FAILED liefert Grund und Ablauf") {
        outcomeOf(row(IdempotencyState.FAILED, reason = "boom")) shouldBe
            IdempotencyReserveOutcome.Failed(scope, future, "boom")
    }

    test("PENDING mit gueltiger Lease meldet die laufende Reservierung") {
        outcomeOf(row(IdempotencyState.PENDING)) shouldBe
            IdempotencyReserveOutcome.ExistingPending(scope, future)
    }

    test("AWAITING_APPROVAL mit gueltiger Lease ohne Challenge") {
        outcomeOf(row(IdempotencyState.AWAITING_APPROVAL)) shouldBe
            IdempotencyReserveOutcome.AwaitingApproval(scope, future, null)
    }

    test("AWAITING_APPROVAL reicht eine persistierte Challenge durch") {
        val challenge = ApprovalChallengeJson.toJson(sampleChallenge())
        val outcome = outcomeOf(row(IdempotencyState.AWAITING_APPROVAL, challengeJson = challenge))
            .shouldBeInstanceOf<IdempotencyReserveOutcome.AwaitingApproval>()
        outcome.challenge shouldBe sampleChallenge()
    }

    test("abgelaufene PENDING-Lease wird uebernommen") {
        decide(row(IdempotencyState.PENDING, expiresAt = past)) shouldBe
            ReserveDecision.RecoverExpired
    }

    test("abgelaufene AWAITING_APPROVAL-Lease wird uebernommen") {
        decide(row(IdempotencyState.AWAITING_APPROVAL, expiresAt = past)) shouldBe
            ReserveDecision.RecoverExpired
    }

    test("die Lease-Grenze ist exklusiv — genau jetzt ist abgelaufen") {
        // `expiresAt.isAfter(now)`: Gleichstand zaehlt als abgelaufen. Diese
        // Grenze deckt sich mit dem `expires_at <= ?` der Recovery-CAS; liefen
        // beide auseinander, entschiede der Code Uebernahme und das UPDATE
        // faende keine Zeile.
        decide(row(IdempotencyState.PENDING, expiresAt = now)) shouldBe
            ReserveDecision.RecoverExpired
        outcomeOf(row(IdempotencyState.PENDING, expiresAt = now.plusMillis(1)))
            .shouldBeInstanceOf<IdempotencyReserveOutcome.ExistingPending>()
    }

    test("terminale Zustaende ignorieren den Ablauf") {
        // COMMITTED/DENIED/FAILED werden nicht uebernommen, auch wenn die
        // Retention abgelaufen ist — dafuer gibt es cleanupExpired.
        outcomeOf(row(IdempotencyState.COMMITTED, expiresAt = past, resultRef = "r"))
            .shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
        outcomeOf(row(IdempotencyState.DENIED, expiresAt = past, reason = "r"))
            .shouldBeInstanceOf<IdempotencyReserveOutcome.Denied>()
        outcomeOf(row(IdempotencyState.FAILED, expiresAt = past, reason = "r"))
            .shouldBeInstanceOf<IdempotencyReserveOutcome.Failed>()
    }

    test("claimed beeinflusst die reserve-Entscheidung nicht") {
        // Das Flag gehoert zum Claim-Pfad. Waere es hier wirksam, koennte eine
        // bereits geclaimte Reservierung ein anderes Ergebnis liefern als
        // dieselbe ungeclaimte — genau das soll nicht passieren.
        IdempotencyState.entries.forEach { state ->
            val geclaimt = row(state, resultRef = "r", reason = "x", claimed = true)
            val ungeclaimt = row(state, resultRef = "r", reason = "x", claimed = false)
            decide(geclaimt) shouldBe decide(ungeclaimt)
        }
    }
})
