package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * „Unbekannte Version" heisst nicht bei jeder Faehigkeit dasselbe — und genau
 * das war die Frage, an der dieser Schnitt hing.
 *
 * Zwei Klassen:
 *
 * - Die Faehigkeit entscheidet, **wie die Angabe des Autors gerendert wird**
 *   (`supportsVirtualComputedColumns`: `stored: false`). Unbekannt ist dort
 *   **optimistisch**, weil die konservative Wahl etwas anderes rendern wuerde
 *   als das Geschriebene — stille Umdeutung.
 * - Die Faehigkeit entscheidet eine **Bequemlichkeit oder eine Verweigerung**
 *   (`supportsDropIfExists`, `supportsComputedExpressionInPlace`). Unbekannt
 *   ist dort **konservativ**, weil die optimistische Wahl einen Fehler fuer
 *   etwas erzeugte, das niemand verlangt hat.
 *
 * Der bequeme Weg — den Pin einmal im Einstieg einsetzen — kippt die zweite
 * Klasse still. Diese Spec haelt beide Seiten fest, damit das nicht
 * unbemerkt zurueckkommt.
 */
class DialectCapabilitiesVersionClassesTest : FunSpec({

    test("optimistic: without a version the newest measured PostgreSQL answers") {
        DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, null)
            .supportsVirtualComputedColumns shouldBe true
        DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL)
            .supportsVirtualComputedColumns shouldBe true
    }

    test("optimistic: an explicit older version still decides against it") {
        DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, PostgresServerVersion(17, 6))
            .supportsVirtualComputedColumns shouldBe false
        DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, PostgresServerVersion(18, 0))
            .supportsVirtualComputedColumns shouldBe true
    }

    test("conservative: without a version Oracle does not get IF EXISTS") {
        DialectCapabilities.forTarget(DatabaseDialect.ORACLE, null).supportsDropIfExists shouldBe false
        DialectCapabilities.forDialect(DatabaseDialect.ORACLE).supportsDropIfExists shouldBe false
    }

    test("conservative: a known Oracle 23 does get it, an older one does not") {
        DialectCapabilities.forTarget(DatabaseDialect.ORACLE, OracleServerVersion(23, "23.0.0.0.0"))
            .supportsDropIfExists shouldBe true
        DialectCapabilities.forTarget(DatabaseDialect.ORACLE, OracleServerVersion(19, "19.0.0.0.0"))
            .supportsDropIfExists shouldBe false
    }

    test("conservative: PostgreSQL's in-place expression change is not assumed") {
        DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, null)
            .supportsComputedExpressionInPlace shouldBe false
        DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, PostgresServerVersion(16, 4))
            .supportsComputedExpressionInPlace shouldBe false
        DialectCapabilities.forTarget(DatabaseDialect.POSTGRESQL, PostgresServerVersion(17, 0))
            .supportsComputedExpressionInPlace shouldBe true
    }

    test("the four dialects without a threshold answer the same either way") {
        listOf(DatabaseDialect.MYSQL, DatabaseDialect.SQLITE, DatabaseDialect.MSSQL).forEach { dialect ->
            val withoutVersion = DialectCapabilities.forTarget(dialect, null)
            // Fuer diese drei gibt es keinen Versionstyp; die Antwort haengt
            // also an nichts, was sich aendern koennte.
            withoutVersion.supportsDropIfExists shouldBe true
            withoutVersion.supportsVirtualComputedColumns shouldBe true
        }
        DialectCapabilities.forTarget(DatabaseDialect.MYSQL, null).supportsComputedExpressionInPlace shouldBe true
        DialectCapabilities.forTarget(DatabaseDialect.SQLITE, null).supportsComputedExpressionInPlace shouldBe true
        DialectCapabilities.forTarget(DatabaseDialect.MSSQL, null).supportsComputedExpressionInPlace shouldBe false
    }
})
