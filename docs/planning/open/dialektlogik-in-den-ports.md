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
| `SqliteCastPreflightStage` | baut einen **MySQL**-Triggernamen |

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

1. Ob die drei MySQL-Stages (`MysqlSequenceCanonicityStage`,
   der MySQL-Teil von `SequencePreserveStage`, der MySQL-Triggername in
   `SqliteCastPreflightStage`) hinter einen Port gehören, den `driver-mysql`
   implementiert — analog zum Preserve-Restore.
2. Ob `SqliteCastPreflightStage` einen MySQL-Triggernamen bauen darf. Das
   sieht nach einem eigenen Befund aus und ist vor Punkt 1 zu prüfen.

## Berührte Stellen

- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlSequenceSupportNaming.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlCheckEnforcementCapability.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/MysqlSequenceCanonicityGate.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MysqlSequenceCanonicityStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SqliteCastPreflightStage.kt`
