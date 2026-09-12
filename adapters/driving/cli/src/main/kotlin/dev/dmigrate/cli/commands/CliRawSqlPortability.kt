package dev.dmigrate.cli.commands

import dev.dmigrate.driver.RawSqlExpressionPortability

/**
 * Die Bindung des Urteils ueber rohen Ausdruckstext an den Adapter, der die
 * Grammatik der Dialekte kennt.
 *
 * Als benanntes Objekt und nicht als Lambda in der Verdrahtung: so laesst sich
 * pruefen, dass die Bindung das tut, was der Migrationspfad von ihr erwartet —
 * eine Verdrahtung, die man nicht pruefen kann, ist eine Verdrahtung, die
 * irgendwann fehlt.
 */
internal object CliRawSqlPortability {

    val assess: RawSqlPortabilityFn = { text, dialect ->
        val verdict = RawSqlExpressionPortability.assess(text, dialect)
        if (verdict.portable) null else verdict.reason ?: "unsupported syntax"
    }
}
