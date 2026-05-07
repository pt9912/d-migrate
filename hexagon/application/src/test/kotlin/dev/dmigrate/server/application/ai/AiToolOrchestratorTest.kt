package dev.dmigrate.server.application.ai

import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiToolClaimId
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.ai.AiToolScope
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.AiToolOutcomeStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class AiToolOrchestratorTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")
    val payloadFp = "0".repeat(64)
    val outputFp = "a".repeat(64)
    val resultRef = "dmigrate://tenants/acme/artifacts/art-result"

    fun envelope(toolName: String = "procedure_transform_plan") = AiToolEnvelope(
        toolName = toolName,
        tenantId = tenant,
        callerId = alice,
        approvalKey = "key-1",
        payloadFingerprint = payloadFp,
        now = now,
    )

    /**
     * Test-Double: deterministischer In-Memory-Store mit
     * Hooks fuer manipulierte Acquire-Outcomes — hier brauchen wir
     * KEINE komplette Lease-/Reclaim-Logik (die ist in G.6.a
     * abgedeckt). Wir prueffen das Orchestrator-Verhalten gegen
     * jeden Acquire-Outcome-Typ direkt.
     */
    class ScriptedStore(
        private val acquireScript: List<AiToolAcquireOutcome>,
    ) : AiToolOutcomeStore {
        var acquireCalls = 0
        var commitCalls = 0
        var lastCommittedOutcome: AiToolOutcome? = null
        private val cursor = AtomicInteger(0)

        override fun acquire(
            scope: AiToolScope,
            payloadFingerprint: String,
            leaseDuration: Duration,
            now: Instant,
        ): AiToolAcquireOutcome {
            acquireCalls++
            val idx = cursor.getAndIncrement()
            return acquireScript.getOrElse(idx) { acquireScript.last() }
        }

        override fun commit(
            scope: AiToolScope,
            claimId: AiToolClaimId,
            outcome: AiToolOutcome,
            now: Instant,
        ): Boolean {
            commitCalls++
            lastCommittedOutcome = outcome
            return true
        }

        override fun reclaimExpired(now: Instant): Int = 0
    }

    fun acquired() = AiToolAcquireOutcome.Acquired(
        scope = envelope().scope(),
        claimId = AiToolClaimId("c-1"),
        leaseExpiresAt = now.plusSeconds(60),
        attemptCount = 1,
    )

    test("Plan §6 G.6: Existing(Succeeded) -> WireSuccess(replayed=true), work() wird NICHT aufgerufen") {
        val existing = AiToolOutcome.Succeeded(
            scope = envelope().scope(),
            payloadFingerprint = payloadFp,
            resultRef = resultRef,
            outputFingerprint = outputFp,
            providerName = "noop",
            model = "noop:default",
            providerRequestId = null,
            committedAt = now,
        )
        val store = ScriptedStore(listOf(AiToolAcquireOutcome.Existing(envelope().scope(), existing)))
        val orchestrator = AiToolOrchestrator(store)
        val workCalled = AtomicInteger(0)
        val result = orchestrator.dispatch(envelope()) {
            workCalled.incrementAndGet()
            AiToolWorkResult.Succeeded(resultRef, outputFp, "noop", "noop:default", null)
        }
        val success = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireSuccess>()
        success.replayed shouldBe true
        success.resultRef shouldBe resultRef
        workCalled.get() shouldBe 0
        store.commitCalls shouldBe 0
    }

    test("Plan §6 G.6: Existing(FailedTerminal) -> WireFailure(replayed=true), work() wird NICHT aufgerufen") {
        val existing = AiToolOutcome.FailedTerminal(
            scope = envelope().scope(),
            payloadFingerprint = payloadFp,
            toolErrorCode = ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
            scrubbedMessage = "secret pattern detected",
            committedAt = now,
        )
        val store = ScriptedStore(listOf(AiToolAcquireOutcome.Existing(envelope().scope(), existing)))
        val orchestrator = AiToolOrchestrator(store)
        val workCalled = AtomicInteger(0)
        val result = orchestrator.dispatch(envelope()) {
            workCalled.incrementAndGet()
            AiToolWorkResult.Succeeded(resultRef, outputFp, "noop", "noop:default", null)
        }
        val failure = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireFailure>()
        failure.replayed shouldBe true
        failure.toolErrorCode shouldBe ToolErrorCode.PROMPT_HYGIENE_BLOCKED
        failure.retryable shouldBe false
        workCalled.get() shouldBe 0
    }

    test("Plan §6 G.6: InProgress -> WireFailure(OPERATION_TIMEOUT, retryable=true)") {
        val store = ScriptedStore(
            listOf(AiToolAcquireOutcome.InProgress(envelope().scope(), now.plusSeconds(60))),
        )
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            error("work() must not be called")
        }
        val failure = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireFailure>()
        failure.toolErrorCode shouldBe ToolErrorCode.OPERATION_TIMEOUT
        failure.retryable shouldBe true
        failure.replayed shouldBe false
    }

    test("Plan §6 G.6: Conflict -> WireFailure(IDEMPOTENCY_CONFLICT, retryable=false)") {
        val store = ScriptedStore(
            listOf(AiToolAcquireOutcome.Conflict(envelope().scope(), "1".repeat(64))),
        )
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            error("work() must not be called")
        }
        val failure = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireFailure>()
        failure.toolErrorCode shouldBe ToolErrorCode.IDEMPOTENCY_CONFLICT
        failure.retryable shouldBe false
    }

    test("Acquired + work returns Succeeded -> commit Succeeded + WireSuccess(replayed=false)") {
        val store = ScriptedStore(listOf(acquired()))
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            AiToolWorkResult.Succeeded(resultRef, outputFp, "noop", "noop:default", null)
        }
        val success = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireSuccess>()
        success.replayed shouldBe false
        success.resultRef shouldBe resultRef
        success.outputFingerprint shouldBe outputFp
        store.commitCalls shouldBe 1
        store.lastCommittedOutcome.shouldBeInstanceOf<AiToolOutcome.Succeeded>()
    }

    test("Acquired + work returns FailedTerminal -> commit FailedTerminal + WireFailure(retryable=false)") {
        val store = ScriptedStore(listOf(acquired()))
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            AiToolWorkResult.FailedTerminal(
                toolErrorCode = ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
                scrubbedMessage = "secret pattern detected",
            )
        }
        val failure = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireFailure>()
        failure.toolErrorCode shouldBe ToolErrorCode.PROMPT_HYGIENE_BLOCKED
        failure.retryable shouldBe false
        failure.replayed shouldBe false
        store.lastCommittedOutcome.shouldBeInstanceOf<AiToolOutcome.FailedTerminal>()
    }

    test("Acquired + work returns FailedRetryable -> commit FailedRetryable + WireFailure(retryable=true)") {
        val store = ScriptedStore(listOf(acquired()))
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            AiToolWorkResult.FailedRetryable(
                toolErrorCode = ToolErrorCode.OPERATION_TIMEOUT,
                scrubbedMessage = "provider timeout",
            )
        }
        val failure = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireFailure>()
        failure.toolErrorCode shouldBe ToolErrorCode.OPERATION_TIMEOUT
        failure.retryable shouldBe true
        store.lastCommittedOutcome.shouldBeInstanceOf<AiToolOutcome.FailedRetryable>()
    }

    test("Plan §6 G.6: work() throws -> catch + commit FailedTerminal(INTERNAL_AGENT_ERROR)") {
        val store = ScriptedStore(listOf(acquired()))
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            throw IllegalStateException("simulated bug")
        }
        val failure = result.shouldBeInstanceOf<AiToolDispatchOutcome.WireFailure>()
        failure.toolErrorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
        failure.retryable shouldBe false
        // Commit muss laufen, damit der Pending-Claim nicht
        // dauerhaft offen bleibt.
        store.commitCalls shouldBe 1
        val terminal = store.lastCommittedOutcome.shouldBeInstanceOf<AiToolOutcome.FailedTerminal>()
        terminal.toolErrorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
    }

    test("FailedRetryable-Outcome traegt attemptCount aus dem Acquire") {
        val acq = AiToolAcquireOutcome.Acquired(
            scope = envelope().scope(),
            claimId = AiToolClaimId("c-3"),
            leaseExpiresAt = now.plusSeconds(60),
            attemptCount = 3,
        )
        val store = ScriptedStore(listOf(acq))
        val orchestrator = AiToolOrchestrator(store)
        orchestrator.dispatch(envelope()) {
            AiToolWorkResult.FailedRetryable(ToolErrorCode.RATE_LIMITED, "rate limited")
        }
        val retryable = store.lastCommittedOutcome.shouldBeInstanceOf<AiToolOutcome.FailedRetryable>()
        retryable.attemptCount shouldBe 3
    }

    test("Plan §6 G.6 Crash-Pfad: commit==false (Lease verloren) blockiert das Wire-Ergebnis nicht") {
        // Store, dessen commit() false zurueckgibt — simuliert
        // Lease-Verlust an einen Reclaimer.
        val store = object : AiToolOutcomeStore {
            var workCommitted: AiToolOutcome? = null
            override fun acquire(
                scope: AiToolScope, payloadFingerprint: String,
                leaseDuration: Duration, now: Instant,
            ): AiToolAcquireOutcome = acquired()

            override fun commit(
                scope: AiToolScope, claimId: AiToolClaimId,
                outcome: AiToolOutcome, now: Instant,
            ): Boolean {
                workCommitted = outcome
                return false
            }

            override fun reclaimExpired(now: Instant): Int = 0
        }
        val orchestrator = AiToolOrchestrator(store)
        val result = orchestrator.dispatch(envelope()) {
            AiToolWorkResult.Succeeded(resultRef, outputFp, "noop", "noop:default", null)
        }
        // Wire-Ergebnis fliesst weiter, auch wenn der durable
        // Commit nicht gegriffen hat — der Reclaimer hat
        // mittlerweile ein eigenes durables Outcome geschrieben.
        result.shouldBeInstanceOf<AiToolDispatchOutcome.WireSuccess>()
        store.workCommitted.shouldBeInstanceOf<AiToolOutcome.Succeeded>()
    }

    test("AiToolEnvelope-Konstruktor erzwingt Form-Invarianten") {
        try {
            envelope(toolName = "")
            error("expected IllegalArgumentException for blank toolName")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            AiToolEnvelope(
                toolName = "tool", tenantId = tenant, callerId = alice,
                approvalKey = "", payloadFingerprint = payloadFp, now = now,
            )
            error("expected IllegalArgumentException for blank approvalKey")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            AiToolEnvelope(
                toolName = "tool", tenantId = tenant, callerId = alice,
                approvalKey = "k", payloadFingerprint = "shorthex", now = now,
            )
            error("expected IllegalArgumentException for short fingerprint")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
})
