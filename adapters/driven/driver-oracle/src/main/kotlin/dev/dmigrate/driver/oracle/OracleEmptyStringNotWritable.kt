package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.data.OracleEmptyString

/**
 * Der Quellwert ist ein leerer String, die Oracle-Zielspalte ist
 * `NOT NULL` -- und Oracle setzt beides gleich.
 *
 * Eigene Ausnahme statt der durchgereichten `ORA-01400`: die
 * Treibermeldung sagt "cannot insert NULL" und laesst den Anwender im
 * Glauben, die Quelle traege NULL. Sie tut es nicht; der Unterschied
 * verschwindet erst im Ziel.
 */
class OracleEmptyStringNotWritable(column: String) : RuntimeException(
    "Column '$column' is NOT NULL in the Oracle target, but the source row carries an empty string. " +
        "Oracle treats '' as NULL, so the value cannot be written as-is. Declare what should happen " +
        "instead: `write.oracle.empty_string` in .d-migrate.yaml or --oracle-empty-string " +
        "(`${OracleEmptyString.LITERAL_PREFIX}<text>` substitutes that text; `error` is the default and " +
        "keeps the run from changing data).",
)
