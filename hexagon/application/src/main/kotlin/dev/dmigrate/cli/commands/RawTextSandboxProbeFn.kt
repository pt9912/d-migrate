package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.RawTextServerForm
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import java.nio.file.Path

/**
 * Fragt den Wegwerf-Sandkasten, welche Form der Server aus dem Soll machen
 * wuerde.
 *
 * Gleiche Bauart wie [CheckPreflightProbeFn]: die Verdrahtung entscheidet, ob
 * es ihn gibt. `null` als Ergebnis heisst „keine Serverform" — der Dialekt kann
 * keinen Sandkasten, der Schalter steht nicht, oder das Soll liess sich dort
 * nicht anwenden.
 */
internal typealias RawTextSandboxProbeFn =
    (CompareOperand.Database, Path?, SchemaDefinition, DatabaseDialect) -> RawTextServerForm?
