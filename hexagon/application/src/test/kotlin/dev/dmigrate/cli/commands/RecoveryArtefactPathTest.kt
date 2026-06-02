package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Instant

class RecoveryArtefactPathTest : FunSpec({

    val instant = Instant.parse("2026-05-10T14:30:45Z")

    test("timestamp uses compact ISO 8601 basic UTC form") {
        RecoveryArtefactPath.timestamp(instant) shouldBe "20260510T143045Z"
    }

    test("timestamp has no colons or spaces (filesystem-safe on every OS)") {
        val ts = RecoveryArtefactPath.timestamp(instant)
        ts.contains(':') shouldBe false
        ts.contains(' ') shouldBe false
        ts.contains('/') shouldBe false
    }

    test("timestamp is sortable lexicographically") {
        val earlier = Instant.parse("2026-05-09T23:59:59Z")
        val later = Instant.parse("2026-05-10T00:00:00Z")
        // Lexicographic ordering must agree with chronological ordering.
        (RecoveryArtefactPath.timestamp(earlier) < RecoveryArtefactPath.timestamp(later)) shouldBe true
    }

    test("recoveryPathFor literal-appends suffix to keep the original path untouched") {
        val original = Path.of("/etc/dm/rollback.sql")
        val recovery = RecoveryArtefactPath.recoveryPathFor(original, instant)
        recovery shouldBe Path.of("/etc/dm/rollback.sql.recovery.20260510T143045Z.rollback.sql")
    }

    test("recoveryPathFor preserves the parent directory") {
        val original = Path.of("/var/lib/dmigrate/runs/up-2025q4/down.sql")
        val recovery = RecoveryArtefactPath.recoveryPathFor(original, instant)
        recovery.parent shouldBe Path.of("/var/lib/dmigrate/runs/up-2025q4")
    }

    test("recoveryPathFor handles a relative path without a parent component") {
        val original = Path.of("rollback.sql")
        val recovery = RecoveryArtefactPath.recoveryPathFor(original, instant)
        recovery shouldBe Path.of("./rollback.sql.recovery.20260510T143045Z.rollback.sql")
    }

    test("recoveryPathFor handles a path without a recognisable extension") {
        // The literal-append rule means we don't try to be clever about
        // stripping/replacing extensions; the result is unambiguous and
        // the original path is provably never touched.
        val original = Path.of("/srv/migrations/down")
        val recovery = RecoveryArtefactPath.recoveryPathFor(original, instant)
        recovery shouldBe Path.of("/srv/migrations/down.recovery.20260510T143045Z.rollback.sql")
    }

    test("recoveryPathFor never collides with the original --rollback-output") {
        // Pin the contract: regardless of the input filename, the recovery
        // path differs by at least the `.recovery.<ts>.rollback.sql` infix.
        val candidates = listOf(
            Path.of("/x/y.sql"),
            Path.of("/x/y.rollback.sql"),
            Path.of("/x/y.recovery.20260101T000000Z.rollback.sql"), // already-recovery-shaped input
            Path.of("z"),
        )
        for (original in candidates) {
            val recovery = RecoveryArtefactPath.recoveryPathFor(original, instant)
            (recovery == original) shouldBe false
        }
    }
})
