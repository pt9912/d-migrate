package dev.dmigrate.core.diff.routine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RoutineIdentityNormalizerTest : FunSpec({

    test("PostgreSQL search_path normalisation trims, lowercases unquoted names, dedupes, and preserves order") {
        RoutineIdentityNormalizer.normalizePostgresSearchPath(
            listOf(" Public ", "", "public", "AUDIT", "audit"),
        ) shouldBe listOf("public", "audit")
    }

    test("PostgreSQL search_path treats quoted and unquoted user placeholder as the same token") {
        RoutineIdentityNormalizer.normalizePostgresSearchPath(
            listOf("\"\$user\"", "\$user", "PUBLIC"),
        ) shouldBe listOf("\$user", "public")
    }

    test("PostgreSQL quoted identifiers keep case and unescape doubled quotes") {
        RoutineIdentityNormalizer.normalizePostgresSearchPath(
            listOf("\"CaseSensitive\"", "\"a\"\"b\""),
        ) shouldBe listOf("CaseSensitive", "a\"b")
    }

    test("MySQL sql_mode normalisation uppercases, sorts, and dedupes tokens") {
        RoutineIdentityNormalizer.normalizeMysqlSqlMode(
            " pipes_as_concat,STRICT_TRANS_TABLES,pipes_as_concat, no_engine_substitution ",
        ) shouldBe "NO_ENGINE_SUBSTITUTION,PIPES_AS_CONCAT,STRICT_TRANS_TABLES"
    }

    test("blank routine identity attributes normalise to null") {
        RoutineIdentityNormalizer.normalizePostgresSearchPath(emptyList()).shouldBe(null)
        RoutineIdentityNormalizer.normalizePostgresSearchPath(listOf(" ", "")).shouldBe(null)
        RoutineIdentityNormalizer.normalizeMysqlSqlMode(" , ").shouldBe(null)
    }
})
