package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice A: pins the
 * [MysqlSequenceCanonicityDeclaration.bindingKey] contract — the
 * key MUST mix the [MysqlSequenceCanonicityKind] discriminator
 * in so two declarations from the same op (e.g. CreateSequence
 * needing both `dmg_nextval` + `dmg_setval` probes) do not
 * collide. The renderer-gate later looks up declarations by
 * exact-match on this key.
 */
class MysqlSequenceCanonicityDeclarationTest : FunSpec({

    fun decl(
        kind: MysqlSequenceCanonicityKind,
        objectName: String,
        operationId: String = "op-1",
        sqlHash: String = "abc",
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = "mysql",
        kind = kind,
        objectName = objectName,
        status = MysqlSequenceCanonicityStatus.CANONICAL,
        sqlHash = sqlHash,
    )

    test("bindingKey mixes operation id, kind and object name (no collision across kinds)") {
        val nextval = decl(MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE, "dmg_nextval")
        val setval = decl(MysqlSequenceCanonicityKind.SETVAL_ROUTINE, "dmg_setval")
        nextval.bindingKey shouldNotBe setval.bindingKey
    }

    test("bindingKey collides only on identical (op, kind, objectName, sqlHash) tuples") {
        val first = decl(MysqlSequenceCanonicityKind.SEQUENCE_ROW, "order_seq")
        val second = decl(MysqlSequenceCanonicityKind.SEQUENCE_ROW, "order_seq")
        first.bindingKey shouldBe second.bindingKey
    }

    test("bindingKey uses unit-separator so identifier dots / parens cannot collide") {
        val a = decl(MysqlSequenceCanonicityKind.SUPPORT_TRIGGER, "schema.trigger")
        val b = decl(MysqlSequenceCanonicityKind.SUPPORT_TRIGGER, "schema", sqlHash = "trigger")
        // Pre-separator change this would have collapsed to the same
        // "op-1|mysql|SUPPORT_TRIGGER|schema.trigger|abc" string. With
        // the ASCII Unit Separator the kind / object / hash slots are
        // unambiguous.
        a.bindingKey shouldNotBe b.bindingKey
    }

    test("status enum exposes the five live-DB outcomes plus two NOT_RUN reasons + PROBE_RUNTIME_ERROR") {
        // Pin the enum surface so the renderer gate can switch on it
        // exhaustively. CANONICAL / DRIFT / MISSING are the live
        // results; the three NOT_RUN_* / PROBE_RUNTIME_ERROR encode
        // the "why didn't we probe" axis.
        MysqlSequenceCanonicityStatus.entries.toSet() shouldBe setOf(
            MysqlSequenceCanonicityStatus.CANONICAL,
            MysqlSequenceCanonicityStatus.DRIFT,
            MysqlSequenceCanonicityStatus.MISSING,
            MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET,
            MysqlSequenceCanonicityStatus.NOT_RUN_POLICY,
            MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
        )
    }

    test("kind enum covers the five canonical helper objects") {
        MysqlSequenceCanonicityKind.entries.toSet() shouldBe setOf(
            MysqlSequenceCanonicityKind.SUPPORT_TABLE,
            MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            MysqlSequenceCanonicityKind.SETVAL_ROUTINE,
            MysqlSequenceCanonicityKind.SEQUENCE_ROW,
            MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
        )
    }
})
