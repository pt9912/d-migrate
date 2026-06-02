package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * F.4 cli-inline-overlay §3.2 pins for the
 * [InlineRenameOverlayBuilder]: CLI parsing rules, sentinel
 * `createdAt`, clock-stable hash, and the duplicate-`from`
 * Exit-2 rejection path. Cross-document conflicts are pinned in
 * `MigrationOverlayPreflightTest`; the runner-level Exit-2 flow is
 * pinned in `SchemaMigratePrePlanOverlayGateTest`.
 */
class InlineRenameOverlayBuilderTest : FunSpec({

    fun build(
        tables: List<String> = emptyList(),
        columns: List<String> = emptyList(),
        sourceFingerprint: String = "src-fp",
        targetFingerprint: String = "dst-fp",
        dialect: String = "postgresql",
        version: String = "d-migrate (test)",
    ): InlineRenameOverlayResult = InlineRenameOverlayBuilder.build(
        renameTableFlags = tables,
        renameColumnFlags = columns,
        sourceFingerprint = sourceFingerprint,
        targetFingerprint = targetFingerprint,
        dialect = dialect,
        version = version,
    )

    test("empty input yields Empty") {
        build().shouldBeInstanceOf<InlineRenameOverlayResult.Empty>()
    }

    test("single --rename-table builds a signed rename-mapping overlay") {
        val result = build(tables = listOf("users_old:users"))
        val built = result.shouldBeInstanceOf<InlineRenameOverlayResult.Built>()
        built.document.source shouldBe InlineRenameOverlayBuilder.INLINE_SOURCE
        val overlay = built.document.overlay
        overlay.overlayKind shouldBe MigrationOverlayKinds.RENAME_MAPPING
        overlay.sourceFingerprint shouldBe "src-fp"
        overlay.targetFingerprint shouldBe "dst-fp"
        overlay.dialect shouldBe "postgresql"
        overlay.createdAt shouldBe InlineRenameOverlayBuilder.INLINE_CREATED_AT_SENTINEL
        overlay.overlayHash?.isNotBlank() shouldBe true
        val entry = overlay.entries.single().shouldBeInstanceOf<RenameMappingOverlayEntry>()
        entry.objectType shouldBe "table"
        entry.fromName shouldBe "users_old"
        entry.toName shouldBe "users"
        entry.id shouldBe "rename-table-0"
    }

    test("trims whitespace around from/to in --rename-table") {
        val built = build(tables = listOf("  users_old : users  "))
            .shouldBeInstanceOf<InlineRenameOverlayResult.Built>()
        val entry = built.document.overlay.entries.single().shouldBeInstanceOf<RenameMappingOverlayEntry>()
        entry.fromName shouldBe "users_old"
        entry.toName shouldBe "users"
    }

    test("--rename-column builds a column entry with stable id") {
        val built = build(columns = listOf("users.old_name:users.new_name"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.Built>()
        val entry = built.document.overlay.entries.single().shouldBeInstanceOf<RenameMappingOverlayEntry>()
        entry.objectType shouldBe "column"
        entry.fromName shouldBe "users.old_name"
        entry.toName shouldBe "users.new_name"
        entry.id shouldBe "rename-column-0"
    }

    test("multiple flags are wired through with positional ids") {
        val built = build(
            tables = listOf("a:b", "c:d"),
            columns = listOf("t.x:t.y"),
        ).shouldBeInstanceOf<InlineRenameOverlayResult.Built>()
        built.document.overlay.entries.map { it.id } shouldBe listOf(
            "rename-table-0", "rename-table-1", "rename-column-0",
        )
    }

    test("missing separator in --rename-table is a parse error") {
        val failed = build(tables = listOf("users_old_users"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        failed.errors.single() shouldContain "expected `<from>:<to>`"
    }

    test("multiple colons in --rename-table is a parse error") {
        val failed = build(tables = listOf("a:b:c"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        failed.errors.single() shouldContain "expected `<from>:<to>`"
    }

    test("blank flag value is a parse error") {
        build(tables = listOf("   ")).shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        build(tables = listOf(":dst")).shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        build(tables = listOf("src:")).shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
    }

    test("--rename-column rejects mismatched table prefixes") {
        val failed = build(columns = listOf("users.x:profiles.y"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        failed.errors.single() shouldContain "table prefix must be identical"
    }

    test("--rename-column rejects unqualified columns") {
        // Spec §6.2: unqualified <from>:<to> in --rename-column is
        // too error-prone — the CLI shortcut requires the explicit
        // table prefix on both sides.
        val failed = build(columns = listOf("old:new"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        failed.errors.single() shouldContain "left side must be `<table>.<column>`"
    }

    test("SQL identifier quoting chars are rejected in the shortcut") {
        // Spec §6.1: the CLI shortcut accepts raw identifier text
        // only; dialect quoting is the renderer's job. Forbidding
        // quoting chars surfaces the rule with a concrete error.
        listOf("\"a\":b", "`a`:b", "[a]:b").forEach { raw ->
            build(tables = listOf(raw)).shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        }
    }

    test("duplicate from within the same invocation is rejected as a parse error") {
        // The same source name maps to two different targets — that
        // is an Exit-2 CLI mistake, not an Exit-8 cross-document
        // gate finding. The runner converts ParseFailed to Exit 2.
        val failed = build(tables = listOf("users_old:users", "users_old:members"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.ParseFailed>()
        failed.errors.single() shouldContain "Duplicate inline rename source"
    }

    test("clock-stable: two identical builds produce the same overlayHash and entry ids") {
        val a = build(tables = listOf("users_old:users"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.Built>()
        val b = build(tables = listOf("users_old:users"))
            .shouldBeInstanceOf<InlineRenameOverlayResult.Built>()
        // Sentinel createdAt + canonical hash means two identical
        // CLI invocations produce a bit-identical overlay (and
        // therefore identical Rename* operation ids downstream)
        // regardless of wall-clock. Pinning this prevents a future
        // refactor from re-introducing a timestamp into the hashed
        // body.
        a.document.overlay.overlayHash shouldBe b.document.overlay.overlayHash
        a.document.overlay.entries.map { it.id } shouldBe b.document.overlay.entries.map { it.id }
    }
})
