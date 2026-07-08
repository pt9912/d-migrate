package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit-Tests für den Bound-Parser (ADR 0019 / AP1 Bug-Hotspot). Die Eingaben
 * sind realistische `pg_get_expr(relpartbound, …)`-Strings je Strategie —
 * inkl. Typ-Casts, Sentinels, mehrspaltiger Tupel und Quote-Edge-Cases.
 */
class PostgresPartitionBoundParserTest : FunSpec({

    val parse = PostgresPartitionBoundParser::parse

    // ── RANGE ──────────────────────────────────────

    test("RANGE single-column numeric bounds") {
        val def = parse("p1", "FOR VALUES FROM (0) TO (100)", PartitionType.RANGE)
        def.from shouldBe listOf(PartitionBound.Value("0"))
        def.to shouldBe listOf(PartitionBound.Value("100"))
        def.isDefault shouldBe false
    }

    test("RANGE strips type casts on timestamptz bounds (the Pagila trigger case)") {
        val expr = "FOR VALUES FROM ('2022-02-01 00:00:00+00'::timestamp with time zone) " +
            "TO ('2022-03-01 00:00:00+00'::timestamp with time zone)"
        val def = parse("payment_p2022_02", expr, PartitionType.RANGE)
        def.from shouldBe listOf(PartitionBound.Value("'2022-02-01 00:00:00+00'"))
        def.to shouldBe listOf(PartitionBound.Value("'2022-03-01 00:00:00+00'"))
    }

    test("RANGE MINVALUE / MAXVALUE sentinels become structured bounds") {
        val lower = parse("p_lo", "FOR VALUES FROM (MINVALUE) TO (100)", PartitionType.RANGE)
        lower.from shouldBe listOf(PartitionBound.MinValue)
        lower.to shouldBe listOf(PartitionBound.Value("100"))

        val upper = parse("p_hi", "FOR VALUES FROM (0) TO (MAXVALUE)", PartitionType.RANGE)
        upper.from shouldBe listOf(PartitionBound.Value("0"))
        upper.to shouldBe listOf(PartitionBound.MaxValue)
    }

    test("RANGE multi-column tuple bounds (with a sentinel inside the tuple)") {
        val expr = "FOR VALUES FROM ('2022-01-01', MINVALUE) TO ('2022-02-01', MAXVALUE)"
        val def = parse("p_multi", expr, PartitionType.RANGE)
        def.from shouldBe listOf(PartitionBound.Value("'2022-01-01'"), PartitionBound.MinValue)
        def.to shouldBe listOf(PartitionBound.Value("'2022-02-01'"), PartitionBound.MaxValue)
    }

    test("RANGE cast carrying parentheses (numeric(10,2)) does not split the tuple") {
        val expr = "FOR VALUES FROM (1.5::numeric(10,2)) TO (9.9::numeric(10,2))"
        val def = parse("p_num", expr, PartitionType.RANGE)
        def.from shouldBe listOf(PartitionBound.Value("1.5"))
        def.to shouldBe listOf(PartitionBound.Value("9.9"))
    }

    test("RANGE missing TO clause is rejected") {
        shouldThrow<IllegalArgumentException> {
            parse("bad", "FOR VALUES FROM (0)", PartitionType.RANGE)
        }
    }

    test("RANGE unbalanced parentheses are rejected") {
        shouldThrow<IllegalStateException> {
            parse("bad", "FOR VALUES FROM (0 TO (100)", PartitionType.RANGE)
        }
    }

    // ── LIST ───────────────────────────────────────

    test("LIST string values are kept verbatim (quotes intact)") {
        val def = parse("p_list", "FOR VALUES IN ('US', 'CA', 'MX')", PartitionType.LIST)
        def.values shouldBe listOf("'US'", "'CA'", "'MX'")
    }

    test("LIST strips casts and keeps NULL") {
        parse("p1", "FOR VALUES IN ('a'::text, 'b'::text)", PartitionType.LIST)
            .values shouldBe listOf("'a'", "'b'")
        parse("p2", "FOR VALUES IN (NULL)", PartitionType.LIST)
            .values shouldBe listOf("NULL")
    }

    test("LIST comma inside a quoted literal is not split") {
        parse("p_comma", "FOR VALUES IN ('a,b', 'c')", PartitionType.LIST)
            .values shouldBe listOf("'a,b'", "'c'")
    }

    test("LIST escaped quote inside a literal stays balanced") {
        parse("p_esc", "FOR VALUES IN ('O''Brien', 'Smith')", PartitionType.LIST)
            .values shouldBe listOf("'O''Brien'", "'Smith'")
    }

    // ── HASH ───────────────────────────────────────

    test("HASH modulus / remainder parsed as ints (lowercase keywords as PG renders)") {
        val def = parse("p_hash", "FOR VALUES WITH (modulus 4, remainder 0)", PartitionType.HASH)
        def.modulus shouldBe 4
        def.remainder shouldBe 0
    }

    test("HASH keyword casing is tolerated") {
        val def = parse("p_hash", "FOR VALUES WITH (MODULUS 8, REMAINDER 3)", PartitionType.HASH)
        def.modulus shouldBe 8
        def.remainder shouldBe 3
    }

    // ── DEFAULT ────────────────────────────────────

    test("DEFAULT partition sets isDefault and no bounds") {
        val def = parse("p_def", "DEFAULT", PartitionType.LIST)
        def.isDefault shouldBe true
        def.from shouldBe null
        def.to shouldBe null
        def.values shouldBe null
    }

    test("DEFAULT is recognised case-insensitively and trims whitespace") {
        parse("p_def", "  default  ", PartitionType.RANGE).isDefault shouldBe true
    }
})
