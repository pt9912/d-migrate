package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Coverage fuer die Diff-Render-Branches in [CompareRendererJson],
 * [CompareRendererYaml] und [CompareRendererPlain]. Konstruiert ein
 * SchemaCompareDocument mit voll besetztem [DiffView] (Tabellen/Views/
 * CustomTypes mit allen Change-Feldern, PrimaryKey-Aenderung,
 * Default/References/Generation auf Spalten, ViewChangeView mit
 * materialized/queryChanged/refresh/sourceDialect) und prueft, dass
 * jeder Branch in den drei Renderern eine erkennbare Ausgabe
 * produziert.
 */
class CompareRendererDiffTest : FunSpec({

    // ── Comprehensive document covering all branches ────────────────

    val fullDiff = DiffView(
        schemaMetadata = MetadataChangeView(
            name = StringChange("OldSchema", "NewSchema"),
            version = StringChange("1.0", "2.0"),
        ),
        customTypesAdded = listOf(
            CustomTypeSummaryView(name = "color_t", kind = "enum", detail = "red, green, blue"),
        ),
        customTypesRemoved = listOf(
            CustomTypeSummaryView(name = "legacy_t", kind = "domain", detail = "base: text"),
        ),
        customTypesChanged = listOf(
            CustomTypeChangeView(
                name = "status_t",
                kind = "enum",
                changes = listOf("values: [a] -> [a, b]", "description: changed"),
            ),
        ),
        tablesAdded = listOf(TableSummaryView("orders", columnCount = 4)),
        tablesRemoved = listOf(TableSummaryView("legacy_log", columnCount = 2)),
        tablesChanged = listOf(
            TableChangeView(
                name = "users",
                columnsAdded = listOf(
                    ColumnSummaryView(name = "email", type = "text"),
                    ColumnSummaryView(name = "tenant_id", type = "integer"),
                ),
                columnsRemoved = listOf("nick", "legacy_id"),
                columnsChanged = listOf(
                    ColumnChangeView(
                        name = "id",
                        type = StringChange("integer", "biginteger"),
                        required = StringChange("false", "true"),
                        default = NullableStringChange(before = null, after = "0"),
                        unique = StringChange("false", "true"),
                        references = NullableStringChange(before = null, after = "tenants.id"),
                        generation = NullableStringChange(before = null, after = "identity(mode=always)"),
                    ),
                ),
                primaryKey = StringListChange(
                    before = listOf("id"),
                    after = listOf("id", "tenant_id"),
                ),
                indicesAdded = listOf("idx_users_email [btree]"),
                indicesRemoved = listOf("idx_users_legacy [btree]"),
                indicesChanged = listOf(
                    StringChange("idx_users_status [btree]", "idx_users_status [btree,unique]"),
                ),
                constraintsAdded = listOf("uk_users_email (unique on [email])"),
                constraintsRemoved = listOf("ck_users_age (check)"),
                constraintsChanged = listOf(
                    StringChange("fk_users_org (foreign_key)", "fk_users_org (foreign_key on [org_id])"),
                ),
            ),
        ),
        viewsAdded = listOf(
            ViewSummaryView(name = "active_users", materialized = false),
            ViewSummaryView(name = "user_stats_mv", materialized = true),
        ),
        viewsRemoved = listOf(ViewSummaryView(name = "old_view", materialized = false)),
        viewsChanged = listOf(
            ViewChangeView(
                name = "users_summary",
                materialized = StringChange("false", "true"),
                refresh = NullableStringChange(before = null, after = "ON COMMIT"),
                queryChanged = true,
                sourceDialect = NullableStringChange(before = "postgresql", after = "mysql"),
            ),
        ),
        sequencesAdded = listOf("user_id_seq"),
        sequencesRemoved = listOf("legacy_seq"),
        sequencesChanged = listOf("audit_seq"),
        functionsAdded = listOf("public.fn_add"),
        functionsRemoved = listOf("public.fn_old"),
        functionsChanged = listOf("public.fn_calc"),
        proceduresAdded = listOf("public.sp_init"),
        proceduresRemoved = listOf("public.sp_old"),
        proceduresChanged = listOf("public.sp_run"),
        triggersAdded = listOf("trg_users_audit"),
        triggersRemoved = listOf("trg_legacy"),
        triggersChanged = listOf("trg_users_update"),
    )

    val fullDoc = SchemaCompareDocument(
        status = "differ",
        exitCode = 1,
        source = "/tmp/source.yaml",
        target = "/tmp/target.yaml",
        summary = SchemaCompareSummary(
            tablesAdded = 1,
            tablesRemoved = 1,
            tablesChanged = 1,
            customTypesAdded = 1,
            customTypesRemoved = 1,
            customTypesChanged = 1,
            viewsAdded = 2,
            viewsRemoved = 1,
            viewsChanged = 1,
            sequencesAdded = 1,
            sequencesRemoved = 1,
            sequencesChanged = 1,
            functionsAdded = 1,
            functionsRemoved = 1,
            functionsChanged = 1,
            proceduresAdded = 1,
            proceduresRemoved = 1,
            proceduresChanged = 1,
            triggersAdded = 1,
            triggersRemoved = 1,
            triggersChanged = 1,
        ),
        diff = fullDiff,
    )

    val emptyDoc = SchemaCompareDocument(
        status = "identical",
        exitCode = 0,
        source = "/tmp/a.yaml",
        target = "/tmp/b.yaml",
        summary = SchemaCompareSummary(),
        diff = null,
    )

    // ── JSON ───────────────────────────────────────────────────────

    context("CompareRendererJson") {
        test("renders schema metadata, custom types, tables, views with all branches") {
            val json = CompareRendererJson.render(fullDoc)
            json shouldContain """"schema_metadata":"""
            json shouldContain """"name":"""
            json shouldContain """"OldSchema""""
            json shouldContain """"NewSchema""""
            json shouldContain """"version":"""
            json shouldContain """"custom_types_added":"""
            json shouldContain """"color_t""""
            json shouldContain """"custom_types_removed":"""
            json shouldContain """"legacy_t""""
            json shouldContain """"custom_types_changed":"""
            json shouldContain """"status_t""""
            json shouldContain """"tables_added":"""
            json shouldContain """"orders""""
            json shouldContain """"tables_removed":"""
            json shouldContain """"legacy_log""""
            json shouldContain """"tables_changed":"""
            json shouldContain """"users""""
            json shouldContain """"views_added":"""
            json shouldContain """"active_users""""
            json shouldContain """"views_removed":"""
            json shouldContain """"views_changed":"""
            json shouldContain """"users_summary""""
            json shouldContain """"materialized":"""
            json shouldContain """"query": "changed""""
            json shouldContain """"refresh":"""
            json shouldContain """"source_dialect":"""
        }

        test("renders table change with columns added/removed/changed, indices, constraints, primary key") {
            val json = CompareRendererJson.render(fullDoc)
            json shouldContain """"columns_added":"""
            json shouldContain """"email""""
            json shouldContain """"tenant_id""""
            json shouldContain """"columns_removed":"""
            json shouldContain """"nick""""
            json shouldContain """"legacy_id""""
            json shouldContain """"columns_changed":"""
            json shouldContain """"type":"""
            json shouldContain """"required":"""
            json shouldContain """"default":"""
            json shouldContain """"unique":"""
            json shouldContain """"references":"""
            json shouldContain """"generation":"""
            json shouldContain """"primary_key":"""
            json shouldContain """"indices_added":"""
            json shouldContain """"indices_removed":"""
            json shouldContain """"indices_changed":"""
            json shouldContain """"constraints_added":"""
            json shouldContain """"constraints_removed":"""
            json shouldContain """"constraints_changed":"""
        }

        test("empty diff renders null") {
            val json = CompareRendererJson.render(emptyDoc)
            json shouldContain """"diff": null"""
        }
    }

    // ── YAML ──────────────────────────────────────────────────────

    context("CompareRendererYaml") {
        test("renders schema metadata, custom types, tables, views with all branches") {
            val yaml = CompareRendererYaml.render(fullDoc)
            yaml shouldContain "OldSchema"
            yaml shouldContain "NewSchema"
            yaml shouldContain "color_t"
            yaml shouldContain "legacy_t"
            yaml shouldContain "status_t"
            yaml shouldContain "orders"
            yaml shouldContain "legacy_log"
            yaml shouldContain "users"
            yaml shouldContain "active_users"
            yaml shouldContain "users_summary"
            // YAML/JSON renderer omits per-name lists for sequences/functions/
            // procedures/triggers — only the summary counts are surfaced
            // (verified separately in the summary block).
        }

        test("renders table change details (columns, indices, constraints, primary key)") {
            val yaml = CompareRendererYaml.render(fullDoc)
            yaml shouldContain "email"
            yaml shouldContain "tenant_id"
            yaml shouldContain "nick"
            yaml shouldContain "primary_key"
            yaml shouldContain "indices_added"
            yaml shouldContain "constraints_added"
        }

        test("empty document renders without crashing") {
            CompareRendererYaml.render(emptyDoc)
        }
    }

    // ── Plain ─────────────────────────────────────────────────────

    context("CompareRendererPlain") {
        test("renders schema metadata, custom types, tables, views with all branches") {
            val plain = CompareRendererPlain.render(fullDoc)
            plain shouldContain "users"
            plain shouldContain "orders"
            plain shouldContain "legacy_log"
            plain shouldContain "color_t"
            plain shouldContain "legacy_t"
            plain shouldContain "status_t"
            plain shouldContain "active_users"
            plain shouldContain "user_id_seq"
            plain shouldContain "trg_users_audit"
        }

        test("renders table change details") {
            val plain = CompareRendererPlain.render(fullDoc)
            plain shouldContain "email"
            plain shouldContain "tenant_id"
            plain shouldContain "primary_key" // some marker mentioning PK change
        }

        test("empty document renders without crashing") {
            CompareRendererPlain.render(emptyDoc)
        }
    }
})
