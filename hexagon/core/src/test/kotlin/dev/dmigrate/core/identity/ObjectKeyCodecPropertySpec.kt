package dev.dmigrate.core.identity

import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property-Based Testing für [ObjectKeyCodec] (LN-046, ADR 0029, Slice-Phase A).
 *
 * [ObjectKeyCodec] verspricht eine "lossless and round-trippable"
 * String-Kodierung von Routine-/Trigger-Identität. Diese Specs verifizieren
 * genau diese Zusage über generierte Eingaben statt handverlesener Beispiele —
 * inklusive der subtilen Randfälle (Reserved-Trenner `% ( ) , :`, überlappende
 * `%`-Escape-Sequenzen, leere Komponenten), für die kotest-property mit
 * Shrinking auf minimale Gegenbeispiele gemacht ist.
 */
class ObjectKeyCodecPropertySpec : FunSpec({

    // Alphabet, das die Reserved-Trenner und `%`-Sequenzen bewusst häufig trifft,
    // damit die Escape-/Unescape-Logik unter Druck steht (nicht nur „safe" ASCII).
    val richChars = ('a'..'d').toList() + ('0'..'1') + listOf('%', '(', ')', ',', ':', ' ', '_')
    val component: Arb<String> = Arb.list(Arb.of(richChars), 0..12).map { it.joinToString("") }
    val parameter: Arb<ParameterDefinition> =
        Arb.bind(component, component, Arb.enum<ParameterDirection>()) { name, type, direction ->
            ParameterDefinition(name = name, type = type, direction = direction)
        }

    test("decode(encode(s)) == s für beliebige Komponenten") {
        checkAll(component) { s ->
            ObjectKeyCodec.decode(ObjectKeyCodec.encode(s)) shouldBe s
        }
    }

    test("routineKey/parseRoutineKey rekonstruiert Name und (direction,type)-Paare") {
        checkAll(component, Arb.list(parameter, 0..5)) { name, params ->
            val key = ObjectKeyCodec.routineKey(name, params)
            val (decodedName, pairs) = ObjectKeyCodec.parseRoutineKey(key)
            decodedName shouldBe name
            pairs shouldBe params.map { it.direction.name.lowercase() to it.type }
        }
    }

    test("routineName liefert den bloßen dekodierten Namen eines kanonischen Keys") {
        checkAll(component, Arb.list(parameter, 0..3)) { name, params ->
            ObjectKeyCodec.routineName(ObjectKeyCodec.routineKey(name, params)) shouldBe name
        }
    }

    test("triggerKey/parseTriggerKey round-trippt (table, name)") {
        checkAll(component, component) { table, name ->
            ObjectKeyCodec.parseTriggerKey(ObjectKeyCodec.triggerKey(table, name)) shouldBe (table to name)
        }
    }

    test("triggerName liefert den bloßen dekodierten Namen eines kanonischen Keys") {
        checkAll(component, component) { table, name ->
            ObjectKeyCodec.triggerName(ObjectKeyCodec.triggerKey(table, name)) shouldBe name
        }
    }
})
