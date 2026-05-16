package dev.dmigrate.driver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice F.3: pins the [BodyEmbedding] contract
 * — wire-format version, sealed status invariants, and the
 * `BLOCKED requires reason` / `non-BLOCKED forbids reason` rules
 * per E.1 Plan §1.
 */
class BodyEmbeddingTest : FunSpec({

    test("disabledDefault is the E.1 initial state") {
        val be = BodyEmbedding.disabledDefault()
        be.status shouldBe BodyEmbeddingStatus.DISABLED
        be.version shouldBe "body-embed.v1"
        be.source shouldBe BodyEmbeddingSource.NONE
        be.reason shouldBe null
    }

    test("CURRENT_VERSION pins the wire-format identifier") {
        BodyEmbedding.CURRENT_VERSION shouldBe "body-embed.v1"
    }

    test("BLOCKED requires a non-null reason") {
        shouldThrow<IllegalArgumentException> {
            BodyEmbedding(
                status = BodyEmbeddingStatus.BLOCKED,
                version = "body-embed.v1",
                source = BodyEmbeddingSource.NONE,
                reason = null,
            )
        }
    }

    test("non-BLOCKED rejects a non-null reason") {
        shouldThrow<IllegalArgumentException> {
            BodyEmbedding(
                status = BodyEmbeddingStatus.DISABLED,
                version = "body-embed.v1",
                source = BodyEmbeddingSource.NONE,
                reason = "should not be allowed",
            )
        }
    }

    test("blocked() factory constructs a BLOCKED state with a reason") {
        val be = BodyEmbedding.blocked("no valid pre-body source")
        be.status shouldBe BodyEmbeddingStatus.BLOCKED
        be.reason shouldBe "no valid pre-body source"
        be.version shouldBe "body-embed.v1"
        be.source shouldBe BodyEmbeddingSource.NONE
    }

    test("BodyEmbeddingStatus has exactly the three documented values") {
        BodyEmbeddingStatus.entries.map { it.name } shouldBe listOf("ENABLED", "DISABLED", "BLOCKED")
    }

    test("BodyEmbeddingSource has exactly the three documented values") {
        BodyEmbeddingSource.entries.map { it.name } shouldBe
            listOf("CURRENT_SCHEMA", "DB_READBACK", "NONE")
    }
})
