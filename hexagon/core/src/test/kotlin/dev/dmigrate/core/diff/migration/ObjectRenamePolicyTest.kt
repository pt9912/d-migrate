package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ParameterDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * F.4 Sub-Slice A.2: per-dialect rename classification pins.
 * Covers the three dialect policies (PG/MySQL/SQLite) for every
 * object kind they own, plus the body-drift contract and the
 * materialized-view carve-out.
 */
class ObjectRenamePolicyTest : FunSpec({

    val capsPostgres = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.POSTGRESQL)
    val capsMysql = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.MYSQL)
    val capsSqlite = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.SQLITE)

    fun viewCandidate(materialized: Boolean = false, sourceBody: String? = "h1", targetBody: String? = "h1") =
        ObjectRenameCandidate(
            objectType = DiffObjectType.VIEW,
            fromName = "v_old",
            toName = "v_new",
            materializedView = materialized,
            sourceBodyHash = sourceBody,
            targetBodyHash = targetBody,
        )

    fun triggerCandidate(sourceBody: String? = "h1", targetBody: String? = "h1") =
        ObjectRenameCandidate(
            objectType = DiffObjectType.TRIGGER,
            fromName = "t_old",
            toName = "t_new",
            triggerTableName = "orders",
            sourceBodyHash = sourceBody,
            targetBodyHash = targetBody,
        )

    fun functionCandidate(sourceBody: String? = "h1", targetBody: String? = "h1") =
        ObjectRenameCandidate(
            objectType = DiffObjectType.FUNCTION,
            fromName = "f_old",
            toName = "f_new",
            routineSignature = listOf(ParameterDefinition(name = "n", type = "int")),
            sourceBodyHash = sourceBody,
            targetBodyHash = targetBody,
        )

    fun sequenceCandidate() = ObjectRenameCandidate(
        objectType = DiffObjectType.SEQUENCE,
        fromName = "s_old",
        toName = "s_new",
    )

    // ── PostgreSQL ─────────────────────────────────────────────────

    test("PG: regular view rename is Native") {
        PostgresObjectRenamePolicy.classify(viewCandidate(), capsPostgres) shouldBe RenameSupport.Native
    }

    test("PG: materialized view rename is Blocked with OBJECT_RENAME_UNSUPPORTED") {
        val r = PostgresObjectRenamePolicy.classify(viewCandidate(materialized = true), capsPostgres)
        val blocked = r.shouldBeInstanceOf<RenameSupport.Blocked>()
        blocked.code shouldBe "OBJECT_RENAME_UNSUPPORTED"
        blocked.message.shouldContain("D.3b")
    }

    test("PG: trigger rename with equal body hashes is Native") {
        PostgresObjectRenamePolicy.classify(triggerCandidate(), capsPostgres) shouldBe RenameSupport.Native
    }

    test("PG: trigger rename with body drift is Blocked") {
        val r = PostgresObjectRenamePolicy.classify(
            triggerCandidate(sourceBody = "h1", targetBody = "h2"),
            capsPostgres,
        )
        val blocked = r.shouldBeInstanceOf<RenameSupport.Blocked>()
        blocked.message.shouldContain("Body-drift")
    }

    test("PG: function rename with equal body hashes is Native") {
        PostgresObjectRenamePolicy.classify(functionCandidate(), capsPostgres) shouldBe RenameSupport.Native
    }

    test("PG: function rename with body drift is Blocked") {
        val r = PostgresObjectRenamePolicy.classify(
            functionCandidate(sourceBody = "h1", targetBody = "h2"),
            capsPostgres,
        )
        r.shouldBeInstanceOf<RenameSupport.Blocked>()
    }

    test("PG: sequence rename is Native (declarative attributes only, no body)") {
        PostgresObjectRenamePolicy.classify(sequenceCandidate(), capsPostgres) shouldBe RenameSupport.Native
    }

    // ── MySQL ──────────────────────────────────────────────────────

    test("MySQL: view rename is Native (RENAME TABLE)") {
        MysqlObjectRenamePolicy.classify(viewCandidate(), capsMysql) shouldBe RenameSupport.Native
    }

    test("MySQL: materialized view rename is Blocked (MySQL has no MV support)") {
        val r = MysqlObjectRenamePolicy.classify(viewCandidate(materialized = true), capsMysql)
        val blocked = r.shouldBeInstanceOf<RenameSupport.Blocked>()
        blocked.message.shouldContain("MySQL has no native materialized-view support")
    }

    test("MySQL: trigger rename falls back to Drop+Create when bodies match") {
        val r = MysqlObjectRenamePolicy.classify(triggerCandidate(), capsMysql)
        val fallback = r.shouldBeInstanceOf<RenameSupport.DropCreateFallback>()
        fallback.rationale.shouldContain("ALTER TRIGGER")
    }

    test("MySQL: trigger rename without source body is Blocked") {
        val r = MysqlObjectRenamePolicy.classify(triggerCandidate(sourceBody = null), capsMysql)
        val blocked = r.shouldBeInstanceOf<RenameSupport.Blocked>()
        blocked.message.shouldContain("sourceBodyHash")
    }

    test("MySQL: trigger rename with body drift is Blocked") {
        val r = MysqlObjectRenamePolicy.classify(
            triggerCandidate(sourceBody = "h1", targetBody = "h2"),
            capsMysql,
        )
        r.shouldBeInstanceOf<RenameSupport.Blocked>()
    }

    test("MySQL: function rename falls back to Drop+Create when bodies match") {
        val r = MysqlObjectRenamePolicy.classify(functionCandidate(), capsMysql)
        r.shouldBeInstanceOf<RenameSupport.DropCreateFallback>()
    }

    test("MySQL: sequence rename falls back to Drop+Create (helper-table emulation)") {
        // E.3 Sub-Slice C: MySQL has no native sequence-rename
        // grammar; the helper-table emulation stores sequences as
        // rows in `dmg_sequences`. The Mapper decomposes the rename
        // into DropSequence(from) + CreateSequence(to) with
        // RenameProvenance — the defensive `UPDATE dmg_sequences`
        // path in MysqlDiffSequenceOps is a regression guard only.
        val r = MysqlObjectRenamePolicy.classify(sequenceCandidate(), capsMysql)
        val fallback = r.shouldBeInstanceOf<RenameSupport.DropCreateFallback>()
        fallback.rationale.shouldContain("helper-table")
    }

    // ── SQLite ─────────────────────────────────────────────────────

    test("SQLite: view rename falls back to Drop+Create when bodies match") {
        val r = SqliteObjectRenamePolicy.classify(viewCandidate(), capsSqlite)
        r.shouldBeInstanceOf<RenameSupport.DropCreateFallback>()
    }

    test("SQLite: view rename without source body is Blocked") {
        val r = SqliteObjectRenamePolicy.classify(viewCandidate(sourceBody = null), capsSqlite)
        r.shouldBeInstanceOf<RenameSupport.Blocked>()
    }

    test("SQLite: trigger rename falls back to Drop+Create when bodies match") {
        val r = SqliteObjectRenamePolicy.classify(triggerCandidate(), capsSqlite)
        r.shouldBeInstanceOf<RenameSupport.DropCreateFallback>()
    }

    test("SQLite: function rename is Blocked (no user-defined routines in SQLite)") {
        val r = SqliteObjectRenamePolicy.classify(functionCandidate(), capsSqlite)
        val blocked = r.shouldBeInstanceOf<RenameSupport.Blocked>()
        blocked.message.shouldContain("user-defined")
    }

    test("SQLite: sequence rename is Blocked (E.3 SQLite emulation out of scope)") {
        val r = SqliteObjectRenamePolicy.classify(sequenceCandidate(), capsSqlite)
        r.shouldBeInstanceOf<RenameSupport.Blocked>()
    }

    // ── Registry ───────────────────────────────────────────────────

    test("Registry returns the right policy per dialect") {
        ObjectRenamePolicyRegistry.forDialect(RenameProjectionDialect.POSTGRESQL) shouldBe PostgresObjectRenamePolicy
        ObjectRenamePolicyRegistry.forDialect(RenameProjectionDialect.MYSQL) shouldBe MysqlObjectRenamePolicy
        ObjectRenamePolicyRegistry.forDialect(RenameProjectionDialect.SQLITE) shouldBe SqliteObjectRenamePolicy
    }
})
