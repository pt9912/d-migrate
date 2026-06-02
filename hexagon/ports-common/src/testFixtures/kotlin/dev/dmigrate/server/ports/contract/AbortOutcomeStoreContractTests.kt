package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.upload.AbortOutcome
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.AbortOutcomeStore
import dev.dmigrate.server.ports.AbortOutcomeStore.SaveOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — Contract-Tests fuer Implementoren von
 * [AbortOutcomeStore]. Pin't:
 *
 * 1. Erster Save unter neuem `resultRef` -> `Stored`.
 * 2. Zweiter Save mit gleichem `resultRef` UND gleichem
 *    `abortFingerprint` -> `AlreadyStored` (Replay-Idempotenz).
 * 3. Zweiter Save mit gleichem `resultRef` aber abweichendem
 *    Fingerprint -> `Conflict` (LF-010 / LF-013 / LN-009 / LN-011 deterministische
 *    Ablehnung).
 * 4. `findByResultRef` liefert die durabel gespeicherte Variante.
 */
abstract class AbortOutcomeStoreContractTests(factory: () -> AbortOutcomeStore) : FunSpec({

    val now: Instant = Instant.parse("2026-05-06T12:00:00Z")

    fun outcome(
        fingerprint: String,
        sessionId: String = "ups-1",
        terminal: UploadSessionState? = UploadSessionState.ABORTED,
        reason: String? = "ops-cleanup",
    ) = AbortOutcome(
        abortFingerprint = fingerprint,
        uploadSessionId = sessionId,
        preAbortState = UploadSessionState.ACTIVE,
        terminalState = terminal,
        quotaReleased = true,
        completedAt = now,
        reason = reason,
    )

    test("erster Save unter neuem resultRef liefert Stored") {
        val store = factory()
        val result = store.save("rr-1", outcome("fp-1"))
        result.shouldBeInstanceOf<SaveOutcome.Stored>()
        result.resultRef shouldBe "rr-1"
        store.findByResultRef("rr-1") shouldBe outcome("fp-1")
    }

    test("zweiter Save mit gleichem resultRef + gleichem Fingerprint -> AlreadyStored") {
        val store = factory()
        store.save("rr-2", outcome("fp-2"))
        val replay = store.save("rr-2", outcome("fp-2"))
        val already = replay.shouldBeInstanceOf<SaveOutcome.AlreadyStored>()
        already.existing.abortFingerprint shouldBe "fp-2"
    }

    test("zweiter Save mit gleichem resultRef aber abweichendem Fingerprint -> Conflict") {
        val store = factory()
        store.save("rr-3", outcome("fp-orig"))
        val attempt = store.save("rr-3", outcome("fp-different"))
        val conflict = attempt.shouldBeInstanceOf<SaveOutcome.Conflict>()
        conflict.existingFingerprint shouldBe "fp-orig"
        conflict.attemptedFingerprint shouldBe "fp-different"

        // LF-010 / LF-013 / LN-009 / LN-011: bei Conflict bleibt der gespeicherte Outcome
        // unangetastet — kein silent overwrite.
        store.findByResultRef("rr-3")!!.abortFingerprint shouldBe "fp-orig"
    }

    test("findByResultRef fuer unbekannten resultRef liefert null") {
        val store = factory()
        store.findByResultRef("rr-unknown") shouldBe null
    }

    test("verschiedene resultRefs sind unabhaengig voneinander adressierbar") {
        val store = factory()
        store.save("rr-a", outcome("fp-a", sessionId = "ups-a"))
        store.save("rr-b", outcome("fp-b", sessionId = "ups-b"))
        store.findByResultRef("rr-a")!!.uploadSessionId shouldBe "ups-a"
        store.findByResultRef("rr-b")!!.uploadSessionId shouldBe "ups-b"
    }
})
