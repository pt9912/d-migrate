package dev.dmigrate.server.application.job

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Duration

class JobExecutorConfigTest : FunSpec({

    test("Sync companion default is the Sync object") {
        JobExecutorConfig.SYNC_DEFAULT shouldBeSameInstanceAs JobExecutorConfig.Sync
    }

    test("Async default reflects plan §3.2 + §10 Q1: fixed 4 threads, 1024 queue, 1s retry") {
        val cfg = JobExecutorConfig.Async.DEFAULT
        cfg.coreThreads shouldBe 4
        cfg.maxThreads shouldBe 4
        cfg.queueCapacity shouldBe 1024
        cfg.keepAliveSeconds shouldBe 60L
        cfg.retryAfter shouldBe Duration.ofSeconds(1)
        cfg.shutdownTimeout shouldBe Duration.ofSeconds(30)
        cfg.threadNamePrefix shouldBe "d-migrate-worker"
    }

    test("admissionCapacity equals maxThreads + queueCapacity") {
        val cfg = JobExecutorConfig.Async(coreThreads = 2, maxThreads = 8, queueCapacity = 32)
        cfg.admissionCapacity shouldBe 40
    }

    test("custom Async overrides apply") {
        val cfg = JobExecutorConfig.Async(
            coreThreads = 1,
            maxThreads = 1,
            queueCapacity = 4,
            keepAliveSeconds = 5,
            retryAfter = Duration.ofMillis(250),
            shutdownTimeout = Duration.ofSeconds(2),
            threadNamePrefix = "test-worker",
        )
        cfg.coreThreads shouldBe 1
        cfg.queueCapacity shouldBe 4
        cfg.keepAliveSeconds shouldBe 5L
        cfg.retryAfter shouldBe Duration.ofMillis(250)
        cfg.shutdownTimeout shouldBe Duration.ofSeconds(2)
        cfg.threadNamePrefix shouldBe "test-worker"
    }
})
