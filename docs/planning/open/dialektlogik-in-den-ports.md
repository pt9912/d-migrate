# Dialektwissen im Lesepfad-Port, weil die Anwendungsschicht es braucht

## Status

Vorabklärung. Der erste Teil ist **erledigt** (siehe
[`done/preserve-restore-in-die-treiber.md`](../done/preserve-restore-in-die-treiber.md));
was hier steht, ist der Rest.

## Befund

`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/` führt 38 Dateien,
zehn davon tragen einen Dialektnamen. Sie zerfallen in zwei Gruppen, und nur
eine davon ist ein Befund:

**Port-Vokabular — richtig dort.** `MysqlServerVersion`,
`OracleServerVersion`, `SqliteLiveCatalog`, `SqliteCastPreflight`,
`MysqlSequenceCanonicity` stehen **in** einer Port-Signatur (`SchemaReadResult`,
`DdlGenerationOptions`). Der Adapter legt den Wert hinein, die
Anwendungsschicht liest ihn heraus. `.a-check.yml` erlaubt
`application → ports`, aber nicht `application → adapters`; im Adapter
definiert, wäre die Abhängigkeit umgedreht.

**Dialektlogik — der Befund.** `MysqlSequenceSupportNaming`,
`MysqlCheckEnforcementResolver`, `MysqlSequenceCanonicityGate` kommen in
**keiner** Port-Signatur vor. Sie liegen dort, weil die Anwendungsschicht
selbst MySQL-spezifisch arbeitet:

| Stelle | Was sie tut |
|---|---|
| `MysqlSequenceCanonicityStage` | baut MySQL-Triggernamen, prüft das MySQL-Hilfstabellen-Format |
| `SequencePreserveStage` | prüft die MySQL-Namenslisten auf Nichtleere |
| `MigrationPreflightPlanner` | plant die MySQL-Sequenz-Kanonizität mit, samt Triggername |

## Warum das zählt

Ein Modul, das Dialektwissen führt, ohne einen Dialekt zu besitzen, wächst mit
jedem neuen Dialekt: der sechste erweitert dieselben geteilten Klassen, statt
sein Wissen mitzubringen. Derselbe Mechanismus, der schon bei
`driver-common` und der View-Portabilität auffiel
([`done/view-query-transformer-per-dialect-rules.md`](../done/view-query-transformer-per-dialect-rules.md)).

Der Preserve-Restore-Fall zeigt auch die Behebung: nicht die Dateien
verschieben, sondern die **Naht** finden, an der die Anwendungsschicht das
Dialektwissen überhaupt braucht — und sie schließen. Danach wandern die
Dateien von selbst.

## Was zu klären ist

1. Ob die drei MySQL-Stellen (`MysqlSequenceCanonicityStage`,
   der MySQL-Teil von `SequencePreserveStage`, der MySQL-Zweig von
   `MigrationPreflightPlanner`) hinter einen Port gehören, den `driver-mysql`
   implementiert — analog zum Preserve-Restore.
2. ~~Ob `SqliteCastPreflightStage` einen MySQL-Triggernamen bauen darf.~~
   **Geprüft 2026-09-13 — kein eigener Befund; die Beobachtung war falsch
   notiert.** Der Verdacht kam vom Dateinamen. `SqliteCastPreflightStage.kt`
   führt **zwei** Objekte auf oberster Ebene, und das gleichnamige rührt
   keinen fremden Dialekt an: es prüft `dialect != SQLITE` und steigt sonst
   aus. Den MySQL-Triggernamen baut `MigrationPreflightPlanner`, der im selben
   File wohnt und mit dem SQLite-Stage nichts zu tun hat — er plant **drei**
   Vorprüfungen (SQLite-Cast, dialektübergreifendes CHECK,
   MySQL-Sequenz-Kanonizität), jede hinter ihrem eigenen Dialekt-Gate.

   Übrig bleibt eine Benennung, kein Defekt: ein allgemeiner Planer wohnt in
   einer Datei, die nach einer seiner drei Aufgaben heißt. Sein MySQL-Zweig
   ist derselbe Fall wie die Zeilen der Tabelle oben und gehört damit **in**
   Punkt 1, nicht davor.

   Mitgeprüft, weil genau dort ein Fehler wehgetan hätte: Planer und Renderer
   bilden den Triggernamen über **dieselbe** Quelle — `MysqlSequenceNaming
   .triggerName` im Treiber delegiert an `MysqlSequenceSupportNaming
   .triggerName`. Die beiden können nicht auseinanderlaufen, das Gate
   vergleicht also nie gegen einen Namen, den es nicht gibt. Was bleibt, ist
   ein toter Parameter (`sequenceName`, per `@Suppress("UNUSED_PARAMETER")`
   stillgestellt), den der Umbau mitnehmen kann.

## Berührte Stellen

- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlSequenceSupportNaming.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlCheckEnforcementCapability.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlSequenceCanonicityGate.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MysqlSequenceCanonicityStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SqliteCastPreflightStage.kt`
  — wegen `MigrationPreflightPlanner` im selben File, nicht wegen des SQLite-Stage
