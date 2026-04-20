package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class PortsReadTest : FunSpec({

    // ── SpatialProfile enum ──────────────────────────────────────────

    test("SpatialProfile has four values") {
        SpatialProfile.entries.map { it.name } shouldBe listOf("POSTGIS", "NATIVE", "SPATIALITE", "NONE")
    }

    test("SpatialProfile valueOf round-trips") {
        SpatialProfile.valueOf("POSTGIS") shouldBe SpatialProfile.POSTGIS
        SpatialProfile.valueOf("NATIVE") shouldBe SpatialProfile.NATIVE
        SpatialProfile.valueOf("SPATIALITE") shouldBe SpatialProfile.SPATIALITE
        SpatialProfile.valueOf("NONE") shouldBe SpatialProfile.NONE
    }

    test("SpatialProfile cliName properties") {
        SpatialProfile.POSTGIS.cliName shouldBe "postgis"
        SpatialProfile.NATIVE.cliName shouldBe "native"
        SpatialProfile.SPATIALITE.cliName shouldBe "spatialite"
        SpatialProfile.NONE.cliName shouldBe "none"
    }

    test("SpatialProfile.fromCliName resolves all known names") {
        SpatialProfile.fromCliName("postgis") shouldBe SpatialProfile.POSTGIS
        SpatialProfile.fromCliName("native") shouldBe SpatialProfile.NATIVE
        SpatialProfile.fromCliName("spatialite") shouldBe SpatialProfile.SPATIALITE
        SpatialProfile.fromCliName("none") shouldBe SpatialProfile.NONE
    }

    test("SpatialProfile.fromCliName is case-insensitive") {
        SpatialProfile.fromCliName("POSTGIS") shouldBe SpatialProfile.POSTGIS
        SpatialProfile.fromCliName("Native") shouldBe SpatialProfile.NATIVE
    }

    test("SpatialProfile.fromCliName returns null for unknown") {
        SpatialProfile.fromCliName("oracle-spatial") shouldBe null
    }

    // ── SpatialProfilePolicy ─────────────────────────────────────────

    test("SpatialProfilePolicy.defaultFor returns dialect defaults") {
        SpatialProfilePolicy.defaultFor(DatabaseDialect.POSTGRESQL) shouldBe SpatialProfile.POSTGIS
        SpatialProfilePolicy.defaultFor(DatabaseDialect.MYSQL) shouldBe SpatialProfile.NATIVE
        SpatialProfilePolicy.defaultFor(DatabaseDialect.SQLITE) shouldBe SpatialProfile.NONE
    }

    test("SpatialProfilePolicy.allowedFor returns allowed sets") {
        SpatialProfilePolicy.allowedFor(DatabaseDialect.POSTGRESQL) shouldBe
            setOf(SpatialProfile.POSTGIS, SpatialProfile.NONE)
        SpatialProfilePolicy.allowedFor(DatabaseDialect.MYSQL) shouldBe
            setOf(SpatialProfile.NATIVE, SpatialProfile.NONE)
        SpatialProfilePolicy.allowedFor(DatabaseDialect.SQLITE) shouldBe
            setOf(SpatialProfile.SPATIALITE, SpatialProfile.NONE)
    }

    test("SpatialProfilePolicy.resolve returns default when rawProfile is null") {
        val result = SpatialProfilePolicy.resolve(DatabaseDialect.POSTGRESQL, null)
        result shouldBe SpatialProfilePolicy.Result.Resolved(SpatialProfile.POSTGIS)
    }

    test("SpatialProfilePolicy.resolve returns Resolved for allowed profile") {
        val result = SpatialProfilePolicy.resolve(DatabaseDialect.POSTGRESQL, "postgis")
        result shouldBe SpatialProfilePolicy.Result.Resolved(SpatialProfile.POSTGIS)
    }

    test("SpatialProfilePolicy.resolve returns Resolved for none profile on any dialect") {
        for (dialect in DatabaseDialect.entries) {
            val result = SpatialProfilePolicy.resolve(dialect, "none")
            result shouldBe SpatialProfilePolicy.Result.Resolved(SpatialProfile.NONE)
        }
    }

    test("SpatialProfilePolicy.resolve returns UnknownProfile for unknown name") {
        val result = SpatialProfilePolicy.resolve(DatabaseDialect.POSTGRESQL, "oracle-spatial")
        result shouldBe SpatialProfilePolicy.Result.UnknownProfile("oracle-spatial")
    }

    test("SpatialProfilePolicy.resolve returns NotAllowedForDialect for disallowed profile") {
        val result = SpatialProfilePolicy.resolve(DatabaseDialect.MYSQL, "postgis")
        result shouldBe SpatialProfilePolicy.Result.NotAllowedForDialect(
            SpatialProfile.POSTGIS, DatabaseDialect.MYSQL,
        )
    }

    // ── MysqlNamedSequenceMode enum ────────────────────────────────

    test("MysqlNamedSequenceMode has two values") {
        MysqlNamedSequenceMode.entries.map { it.name } shouldBe listOf("ACTION_REQUIRED", "HELPER_TABLE")
    }

    test("MysqlNamedSequenceMode.fromCliName resolves known names") {
        MysqlNamedSequenceMode.fromCliName("action_required") shouldBe MysqlNamedSequenceMode.ACTION_REQUIRED
        MysqlNamedSequenceMode.fromCliName("helper_table") shouldBe MysqlNamedSequenceMode.HELPER_TABLE
    }

    test("MysqlNamedSequenceMode.fromCliName is case-insensitive") {
        MysqlNamedSequenceMode.fromCliName("HELPER_TABLE") shouldBe MysqlNamedSequenceMode.HELPER_TABLE
        MysqlNamedSequenceMode.fromCliName("Action_Required") shouldBe MysqlNamedSequenceMode.ACTION_REQUIRED
    }

    test("MysqlNamedSequenceMode.fromCliName returns null for unknown") {
        MysqlNamedSequenceMode.fromCliName("auto") shouldBe null
    }

    test("MysqlNamedSequenceMode valueOf round-trips") {
        MysqlNamedSequenceMode.valueOf("ACTION_REQUIRED") shouldBe MysqlNamedSequenceMode.ACTION_REQUIRED
        MysqlNamedSequenceMode.valueOf("HELPER_TABLE") shouldBe MysqlNamedSequenceMode.HELPER_TABLE
    }

    test("MysqlNamedSequenceMode toString contains name") {
        MysqlNamedSequenceMode.ACTION_REQUIRED.toString() shouldContain "ACTION_REQUIRED"
        MysqlNamedSequenceMode.HELPER_TABLE.toString() shouldContain "HELPER_TABLE"
    }

    test("MysqlNamedSequenceMode cliName properties") {
        MysqlNamedSequenceMode.ACTION_REQUIRED.cliName shouldBe "action_required"
        MysqlNamedSequenceMode.HELPER_TABLE.cliName shouldBe "helper_table"
    }

    test("DdlGenerationOptions carries mysqlNamedSequenceMode") {
        val opts = DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE)
        opts.mysqlNamedSequenceMode shouldBe MysqlNamedSequenceMode.HELPER_TABLE

        val defaultOpts = DdlGenerationOptions()
        defaultOpts.mysqlNamedSequenceMode shouldBe null
    }

    test("DdlGenerationOptions copy and equality") {
        val a = DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.ACTION_REQUIRED)
        val b = a.copy(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE)
        a shouldNotBe b
        b.mysqlNamedSequenceMode shouldBe MysqlNamedSequenceMode.HELPER_TABLE
        a.copy() shouldBe a
    }

    test("DdlGenerationOptions toString contains class name") {
        val opts = DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE)
        opts.toString() shouldContain "DdlGenerationOptions"
        opts.toString() shouldContain "HELPER_TABLE"
    }

    test("SpatialProfilePolicy.resolve returns NotAllowedForDialect for spatialite on PostgreSQL") {
        val result = SpatialProfilePolicy.resolve(DatabaseDialect.POSTGRESQL, "spatialite")
        result shouldBe SpatialProfilePolicy.Result.NotAllowedForDialect(
            SpatialProfile.SPATIALITE, DatabaseDialect.POSTGRESQL,
        )
    }

    // ── SpatialProfilePolicy.Result sealed interface ─────────────────

    test("SpatialProfilePolicy.Result.Resolved data class properties") {
        val r = SpatialProfilePolicy.Result.Resolved(SpatialProfile.POSTGIS)
        r.profile shouldBe SpatialProfile.POSTGIS
    }

    test("SpatialProfilePolicy.Result.Resolved equality") {
        val a = SpatialProfilePolicy.Result.Resolved(SpatialProfile.POSTGIS)
        val b = SpatialProfilePolicy.Result.Resolved(SpatialProfile.POSTGIS)
        a shouldBe b
    }

    test("SpatialProfilePolicy.Result.Resolved copy") {
        val r = SpatialProfilePolicy.Result.Resolved(SpatialProfile.POSTGIS)
        val c = r.copy(profile = SpatialProfile.NONE)
        c.profile shouldBe SpatialProfile.NONE
    }

    test("SpatialProfilePolicy.Result.UnknownProfile data class") {
        val r = SpatialProfilePolicy.Result.UnknownProfile("foo")
        r.raw shouldBe "foo"
        r.toString() shouldContain "UnknownProfile"
    }

    test("SpatialProfilePolicy.Result.NotAllowedForDialect data class") {
        val r = SpatialProfilePolicy.Result.NotAllowedForDialect(
            SpatialProfile.POSTGIS, DatabaseDialect.MYSQL,
        )
        r.profile shouldBe SpatialProfile.POSTGIS
        r.dialect shouldBe DatabaseDialect.MYSQL
        r.toString() shouldContain "NotAllowedForDialect"
    }

    // ── ReverseSourceKind enum ────────────────────────────────────────

    test("ReverseSourceKind has two values") {
        ReverseSourceKind.entries.map { it.name } shouldBe listOf("ALIAS", "URL")
    }

    test("ReverseSourceKind valueOf round-trips") {
        ReverseSourceKind.valueOf("ALIAS") shouldBe ReverseSourceKind.ALIAS
        ReverseSourceKind.valueOf("URL") shouldBe ReverseSourceKind.URL
    }

    // ── ReverseSourceRef data class ──────────────────────────────────

    test("ReverseSourceRef properties") {
        val ref = ReverseSourceRef(kind = ReverseSourceKind.ALIAS, value = "my-pg")
        ref.kind shouldBe ReverseSourceKind.ALIAS
        ref.value shouldBe "my-pg"
    }

    test("ReverseSourceRef equality") {
        val a = ReverseSourceRef(ReverseSourceKind.URL, "jdbc:pg://host/db")
        val b = ReverseSourceRef(ReverseSourceKind.URL, "jdbc:pg://host/db")
        a shouldBe b
    }

    test("ReverseSourceRef inequality") {
        val a = ReverseSourceRef(ReverseSourceKind.ALIAS, "a")
        val b = ReverseSourceRef(ReverseSourceKind.URL, "a")
        a shouldNotBe b
    }

    test("ReverseSourceRef copy") {
        val ref = ReverseSourceRef(ReverseSourceKind.ALIAS, "old")
        val c = ref.copy(value = "new")
        c.value shouldBe "new"
        c.kind shouldBe ReverseSourceKind.ALIAS
    }

    test("ReverseSourceRef toString contains class name") {
        val ref = ReverseSourceRef(ReverseSourceKind.ALIAS, "x")
        ref.toString() shouldContain "ReverseSourceRef"
    }

    // ── SchemaReadSeverity enum ──────────────────────────────────────

    test("SchemaReadSeverity has three values") {
        SchemaReadSeverity.entries.map { it.name } shouldBe listOf("INFO", "WARNING", "ACTION_REQUIRED")
    }

    test("SchemaReadSeverity valueOf round-trips") {
        SchemaReadSeverity.valueOf("INFO") shouldBe SchemaReadSeverity.INFO
        SchemaReadSeverity.valueOf("WARNING") shouldBe SchemaReadSeverity.WARNING
        SchemaReadSeverity.valueOf("ACTION_REQUIRED") shouldBe SchemaReadSeverity.ACTION_REQUIRED
    }

    // ── DdlResult.render (ensure all lines covered) ──────────────────

    test("DdlResult with skipped objects") {
        val skipped = SkippedObject("trigger", "trg1", "not supported", "E055", "manual migration")
        val result = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE t (id INT);")),
            skippedObjects = listOf(skipped),
        )
        result.skippedObjects.size shouldBe 1
        result.skippedObjects[0].type shouldBe "trigger"
        result.skippedObjects[0].name shouldBe "trg1"
        result.skippedObjects[0].reason shouldBe "not supported"
        result.skippedObjects[0].code shouldBe "E055"
        result.skippedObjects[0].hint shouldBe "manual migration"
    }

    test("DdlResult notes from multiple statements") {
        val note1 = TransformationNote(NoteType.INFO, "I001", "t1", "msg1")
        val note2 = TransformationNote(NoteType.WARNING, "W002", "t2", "msg2", "h2")
        val result = DdlResult(
            listOf(
                DdlStatement("SELECT 1;", listOf(note1)),
                DdlStatement("SELECT 2;", listOf(note2)),
            ),
        )
        result.notes shouldBe listOf(note1, note2)
    }

    test("DdlStatement render with note without hint") {
        val note = TransformationNote(NoteType.INFO, "I001", "obj", "info msg")
        val stmt = DdlStatement("SELECT 1;", listOf(note))
        val rendered = stmt.render()
        rendered shouldContain "-- [I001] info msg"
        rendered shouldContain "SELECT 1;"
        // hint line should NOT appear
        ("Hint" in rendered) shouldBe false
    }
})
