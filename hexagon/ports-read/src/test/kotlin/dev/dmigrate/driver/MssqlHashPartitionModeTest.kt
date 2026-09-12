package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Die Abbildung des CLI-Wertes auf die Strategie. Sie entscheidet, ob eine
 * `hash`-Partitionierung gegen SQL Server mit `E055` abbricht oder ueber eine
 * persistierte berechnete Spalte nachgebaut wird — ein unerkannter Wert darf
 * deshalb nicht stillschweigend auf den Default fallen, sondern muss `null`
 * liefern, damit der Aufrufer ihn als Eingabefehler melden kann.
 */
class MssqlHashPartitionModeTest : FunSpec({

    test("every mode is reachable by its CLI name") {
        MssqlHashPartitionMode.entries.forEach { mode ->
            MssqlHashPartitionMode.fromCliName(mode.cliName) shouldBe mode
        }
    }

    test("the CLI names are the ones the handbook prints") {
        MssqlHashPartitionMode.ACTION_REQUIRED.cliName shouldBe "action_required"
        MssqlHashPartitionMode.COMPUTED_COLUMN.cliName shouldBe "computed_column"
    }

    test("the lookup ignores case") {
        MssqlHashPartitionMode.fromCliName("COMPUTED_COLUMN") shouldBe MssqlHashPartitionMode.COMPUTED_COLUMN
        MssqlHashPartitionMode.fromCliName("Action_Required") shouldBe MssqlHashPartitionMode.ACTION_REQUIRED
    }

    test("an unknown value is null, not the default") {
        MssqlHashPartitionMode.fromCliName("").shouldBeNull()
        MssqlHashPartitionMode.fromCliName("computed column").shouldBeNull()
        MssqlHashPartitionMode.fromCliName("hash").shouldBeNull()
    }
})
