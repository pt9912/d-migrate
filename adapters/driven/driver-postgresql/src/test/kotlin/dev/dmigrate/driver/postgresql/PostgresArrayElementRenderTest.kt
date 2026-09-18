package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * S3 — der PostgreSQL-Generator rendert das Array-Element in seinem Typ.
 *
 * `resolveElementType` kannte nur `text`, `integer`, `boolean` und `uuid`:
 * `bigint[]`, `double precision[]`, `numeric[]` und `json[]` wurden damit
 * schon PostgreSQL → PostgreSQL still `TEXT[]`, obwohl der Reverse die
 * Elementart richtig gelesen hatte. Der Kanonisierer ist die gelebte
 * Zusammensetzung `reverse(toSql(t))` und spiegelt denselben Satz — sonst
 * projizierte er ein Element auf `text`, das der Reverse richtig liest.
 */
class PostgresArrayElementRenderTest : FunSpec({

    val mapper = PostgresTypeMapper()

    val rendered = mapOf(
        "text" to "TEXT[]",
        "integer" to "INTEGER[]",
        "biginteger" to "BIGINT[]",
        "boolean" to "BOOLEAN[]",
        "uuid" to "UUID[]",
        "float" to "DOUBLE PRECISION[]",
        "decimal" to "NUMERIC[]",
        "json" to "JSONB[]",
    )

    test("jede Elementart, die der Reverse benennt, wird in ihrem Typ gerendert") {
        for ((element, ddl) in rendered) {
            withClue("array($element)") { mapper.toSql(NeutralType.Array(element)) shouldBe ddl }
        }
    }

    // Gegenprobe: ein Element, das der Reverse **nicht** benennt, bleibt
    // `TEXT[]` — das ist N1s Fall, und dort ist `text` die gelesene Wahrheit.
    test("eine unbekannte Elementart bleibt TEXT[]") {
        mapper.toSql(NeutralType.Array("date")) shouldBe "TEXT[]"
        mapper.toSql(NeutralType.Array("inet")) shouldBe "TEXT[]"
    }

    test("der Kanonisierer haelt jede benannte Elementart fest") {
        for (element in rendered.keys) {
            withClue("array($element)") {
                PostgresNeutralTypeCanonicalizer.canonicalize(NeutralType.Array(element)) shouldBe
                    NeutralType.Array(element)
            }
        }
    }

    // Was der Reverse als `text` liest, projiziert der Kanonisierer auch so —
    // die beiden Seiten sind derselbe geschlossene Satz.
    test("eine unbekannte Elementart projiziert auf array(text)") {
        PostgresNeutralTypeCanonicalizer.canonicalize(NeutralType.Array("date")) shouldBe
            NeutralType.Array("text")
    }
})
