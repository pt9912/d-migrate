package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class FilterDslParserTest : FunSpec({

    fun parseOk(input: String): FilterExpr {
        val result = FilterDslParser.parse(input)
        result.shouldBeInstanceOf<FilterDslParseResult.Success>()
        return result.expr
    }

    fun parseFail(input: String): FilterDslParseError {
        val result = FilterDslParser.parse(input)
        result.shouldBeInstanceOf<FilterDslParseResult.Failure>()
        return result.error
    }

    // ── Basic comparisons ──────────────────────────────────────

    test("simple equality") {
        val expr = parseOk("status = 'OPEN'")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.op shouldBe "="
        expr.left.shouldBeInstanceOf<ValueExpr.Identifier>().name shouldBe "status"
        expr.right.shouldBeInstanceOf<ValueExpr.StrLiteral>().value shouldBe "OPEN"
    }

    test("not equal") {
        val expr = parseOk("age != 0")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.op shouldBe "!="
    }

    test("greater than, less than, >=, <=") {
        for (op in listOf(">", "<", ">=", "<=")) {
            val expr = parseOk("x $op 10")
            expr.shouldBeInstanceOf<FilterExpr.Comparison>()
            expr.op shouldBe op
        }
    }

    test("integer literal") {
        val expr = parseOk("id = 42")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.right.shouldBeInstanceOf<ValueExpr.IntLiteral>().value shouldBe 42L
    }

    test("decimal literal") {
        val expr = parseOk("price > 9.99")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.right.shouldBeInstanceOf<ValueExpr.DecLiteral>().value.toPlainString() shouldBe "9.99"
    }

    test("boolean literal true/false case-insensitive") {
        val expr = parseOk("active = True")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.right.shouldBeInstanceOf<ValueExpr.BoolLiteral>().value shouldBe true
    }

    test("string literal with escaped quote") {
        val expr = parseOk("name = 'O''Brien'")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.right.shouldBeInstanceOf<ValueExpr.StrLiteral>().value shouldBe "O'Brien"
    }

    // ── IS NULL / IS NOT NULL ──────────────────────────────────

    test("IS NULL") {
        val expr = parseOk("email IS NULL")
        expr.shouldBeInstanceOf<FilterExpr.IsNull>()
        expr.expr.shouldBeInstanceOf<ValueExpr.Identifier>().name shouldBe "email"
    }

    test("IS NOT NULL") {
        val expr = parseOk("email IS NOT NULL")
        expr.shouldBeInstanceOf<FilterExpr.IsNotNull>()
    }

    test("IS NULL case-insensitive") {
        parseOk("x is null").shouldBeInstanceOf<FilterExpr.IsNull>()
        parseOk("x Is Not Null").shouldBeInstanceOf<FilterExpr.IsNotNull>()
    }

    // ── IN ─────────────────────────────────────────────────────

    test("IN with string values") {
        val expr = parseOk("status IN ('OPEN', 'CLOSED')")
        expr.shouldBeInstanceOf<FilterExpr.In>()
        expr.values.size shouldBe 2
    }

    test("IN with arithmetic") {
        val expr = parseOk("amount IN (100, price * 2)")
        expr.shouldBeInstanceOf<FilterExpr.In>()
        expr.values[1].shouldBeInstanceOf<ValueExpr.Arithmetic>()
    }

    test("IN case-insensitive") {
        parseOk("x in (1, 2)").shouldBeInstanceOf<FilterExpr.In>()
    }

    // ── Boolean operators and precedence ───────────────────────

    test("AND") {
        val expr = parseOk("a = 1 AND b = 2")
        expr.shouldBeInstanceOf<FilterExpr.And>()
    }

    test("OR") {
        val expr = parseOk("a = 1 OR b = 2")
        expr.shouldBeInstanceOf<FilterExpr.Or>()
    }

    test("NOT") {
        val expr = parseOk("NOT a = 1")
        expr.shouldBeInstanceOf<FilterExpr.Not>()
    }

    test("NOT > AND > OR precedence") {
        // a = 1 OR NOT b = 2 AND c = 3
        // should parse as: a = 1 OR ((NOT b = 2) AND c = 3)
        val expr = parseOk("a = 1 OR NOT b = 2 AND c = 3")
        expr.shouldBeInstanceOf<FilterExpr.Or>()
        val right = expr.right.shouldBeInstanceOf<FilterExpr.And>()
        right.left.shouldBeInstanceOf<FilterExpr.Not>()
    }

    test("parentheses override precedence") {
        val expr = parseOk("(a = 1 OR b = 2) AND c = 3")
        expr.shouldBeInstanceOf<FilterExpr.And>()
        expr.left.shouldBeInstanceOf<FilterExpr.Group>()
    }

    test("keywords case-insensitive") {
        parseOk("a = 1 and b = 2 or c = 3").shouldBeInstanceOf<FilterExpr.Or>()
    }

    // ── Qualified identifiers ──────────────────────────────────

    test("qualified identifier table.column") {
        val expr = parseOk("orders.status = 'OPEN'")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.left.shouldBeInstanceOf<ValueExpr.Identifier>().name shouldBe "orders.status"
    }

    // ── Arithmetic ─────────────────────────────────────────────

    test("arithmetic in comparison") {
        val expr = parseOk("price * quantity > 100")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.left.shouldBeInstanceOf<ValueExpr.Arithmetic>()
    }

    test("arithmetic precedence: * before +") {
        val expr = parseOk("a + b * c > 0")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        val left = expr.left.shouldBeInstanceOf<ValueExpr.Arithmetic>()
        left.op shouldBe "+"
        left.right.shouldBeInstanceOf<ValueExpr.Arithmetic>().op shouldBe "*"
    }

    test("unary minus") {
        val expr = parseOk("x = -1")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        val right = expr.right.shouldBeInstanceOf<ValueExpr.UnaryMinus>()
        right.inner.shouldBeInstanceOf<ValueExpr.IntLiteral>().value shouldBe 1L
    }

    test("grouped value expression") {
        val expr = parseOk("(a + b) * c > 0")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        val left = expr.left.shouldBeInstanceOf<ValueExpr.Arithmetic>()
        left.op shouldBe "*"
        left.left.shouldBeInstanceOf<ValueExpr.GroupedValue>()
    }

    // ── Function calls ─────────────────────────────────────────

    test("allowed function LOWER") {
        val expr = parseOk("LOWER(name) = 'alice'")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        val fn = expr.left.shouldBeInstanceOf<ValueExpr.FunctionCall>()
        fn.name shouldBe "LOWER"
        fn.args.size shouldBe 1
    }

    test("ROUND with 2 args") {
        val expr = parseOk("ROUND(price, 2) > 0")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.left.shouldBeInstanceOf<ValueExpr.FunctionCall>().args.size shouldBe 2
    }

    test("COALESCE with 3 args") {
        val expr = parseOk("COALESCE(a, b, 0) > 0")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.left.shouldBeInstanceOf<ValueExpr.FunctionCall>().args.size shouldBe 3
    }

    test("function case-insensitive") {
        val expr = parseOk("lower(name) = 'x'")
        expr.shouldBeInstanceOf<FilterExpr.Comparison>()
        expr.left.shouldBeInstanceOf<ValueExpr.FunctionCall>().name shouldBe "LOWER"
    }

    // ── Error cases ────────────────────────────────────────────

    test("empty filter") {
        val err = parseFail("")
        err.message shouldContain "empty"
    }

    test("whitespace-only filter") {
        val err = parseFail("   ")
        err.message shouldContain "empty"
    }

    test("null literal with = operator") {
        val err = parseFail("name = null")
        err.message shouldContain "IS NULL"
    }

    test("null in IN list") {
        val err = parseFail("name IN ('a', null, 'b')")
        err.message shouldContain "IS NULL"
    }

    test("null literal with != operator") {
        val err = parseFail("name != null")
        err.message shouldContain "IS NULL"
    }

    test("leading zeros in integer") {
        val err = parseFail("x = 01")
        err.message shouldContain "Leading zeros"
    }

    test("leading zeros in decimal") {
        val err = parseFail("x = 01.5")
        err.message shouldContain "Leading zeros"
    }

    test("multi-level qualified identifier") {
        val err = parseFail("schema.table.column = 1")
        err.message shouldContain "Multi-level"
    }

    test("disallowed function") {
        val err = parseFail("CONCAT(a, b) = 'x'")
        err.message shouldContain "not allowed"
        err.message shouldContain "CONCAT"
    }

    test("function wrong arity: LOWER with 2 args") {
        val err = parseFail("LOWER(a, b) = 'x'")
        err.message shouldContain "1"
        err.message shouldContain "2"
    }

    test("COALESCE with 1 arg") {
        val err = parseFail("COALESCE(a) > 0")
        err.message shouldContain "2"
    }

    test("unterminated string") {
        val err = parseFail("x = 'hello")
        err.message shouldContain "Unterminated"
    }

    test("unexpected character") {
        val err = parseFail("x = @foo")
        err.message shouldContain "Unexpected character"
    }

    // ── Canonical fingerprint ──────────────────────────────────

    test("canonicalize normalizes keyword case and whitespace") {
        val expr = parseOk("  status  =   'OPEN'   and  active = true  ")
        val canon = FilterDslTranslator.canonicalize(expr)
        canon shouldBe "status = 'OPEN' AND active = true"
    }

    test("canonicalize lowercases identifiers") {
        val expr = parseOk("Status = 'x'")
        FilterDslTranslator.canonicalize(expr) shouldBe "status = 'x'"
    }

    test("canonicalize preserves qualified identifiers lowercased") {
        val expr = parseOk("Orders.Status = 'x'")
        FilterDslTranslator.canonicalize(expr) shouldBe "orders.status = 'x'"
    }

    test("canonicalize preserves parentheses") {
        val expr = parseOk("(a = 1)")
        FilterDslTranslator.canonicalize(expr) shouldBe "(a = 1)"
    }

    test("same semantic filter produces same fingerprint regardless of whitespace") {
        val a = FilterDslTranslator.canonicalize(parseOk("status='OPEN' AND active=true"))
        val b = FilterDslTranslator.canonicalize(parseOk("status = 'OPEN'  AND  active = true"))
        a shouldBe b
    }

    test("same semantic filter produces same fingerprint regardless of keyword case") {
        val a = FilterDslTranslator.canonicalize(parseOk("status = 'x' AND active = true"))
        val b = FilterDslTranslator.canonicalize(parseOk("status = 'x' and active = TRUE"))
        a shouldBe b
    }

    // ── SQL emission ───────────────────────────────────────────

    test("toParameterizedClause produces ? placeholders and params") {
        val expr = parseOk("status = 'OPEN' AND age > 18")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"status\" = ? AND \"age\" > ?"
        clause.params shouldBe listOf("OPEN", 18L)
    }

    test("toParameterizedClause handles IN list") {
        val expr = parseOk("id IN (1, 2, 3)")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"id\" IN (?, ?, ?)"
        clause.params shouldBe listOf(1L, 2L, 3L)
    }

    test("toParameterizedClause handles IS NULL without params") {
        val expr = parseOk("email IS NULL")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"email\" IS NULL"
        clause.params shouldBe emptyList()
    }

    test("toParameterizedClause handles function calls") {
        val expr = parseOk("LOWER(name) = 'alice'")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "LOWER(\"name\") = ?"
        clause.params shouldBe listOf("alice")
    }

    test("toParameterizedClause handles MySQL backtick quoting") {
        val expr = parseOk("status = 'OPEN'")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.MYSQL)
        clause.sql shouldBe "`status` = ?"
        clause.params shouldBe listOf("OPEN")
    }

    test("toParameterizedClause handles qualified identifier") {
        val expr = parseOk("orders.status = 'x'")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"orders\".\"status\" = ?"
    }

    test("toParameterizedClause wraps OR in parens") {
        val expr = parseOk("a = 1 OR b = 2")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "(\"a\" = ? OR \"b\" = ?)"
    }

    test("toParameterizedClause handles boolean params") {
        val expr = parseOk("active = true")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"active\" = ?"
        clause.params shouldBe listOf(true)
    }

    test("toParameterizedClause handles arithmetic") {
        val expr = parseOk("price * quantity > 100")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"price\" * \"quantity\" > ?"
        clause.params shouldBe listOf(100L)
    }

    // ── SQL emission: remaining branches ─────────────────────────

    test("toParameterizedClause handles NOT") {
        val expr = parseOk("NOT active = true")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "NOT \"active\" = ?"
    }

    test("toParameterizedClause handles grouped filter") {
        val expr = parseOk("(a = 1 OR b = 2) AND c = 3")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldContain "("
        clause.sql shouldContain "OR"
        clause.sql shouldContain "AND"
    }

    test("toParameterizedClause handles decimal literal") {
        val expr = parseOk("price > 9.99")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.params shouldBe listOf(java.math.BigDecimal("9.99"))
    }

    test("toParameterizedClause handles unary minus") {
        val expr = parseOk("balance > -100")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldContain "-?"
    }

    test("toParameterizedClause handles function call in SQL") {
        val expr = parseOk("LOWER(name) = 'alice'")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "LOWER(\"name\") = ?"
    }

    test("toParameterizedClause handles IS NOT NULL") {
        val expr = parseOk("email IS NOT NULL")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"email\" IS NOT NULL"
        clause.params shouldBe emptyList()
    }

    test("toParameterizedClause handles grouped value expression") {
        val expr = parseOk("(a + b) * c > 0")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldContain "(\"a\" + \"b\")"
    }

    test("toParameterizedClause handles COALESCE with multiple args") {
        val expr = parseOk("COALESCE(a, b, 0) > 0")
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldContain "COALESCE(\"a\", \"b\", ?)"
    }

    // ── Canonical fingerprint: remaining branches ──────────────

    test("canonicalize handles NOT") {
        val expr = parseOk("NOT x = 1")
        FilterDslTranslator.canonicalize(expr) shouldBe "NOT x = 1"
    }

    test("canonicalize handles OR") {
        val expr = parseOk("a = 1 OR b = 2")
        FilterDslTranslator.canonicalize(expr) shouldBe "a = 1 OR b = 2"
    }

    test("canonicalize handles IN list") {
        val expr = parseOk("id IN (1, 2, 3)")
        FilterDslTranslator.canonicalize(expr) shouldBe "id IN (1, 2, 3)"
    }

    test("canonicalize handles IS NOT NULL") {
        val expr = parseOk("x IS NOT NULL")
        FilterDslTranslator.canonicalize(expr) shouldBe "x IS NOT NULL"
    }

    test("canonicalize handles decimal literal") {
        val expr = parseOk("price = 9.99")
        FilterDslTranslator.canonicalize(expr) shouldBe "price = 9.99"
    }

    test("canonicalize handles unary minus") {
        val expr = parseOk("x = -5")
        FilterDslTranslator.canonicalize(expr) shouldBe "x = -5"
    }

    test("canonicalize handles function call") {
        val expr = parseOk("LOWER(x) = 'a'")
        FilterDslTranslator.canonicalize(expr) shouldBe "LOWER(x) = 'a'"
    }

    test("canonicalize handles arithmetic") {
        val expr = parseOk("a + b > 0")
        FilterDslTranslator.canonicalize(expr) shouldBe "a + b > 0"
    }

    test("canonicalize handles grouped value") {
        val expr = parseOk("(a + b) > 0")
        FilterDslTranslator.canonicalize(expr) shouldBe "(a + b) > 0"
    }

    // ── Complex expressions ────────────────────────────────────

    test("complex filter round-trips through parse and canonicalize") {
        val input = "status IN ('OPEN', 'PENDING') AND (amount > 100 OR priority = 1) AND email IS NOT NULL"
        val expr = parseOk(input)
        val canon = FilterDslTranslator.canonicalize(expr)
        canon shouldBe "status IN ('OPEN', 'PENDING') AND (amount > 100 OR priority = 1) AND email IS NOT NULL"
    }

    test("complex filter produces correct SQL and params") {
        val input = "status = 'OPEN' AND age >= 18 AND name IS NOT NULL"
        val expr = parseOk(input)
        val clause = FilterDslTranslator.toParameterizedClause(expr, DatabaseDialect.POSTGRESQL)
        clause.sql shouldBe "\"status\" = ? AND \"age\" >= ? AND \"name\" IS NOT NULL"
        clause.params shouldBe listOf("OPEN", 18L)
    }
})
