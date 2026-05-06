package dev.dmigrate.server.application.job

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class JobExecutorLifecycleTest : FunSpec({

    test("SyncExecutorLifecycle.status() ist eine Null-Snapshot ohne Telemetrie") {
        val snap = SyncExecutorLifecycle.status()
        snap shouldBe JobExecutorStatus(
            active = 0L,
            queued = 0L,
            completed = 0L,
            rejected = 0L,
            capacity = 0L,
        )
    }

    test("SyncExecutorLifecycle.shutdown(any) liefert sofort true") {
        SyncExecutorLifecycle.shutdown(Duration.ZERO) shouldBe true
        SyncExecutorLifecycle.shutdown(Duration.ofSeconds(60)) shouldBe true
    }
})
