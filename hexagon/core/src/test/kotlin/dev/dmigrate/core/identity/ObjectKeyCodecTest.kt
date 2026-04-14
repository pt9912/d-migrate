package dev.dmigrate.core.identity

import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ObjectKeyCodecTest : FunSpec({

    // --- Percent-Encoding ---

    test("encode leaves plain names unchanged") {
        ObjectKeyCodec.encode("my_function") shouldBe "my_function"
    }

    test("encode encodes reserved separators") {
        ObjectKeyCodec.encode("name(x)") shouldBe "name%28x%29"
        ObjectKeyCodec.encode("a,b") shouldBe "a%2Cb"
        ObjectKeyCodec.encode("a:b") shouldBe "a%3Ab"
        ObjectKeyCodec.encode("100%") shouldBe "100%25"
    }

    test("decode reverses encode for all reserved chars") {
        val original = "tricky%name(a:b,c)"
        ObjectKeyCodec.decode(ObjectKeyCodec.encode(original)) shouldBe original
    }

    // --- Routine Keys ---

    test("routineKey with no parameters") {
        val key = ObjectKeyCodec.routineKey("do_stuff", emptyList())
        key shouldBe "do_stuff()"
    }

    test("routineKey with single parameter") {
        val params = listOf(ParameterDefinition("x", "integer", ParameterDirection.IN))
        val key = ObjectKeyCodec.routineKey("get_user", params)
        key shouldBe "get_user(in:integer)"
    }

    test("routineKey with multiple parameters") {
        val params = listOf(
            ParameterDefinition("id", "integer", ParameterDirection.IN),
            ParameterDefinition("result", "text", ParameterDirection.OUT),
        )
        val key = ObjectKeyCodec.routineKey("fetch", params)
        key shouldBe "fetch(in:integer,out:text)"
    }

    test("routineKey round-trips through parseRoutineKey") {
        val params = listOf(
            ParameterDefinition("a", "decimal(10,2)", ParameterDirection.IN),
            ParameterDefinition("b", "varchar", ParameterDirection.INOUT),
        )
        val key = ObjectKeyCodec.routineKey("calc", params)
        val (name, parsed) = ObjectKeyCodec.parseRoutineKey(key)
        name shouldBe "calc"
        parsed shouldBe listOf("in" to "decimal(10,2)", "inout" to "varchar")
    }

    test("overloaded routines produce distinct keys") {
        val key1 = ObjectKeyCodec.routineKey("process", listOf(
            ParameterDefinition("x", "integer", ParameterDirection.IN),
        ))
        val key2 = ObjectKeyCodec.routineKey("process", listOf(
            ParameterDefinition("x", "text", ParameterDirection.IN),
        ))
        val key3 = ObjectKeyCodec.routineKey("process", listOf(
            ParameterDefinition("x", "integer", ParameterDirection.IN),
            ParameterDefinition("y", "integer", ParameterDirection.IN),
        ))
        // All three must be distinct
        val keys = setOf(key1, key2, key3)
        keys.size shouldBe 3
    }

    test("routineKey with reserved chars in name") {
        val key = ObjectKeyCodec.routineKey("my:func(v1)", listOf(
            ParameterDefinition("p", "text", ParameterDirection.IN),
        ))
        val (name, params) = ObjectKeyCodec.parseRoutineKey(key)
        name shouldBe "my:func(v1)"
        params shouldBe listOf("in" to "text")
    }

    // --- Trigger Keys ---

    test("triggerKey simple case") {
        val key = ObjectKeyCodec.triggerKey("users", "audit_insert")
        key shouldBe "users::audit_insert"
    }

    test("triggerKey round-trips through parseTriggerKey") {
        val key = ObjectKeyCodec.triggerKey("orders", "before_delete")
        val (table, name) = ObjectKeyCodec.parseTriggerKey(key)
        table shouldBe "orders"
        name shouldBe "before_delete"
    }

    test("same trigger name on different tables produces distinct keys") {
        val key1 = ObjectKeyCodec.triggerKey("users", "audit")
        val key2 = ObjectKeyCodec.triggerKey("orders", "audit")
        key1 shouldBe "users::audit"
        key2 shouldBe "orders::audit"
        (key1 != key2) shouldBe true
    }

    test("triggerKey with reserved chars in table or name") {
        val key = ObjectKeyCodec.triggerKey("my::table", "trg:name")
        val (table, name) = ObjectKeyCodec.parseTriggerKey(key)
        table shouldBe "my::table"
        name shouldBe "trg:name"
    }
})
