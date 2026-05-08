package dev.dmigrate.server.application.job

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Duration

class JobExecutorConfigTest : FunSpec({

    test("Sync companion default is the Sync object") {
        JobExecutorConfig.SYNC_DEFAULT shouldBeSameInstanceAs JobExecutorConfig.Sync
    }

    test("Async default reflects LF-012 / LN-011 / LN-017 / LN-027: fixed 4 threads, 1024 queue, 1s retry") {
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

    context("Async validation (Plan LF-012 / LN-011 / LN-017 / LN-027 §3.4 + §10 Q1)") {

        test("coreThreads <= 0 wirft IllegalArgumentException") {
            val ex = shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(coreThreads = 0, maxThreads = 0, queueCapacity = 1)
            }
            ex.message shouldContain "coreThreads"
        }

        test("coreThreads negativ wirft IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(coreThreads = -1, maxThreads = -1, queueCapacity = 1)
            }
        }

        test("maxThreads < coreThreads wirft IllegalArgumentException") {
            val ex = shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(coreThreads = 4, maxThreads = 2, queueCapacity = 1)
            }
            ex.message shouldContain "maxThreads"
        }

        test("queueCapacity = 0 wirft IllegalArgumentException (ArrayBlockingQueue-Constraint)") {
            val ex = shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(coreThreads = 1, maxThreads = 1, queueCapacity = 0)
            }
            ex.message shouldContain "queueCapacity"
            ex.message shouldContain "ArrayBlockingQueue"
        }

        test("queueCapacity negativ wirft IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(coreThreads = 1, maxThreads = 1, queueCapacity = -5)
            }
        }

        test("keepAliveSeconds negativ wirft IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(
                    coreThreads = 1,
                    maxThreads = 1,
                    queueCapacity = 1,
                    keepAliveSeconds = -1,
                )
            }
        }

        test("retryAfter negativ wirft IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(
                    coreThreads = 1,
                    maxThreads = 1,
                    queueCapacity = 1,
                    retryAfter = Duration.ofMillis(-100),
                )
            }
        }

        test("shutdownTimeout negativ wirft IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(
                    coreThreads = 1,
                    maxThreads = 1,
                    queueCapacity = 1,
                    shutdownTimeout = Duration.ofSeconds(-1),
                )
            }
        }

        test("threadNamePrefix blank wirft IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                JobExecutorConfig.Async(
                    coreThreads = 1,
                    maxThreads = 1,
                    queueCapacity = 1,
                    threadNamePrefix = "  ",
                )
            }
        }

        test("Defaults sind valide; coreThreads = maxThreads zulaessig") {
            // No throw — happy path.
            JobExecutorConfig.Async(coreThreads = 4, maxThreads = 4, queueCapacity = 1)
            JobExecutorConfig.Async.DEFAULT
        }

        test("retryAfter und shutdownTimeout = 0 sind erlaubt (Grenzwert)") {
            JobExecutorConfig.Async(
                coreThreads = 1,
                maxThreads = 1,
                queueCapacity = 1,
                retryAfter = Duration.ZERO,
                shutdownTimeout = Duration.ZERO,
            )
        }
    }
})
