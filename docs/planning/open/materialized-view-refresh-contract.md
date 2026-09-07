# Materialized Views: der Refresh-Vertrag im Migrationsreport

## Befund

Der Report führt je Materialized View einen Vertrag (`materializedViews[]`)
mit `stalenessAfterUp`, `refreshSteps`, `locking` und `rollback`. Zwei
Aussagen darin sind für Oracle jetzt ungenau, seit der Dialekt MVs wirklich
rendert.

**1. `rollback` verspricht mehr, als eine Rücknahme leisten kann.** Für ein
`ReplaceMaterializedView` steht dort `SOURCE_QUERY_AVAILABLE_…`: die
*Definition* lässt sich wiederherstellen. Die materialisierten Zeilen nicht —
in keiner Richtung, denn `CREATE MATERIALIZED VIEW` baut sie neu auf. Für
PostgreSQL galt das schon, war aber weniger sichtbar.

**2. Auf Oracle ist ein `DROP` + `CREATE` keine atomare Einheit.**
`MigrationExecutionStatusBuilder` fasst beide Anweisungen zu einer Gruppe
zusammen, weil sie dieselbe Operations-ID tragen — das ist richtig. Die
PostgreSQL-Begründung dahinter stützt sich aber auf transaktionales DDL („kein
Leser sieht den Zwischenzustand"). Oracle committet vor und nach jeder
DDL-Anweisung implizit; scheitert das `CREATE` nach erfolgreichem `DROP`, ist
die Sicht weg und nichts rollt zurück.

Das ist kein Oracle-Sonderfall des Slices, sondern eine allgemeine Aussage:
`MigrationExecutionStatusBuilder.transactionBoundary` meldet für
`RUNNER_OWNED` unbesehen `INSIDE`, unabhängig von `hints.transactionBehavior`.
Bei MVs fällt es nur besonders auf, weil dort echte Daten am Zwischenzustand
hängen.

## Was fehlt

Ein **ausführbarer Refresh-Schritt** gibt es nicht: `schema refresh
materialized-view` existiert nicht, und der Report führt das als eigenen
Blocker (`BLOCKED_SCHEMA_REFRESH_UNSUPPORTED`). Solange er fehlt, kann eine
Migration eine Sicht neu anlegen, aber nicht auffrischen — bei `on demand`
bleibt sie nach dem `CREATE` gefüllt, nach einem späteren Basistabellen-Wechsel
aber veraltet, ohne dass d-migrate etwas dagegen tun kann.

## Zu entscheiden

1. Ob `transactionBoundary` die `DialectExecutionHints.transactionBehavior`
   auswerten soll. Das betrifft **jede** Oracle-DDL-Anweisung, nicht nur MVs,
   und ändert die Berichtsform breit.
2. Ob der Rollback-Vertrag einen eigenen Wert für „Definition ja, Inhalt nein"
   braucht, statt den vorhandenen mitzubenutzen.
3. Ob `schema refresh materialized-view` gebaut wird — dann bekäme der
   Vertrag seinen ausführbaren Schritt.

## Berührte Stellen

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateMaterializedViewContractBuilder.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MigrationExecutionStatusBuilder.kt`
- `spec/cli-spec.md` (die normative Tabelle der MV-Blocker)
