package dev.dmigrate.server.core.upload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UploadSessionTransitionsTest : FunSpec({

    test("ACTIVE may transition to COMPLETED, ABORTED, EXPIRED") {
        UploadSessionTransitions.isAllowed(UploadSessionState.ACTIVE, UploadSessionState.COMPLETED) shouldBe true
        UploadSessionTransitions.isAllowed(UploadSessionState.ACTIVE, UploadSessionState.ABORTED) shouldBe true
        UploadSessionTransitions.isAllowed(UploadSessionState.ACTIVE, UploadSessionState.EXPIRED) shouldBe true
    }

    test("ACTIVE cannot transition to itself") {
        UploadSessionTransitions.isAllowed(UploadSessionState.ACTIVE, UploadSessionState.ACTIVE) shouldBe false
    }

    test("terminal states accept no further transitions") {
        listOf(
            UploadSessionState.COMPLETED,
            UploadSessionState.ABORTED,
            UploadSessionState.EXPIRED,
        ).forEach { from ->
            UploadSessionState.entries.forEach { to ->
                UploadSessionTransitions.isAllowed(from, to) shouldBe false
            }
        }
    }

    test("only ACTIVE accepts segments") {
        UploadSessionTransitions.acceptsSegments(UploadSessionState.ACTIVE) shouldBe true
        UploadSessionTransitions.acceptsSegments(UploadSessionState.COMPLETED) shouldBe false
        UploadSessionTransitions.acceptsSegments(UploadSessionState.ABORTED) shouldBe false
        UploadSessionTransitions.acceptsSegments(UploadSessionState.EXPIRED) shouldBe false
    }

    test("only ACTIVE allows resume") {
        UploadSessionTransitions.canResume(UploadSessionState.ACTIVE) shouldBe true
        UploadSessionTransitions.canResume(UploadSessionState.COMPLETED) shouldBe false
        UploadSessionTransitions.canResume(UploadSessionState.ABORTED) shouldBe false
        UploadSessionTransitions.canResume(UploadSessionState.EXPIRED) shouldBe false
    }

    test("UploadSessionState marks terminal flags correctly") {
        UploadSessionState.ACTIVE.terminal shouldBe false
        UploadSessionState.COMPLETED.terminal shouldBe true
        UploadSessionState.ABORTED.terminal shouldBe true
        UploadSessionState.EXPIRED.terminal shouldBe true
    }
})
