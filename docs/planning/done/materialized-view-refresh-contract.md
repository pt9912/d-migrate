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

## Was daraus wurde

**Punkt 1 war keine Entscheidung, sondern eine Abweichung von der Spec.**
`spec/cli-spec.md` sagt, `transactionBoundary` stehe „relativ zur **effektiven**
Runner- oder Stream-Transaktion" — der Code meldete für `RUNNER_OWNED`
unbesehen `INSIDE`. Für MySQL und Oracle ist das falsch: beide erklären
`transactionBehavior = IMPLICIT_COMMIT`, ihre DDL committet vor und nach sich
selbst.

Schärfer noch: `TransactionBehavior.UNKNOWN` trägt die Regel im eigenen
Vertragstext — *„The report MUST NOT claim full rollback for `UNKNOWN`"* — und
genau das tat der Bericht. `INSIDE` gilt jetzt nur für
`FULLY_TRANSACTIONAL`; alles andere meldet `NONE`. Ein eigener Enum-Wert war
nicht nötig: `NONE` heißt „diese Gruppe steht in keiner Transaktion", und
welcher Fall vorliegt, sagt das danebenstehende `transactionScope`.

Der Befund ist damit **nicht** MV- und nicht Oracle-spezifisch. Bei
Materialized Views fiel er nur auf, weil dort Daten am Zwischenzustand hängen.

**Punkt 2 brauchte keinen neuen Wert.** `SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED`
sagt bereits wörtlich, was gilt: die Quellabfrage ist da, ein Refresh bleibt
nötig. Was fehlte, war die Aussage in der Spec, dass `rollback` **immer** nur
von der Definition spricht und nie von den materialisierten Zeilen — in keiner
Richtung, denn `CREATE MATERIALIZED VIEW` baut sie neu auf. Das steht jetzt
dort. Nebenbei korrigiert: die Vertragstabelle war PostgreSQL-only formuliert,
obwohl Oracle Materialized Views rendert (und als einziger Dialekt auch ihre
Refresh-Einstellung).

**Punkt 3 bleibt offen — und ist keine Bau-, sondern eine Spec-Frage.**
`schema refresh materialized-view` kommt in `spec/cli-spec.md` nur als
geblockte *Absicht* in einer Tabellenzeile vor; als Befehl ist er nirgends
beschrieben. Er steht deshalb auch nicht im Tracker der spezifizierten, aber
nicht gebauten Befehle. Ihn zu bauen hieße, ihn zuerst zu spezifizieren — eine
Erweiterung der Befehlsoberfläche, die dem Eigner gehört. Eigenes Ticket:
[`schema-refresh-materialized-view.md`](../open/schema-refresh-materialized-view.md).

## Berührte Stellen

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateMaterializedViewContractBuilder.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/MigrationExecutionStatusBuilder.kt`
- `spec/cli-spec.md` (die normative Tabelle der MV-Blocker)
