package dev.dmigrate.server.core.job

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JobVisibilityTest : FunSpec({

    test("has exactly the three documented values") {
        JobVisibility.entries.toSet() shouldBe setOf(
            JobVisibility.OWNER,
            JobVisibility.TENANT,
            JobVisibility.ADMIN,
        )
    }

    test("JobStatus has the five lifecycle values") {
        JobStatus.entries.toSet() shouldBe setOf(
            JobStatus.QUEUED,
            JobStatus.RUNNING,
            JobStatus.SUCCEEDED,
            JobStatus.FAILED,
            JobStatus.CANCELLED,
        )
    }

    test("JobStatus terminal flag matches contract") {
        JobStatus.QUEUED.terminal shouldBe false
        JobStatus.RUNNING.terminal shouldBe false
        JobStatus.SUCCEEDED.terminal shouldBe true
        JobStatus.FAILED.terminal shouldBe true
        JobStatus.CANCELLED.terminal shouldBe true
    }
})
