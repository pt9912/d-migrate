package dev.dmigrate.server.persistence.jdbc.idempotency

import dev.dmigrate.server.core.approval.ApprovalChallenge
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.IdempotencyState
import dev.dmigrate.server.core.idempotency.InitResumeOutcome
import dev.dmigrate.server.core.idempotency.InitResumeScope
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

/**
 * Entscheidungstabelle des `claimApproved`-Pfads — ohne Datenbank.
 */
class ClaimDecisionTest : FunSpec({

    val scope = IdempotencyScope(
        tenantId = TenantId("acme"),
        callerId = PrincipalId("svc-migrator"),
        toolName = "schema_compare",
        idempotencyKey = IdempotencyKey("key-1"),
    )
    val now = Instant.parse("2026-08-09T12:00:00Z")
    val future = now.plusSeconds(60)
    val past = now.minusSeconds(1)

    fun row(
        state: IdempotencyState,
        expiresAt: Instant = future,
        resultRef: String? = null,
        reason: String? = null,
        claimed: Boolean = false,
    ) = ReservationRow(
        state = state,
        claimed = claimed,
        fingerprint = "fp-abc",
        expiresAt = expiresAt,
        resultRef = resultRef,
        challengeJson = null,
        reason = reason,
    )

    fun outcomeOf(existing: ReservationRow?) =
        decideClaim(scope, now, existing)
            .shouldBeInstanceOf<ClaimDecision.Complete>().outcome

    test("fehlende Zeile ist kein Fehler, sondern NotAwaitingApproval") {
        outcomeOf(null) shouldBe IdempotencyClaimOutcome.NotAwaitingApproval(scope)
    }

    test("gueltige Freigabe wird eingeloest") {
        decideClaim(scope, now, row(IdempotencyState.AWAITING_APPROVAL)) shouldBe
            ClaimDecision.TransitionToClaimed
    }

    test("abgelaufene Freigabe wird NICHT eingeloest") {
        // Sonst liefe die Claim-CAS (`expires_at > ?`) ins Leere und der
        // check(updated == 1) in transitionToClaimed wuerde werfen.
        outcomeOf(row(IdempotencyState.AWAITING_APPROVAL, expiresAt = past)) shouldBe
            IdempotencyClaimOutcome.NotAwaitingApproval(scope)
    }

    test("die Freigabe-Grenze ist exklusiv — genau jetzt ist abgelaufen") {
        outcomeOf(row(IdempotencyState.AWAITING_APPROVAL, expiresAt = now))
            .shouldBeInstanceOf<IdempotencyClaimOutcome.NotAwaitingApproval>()
        decideClaim(scope, now, row(IdempotencyState.AWAITING_APPROVAL, expiresAt = now.plusMillis(1))) shouldBe
            ClaimDecision.TransitionToClaimed
    }

    test("nur eine bereits geclaimte PENDING-Zeile ist ein wiederholter Claim") {
        outcomeOf(row(IdempotencyState.PENDING, claimed = true)) shouldBe
            IdempotencyClaimOutcome.AlreadyClaimed(scope, future)
        outcomeOf(row(IdempotencyState.PENDING, claimed = false)) shouldBe
            IdempotencyClaimOutcome.NotAwaitingApproval(scope)
    }

    test("COMMITTED und DENIED liefern ihr terminales Ergebnis") {
        outcomeOf(row(IdempotencyState.COMMITTED, resultRef = "job-9")) shouldBe
            IdempotencyClaimOutcome.Committed(scope, "job-9")
        outcomeOf(row(IdempotencyState.DENIED, reason = "policy")) shouldBe
            IdempotencyClaimOutcome.Denied(scope, future, "policy")
    }

    test("FAILED ist nicht claimbar") {
        outcomeOf(row(IdempotencyState.FAILED, reason = "boom")) shouldBe
            IdempotencyClaimOutcome.NotAwaitingApproval(scope)
    }

    test("der Fingerprint spielt beim Claim keine Rolle") {
        // Anders als bei reserve: geclaimt wird gegen eine bestehende Freigabe,
        // nicht gegen einen erneut eingereichten Payload.
        val a = row(IdempotencyState.AWAITING_APPROVAL).copy(fingerprint = "fp-1")
        val b = row(IdempotencyState.AWAITING_APPROVAL).copy(fingerprint = "fp-2")
        decideClaim(scope, now, a) shouldBe decideClaim(scope, now, b)
    }
})

/**
 * Init-Resume-Entscheidung und Retention-Regel — beide ohne Datenbank.
 */
class InitResumeAndRetentionTest : FunSpec({

    val scope = InitResumeScope(
        tenantId = TenantId("acme"),
        callerId = PrincipalId("svc-migrator"),
        toolName = "schema_compare",
        clientRequestId = "req-1",
    )
    val now = Instant.parse("2026-08-09T12:00:00Z")
    val expires = now.plusSeconds(600)

    test("gleicher Fingerprint liefert dieselbe Session zurueck") {
        decideInitResume(scope, "fp-1", InitResumeRow("sess-1", "fp-1", expires)) shouldBe
            InitResumeOutcome.Existing(scope, "sess-1", expires)
    }

    test("abweichender Fingerprint ist ein Konflikt") {
        decideInitResume(scope, "fp-2", InitResumeRow("sess-1", "fp-1", expires)) shouldBe
            InitResumeOutcome.Conflict(scope, "fp-1")
    }

    test("terminalExpiry: ohne Wunsch gilt der Default") {
        terminalExpiry(now, defaultSeconds = 600, retentionUntil = null) shouldBe now.plusSeconds(600)
    }

    test("terminalExpiry: eine spaetere Retention gewinnt") {
        val laenger = now.plusSeconds(86_400)
        terminalExpiry(now, defaultSeconds = 600, retentionUntil = laenger) shouldBe laenger
    }

    test("terminalExpiry: eine fruehere Retention kann nicht verkuerzen") {
        // Sonst koennte ein Aufrufer die Aufbewahrung unter das Minimum druecken
        // und terminale Ergebnisse frueher verschwinden lassen, als der Vertrag
        // sie zusichert.
        terminalExpiry(now, defaultSeconds = 600, retentionUntil = now.plusSeconds(60)) shouldBe
            now.plusSeconds(600)
    }

    test("terminalExpiry: Gleichstand zaehlt nicht als spaeter") {
        val gleich = now.plusSeconds(600)
        terminalExpiry(now, defaultSeconds = 600, retentionUntil = gleich) shouldBe gleich
    }
})
